use std::ffi::{CStr, CString};
use std::os::raw::c_char;
use std::path::{Path, PathBuf};
use std::sync::Mutex;

use typst::foundations::{Bytes, Datetime};
use typst::text::{Font, FontBook};
use typst::syntax::{FileId, Source, RootedPath, VirtualPath, VirtualRoot};
use typst::{Library, World, LibraryExt, Features, Feature};
use typst::diag::FileResult;
use typst::utils::LazyHash;

use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jlong, jbyteArray};

/// Глобальное хранилище последней ошибки
static LAST_ERROR: Mutex<Option<String>> = Mutex::new(None);

/// Глобальное хранилище предупреждений
static LAST_WARNINGS: Mutex<Option<String>> = Mutex::new(None);

fn set_last_error(msg: String) {
    if let Ok(mut guard) = LAST_ERROR.lock() {
        *guard = Some(msg);
    }
}

fn get_last_error_msg() -> String {
    if let Ok(guard) = LAST_ERROR.lock() {
        guard.clone().unwrap_or_default()
    } else {
        String::new()
    }
}

fn set_last_warnings(msg: String) {
    if let Ok(mut guard) = LAST_WARNINGS.lock() {
        *guard = Some(msg);
    }
}

fn get_last_warnings_msg() -> String {
    if let Ok(guard) = LAST_WARNINGS.lock() {
        guard.clone().unwrap_or_default()
    } else {
        String::new()
    }
}

use typst::diag::SourceDiagnostic;

fn format_warnings(warnings: &[SourceDiagnostic]) -> String {
    let mut out = String::new();
    for w in warnings {
        out.push_str(&format!("warning: {}\n", w.message));
        for hint in &w.hints {
            out.push_str(&format!("  hint: {}\n", hint.v));
        }
    }
    out
}

/// Результат компиляции — тип формата
#[repr(C)]
pub enum CompileFormat {
    Pdf = 0,
    Html = 1,
}

/// Результат компиляции для передачи через C
#[repr(C)]
pub struct CompileResult {
    pub data: *mut u8,
    pub len: usize,
    pub format: CompileFormat,
}

/// Мир для мобильного окружения
struct MobileWorld {
    library: LazyHash<Library>,
    book: LazyHash<FontBook>,
    main_id: FileId,
    source: Source,
    font_data: Vec<Font>,
}

impl MobileWorld {
    fn new(source_code: &str, font_paths: &[PathBuf], enable_html: bool) -> Self {
        let library = if enable_html {
            let features: Features = vec![Feature::Html].into_iter().collect();
            Library::builder().with_features(features).build()
        } else {
            Library::default()
        };

        let mut fonts = Vec::new();
        let mut font_book = FontBook::new();

        // Загружаем встроенные шрифты typst-assets
        let embedded_fonts = typst_assets::fonts();
        for font_data in embedded_fonts {
            let bytes = Bytes::new(font_data);
            for font in Font::iter(bytes.clone()) {
                font_book.push(font.info().clone());
                fonts.push(font);
            }
        }

        // Дополнительные шрифты из пользовательской директории
        for path in font_paths {
            if let Ok(data) = std::fs::read(path) {
                let bytes = Bytes::new(data);
                for font in Font::iter(bytes.clone()) {
                    font_book.push(font.info().clone());
                    fonts.push(font);
                }
            }
        }

        eprintln!("Total fonts loaded: {}", fonts.len());

        let vpath = VirtualPath::new("main.typ").unwrap();
        let rooted = RootedPath::new(VirtualRoot::Project, vpath);
        let file_id = FileId::new(rooted);
        let source = Source::new(file_id, source_code.to_string());

        MobileWorld {
            library: LazyHash::new(library),
            book: LazyHash::new(font_book),
            main_id: file_id,
            source,
            font_data: fonts,
        }
    }
}

impl World for MobileWorld {
    fn library(&self) -> &LazyHash<Library> {
        &self.library
    }

    fn book(&self) -> &LazyHash<FontBook> {
        &self.book
    }

    fn main(&self) -> FileId {
        self.main_id
    }

    fn source(&self, id: FileId) -> FileResult<Source> {
        if id == self.main_id {
            Ok(self.source.clone())
        } else {
            Err(typst::diag::FileError::NotFound(PathBuf::new()))
        }
    }

    fn file(&self, _id: FileId) -> FileResult<Bytes> {
        Err(typst::diag::FileError::NotFound(PathBuf::new()))
    }

    fn font(&self, index: usize) -> Option<Font> {
        self.font_data.get(index).cloned()
    }

    fn today(&self, _offset: Option<typst::foundations::Duration>) -> Option<Datetime> {
        Datetime::from_ymd(2024, 1, 1)
    }
}

// --- Core C FFI Functions ---

fn compile_inner(source: &str, font_paths: &[PathBuf]) -> CompileResult {
    let world = MobileWorld::new(source, font_paths, false);
    let result = typst::compile::<typst_layout::PagedDocument>(&world);

    let warnings_text = format_warnings(&result.warnings);
    if !warnings_text.is_empty() {
        set_last_warnings(warnings_text.clone());
        eprintln!("{}", warnings_text);
    } else {
        set_last_warnings(String::new());
    }

    match result.output {
        Ok(document) => {
            match typst_pdf::pdf(&document, &typst_pdf::PdfOptions::default()) {
                Ok(pdf_bytes) => {
                    let mut bytes = pdf_bytes.to_vec();
                    bytes.shrink_to_fit();
                    let len = bytes.len();
                    eprintln!("PDF compiled successfully, size: {} bytes", len);
                    let ptr = bytes.as_mut_ptr();
                    std::mem::forget(bytes);
                    CompileResult { data: ptr, len, format: CompileFormat::Pdf }
                }
                Err(e) => {
                    let msg = format!("PDF export error: {:?}", e);
                    eprintln!("{}", msg);
                    set_last_error(msg);
                    CompileResult { data: std::ptr::null_mut(), len: 0, format: CompileFormat::Pdf }
                }
            }
        }
        Err(errors) => {
            let mut error_msg = String::from("Compilation errors:\n");
            for error in &errors {
                let msg = format!("  - {}", error.message);
                eprintln!("Typst error: {}", msg);
                error_msg.push_str(&msg);
                error_msg.push('\n');
            }
            set_last_error(error_msg);
            CompileResult { data: std::ptr::null_mut(), len: 0, format: CompileFormat::Pdf }
        }
    }
}

fn compile_html_inner(source: &str, font_paths: &[PathBuf]) -> CompileResult {
    let world = MobileWorld::new(source, font_paths, true);
    let result = typst::compile::<typst_html::HtmlDocument>(&world);

    let warnings_text = format_warnings(&result.warnings);
    if !warnings_text.is_empty() {
        set_last_warnings(warnings_text.clone());
        eprintln!("{}", warnings_text);
    } else {
        set_last_warnings(String::new());
    }

    match result.output {
        Ok(document) => {
            match typst_html::html(&document, &typst_html::HtmlOptions::default()) {
                Ok(html_string) => {
                    let mut bytes = html_string.into_bytes();
                    bytes.shrink_to_fit();
                    let len = bytes.len();
                    eprintln!("HTML compiled successfully, size: {} bytes", len);
                    let ptr = bytes.as_mut_ptr();
                    std::mem::forget(bytes);
                    CompileResult { data: ptr, len, format: CompileFormat::Html }
                }
                Err(e) => {
                    let msg = format!("HTML export error: {:?}", e);
                    eprintln!("{}", msg);
                    set_last_error(msg);
                    CompileResult { data: std::ptr::null_mut(), len: 0, format: CompileFormat::Html }
                }
            }
        }
        Err(errors) => {
            let mut error_msg = String::from("HTML compilation errors:\n");
            for error in &errors {
                let msg = format!("  - {}", error.message);
                eprintln!("Typst HTML error: {}", msg);
                error_msg.push_str(&msg);
                error_msg.push('\n');
            }
            set_last_error(error_msg);
            CompileResult { data: std::ptr::null_mut(), len: 0, format: CompileFormat::Html }
        }
    }
}

fn get_font_paths(font_path: *const c_char) -> Vec<PathBuf> {
    unsafe {
        if font_path.is_null() {
            vec![]
        } else {
            match CStr::from_ptr(font_path).to_str() {
                Ok(s) => collect_fonts(&PathBuf::from(s)),
                Err(_) => vec![],
            }
        }
    }
}

#[no_mangle]
pub extern "C" fn compile_to_pdf(
    source_ptr: *const c_char,
    font_path: *const c_char,
) -> CompileResult {
    let source_code = unsafe {
        CStr::from_ptr(source_ptr).to_str().unwrap_or("").to_string()
    };
    let font_paths = get_font_paths(font_path);
    compile_inner(&source_code, &font_paths)
}

#[no_mangle]
pub extern "C" fn compile_to_html(
    source_ptr: *const c_char,
    font_path: *const c_char,
) -> CompileResult {
    let source_code = unsafe {
        CStr::from_ptr(source_ptr).to_str().unwrap_or("").to_string()
    };
    let font_paths = get_font_paths(font_path);
    compile_html_inner(&source_code, &font_paths)
}

fn collect_fonts(dir: &Path) -> Vec<PathBuf> {
    if !dir.exists() { return vec![]; }
    match std::fs::read_dir(dir) {
        Ok(rd) => rd.filter_map(|e| {
            let p = e.ok()?.path();
            let ext = p.extension()?.to_str()?;
            match ext.to_lowercase().as_str() {
                "ttf" | "otf" | "woff" | "woff2" => Some(p),
                _ => None,
            }
        }).collect(),
        Err(_) => vec![],
    }
}

#[no_mangle]
pub extern "C" fn free_compile_result(result: CompileResult) {
    if !result.data.is_null() {
        unsafe { let _ = Vec::from_raw_parts(result.data, result.len, result.len); }
    }
}

// --- JNI Functions ---

#[no_mangle]
pub extern "system" fn Java_com_typstwriter_MainActivity_compileToPdf(
    mut env: JNIEnv,
    _class: JClass,
    source: JString,
    font_path: JString,
) -> jlong {
    let source_str: String = match env.get_string(&source) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };

    let font_str: Option<String> = match env.get_string(&font_path) {
        Ok(s) => Some(s.into()),
        Err(_) => None,
    };

    let font_c_str = font_str.map(|s| CString::new(s).unwrap());
    let font_ptr = font_c_str.as_ref().map(|s| s.as_ptr()).unwrap_or(std::ptr::null());

    let result = compile_to_pdf(
        CString::new(source_str).unwrap().as_ptr(),
        font_ptr,
    );

    Box::into_raw(Box::new(result)) as jlong
}

#[no_mangle]
pub extern "system" fn Java_com_typstwriter_MainActivity_compileToHtml(
    mut env: JNIEnv,
    _class: JClass,
    source: JString,
    font_path: JString,
) -> jlong {
    let source_str: String = match env.get_string(&source) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };

    let font_str: Option<String> = match env.get_string(&font_path) {
        Ok(s) => Some(s.into()),
        Err(_) => None,
    };

    let font_c_str = font_str.map(|s| CString::new(s).unwrap());
    let font_ptr = font_c_str.as_ref().map(|s| s.as_ptr()).unwrap_or(std::ptr::null());

    let result = compile_to_html(
        CString::new(source_str).unwrap().as_ptr(),
        font_ptr,
    );

    Box::into_raw(Box::new(result)) as jlong
}

#[no_mangle]
pub extern "system" fn Java_com_typstwriter_MainActivity_freeCompileResult(
    _env: JNIEnv,
    _class: JClass,
    result_ptr: jlong,
) {
    if result_ptr != 0 {
        unsafe { let _ = Box::from_raw(result_ptr as *mut CompileResult); }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_typstwriter_MainActivity_getCompileResultLen(
    _env: JNIEnv,
    _class: JClass,
    result_ptr: jlong,
) -> jlong {
    if result_ptr == 0 { return 0; }
    unsafe { (*(result_ptr as *const CompileResult)).len as jlong }
}

#[no_mangle]
pub extern "system" fn Java_com_typstwriter_MainActivity_getCompileResultFormat(
    _env: JNIEnv,
    _class: JClass,
    result_ptr: jlong,
) -> jlong {
    if result_ptr == 0 { return -1; }
    unsafe {
        match (*(result_ptr as *const CompileResult)).format {
            CompileFormat::Pdf => 0,
            CompileFormat::Html => 1,
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_typstwriter_MainActivity_getCompileResultData(
    mut env: JNIEnv,
    _class: JClass,
    result_ptr: jlong,
) -> jbyteArray {
    if result_ptr == 0 { return std::ptr::null_mut(); }

    unsafe {
        let result = &*(result_ptr as *const CompileResult);
        if result.data.is_null() || result.len == 0 {
            return std::ptr::null_mut();
        }

        let bytes = std::slice::from_raw_parts(result.data, result.len);
        let i8_bytes: &[i8] = &*(bytes as *const [u8] as *const [i8]);
        let jni_bytes = match env.new_byte_array(result.len as i32) {
            Ok(b) => b,
            Err(_) => return std::ptr::null_mut(),
        };
        if env.set_byte_array_region(&jni_bytes, 0, i8_bytes).is_err() {
            return std::ptr::null_mut();
        }
        jni_bytes.into_raw()
    }
}

#[no_mangle]
pub extern "system" fn Java_com_typstwriter_MainActivity_getLastError(
    mut env: JNIEnv,
    _class: JClass,
) -> jni::sys::jstring {
    let error_msg = get_last_error_msg();
    let jstr = match env.new_string(&error_msg) {
        Ok(s) => s,
        Err(_) => return std::ptr::null_mut(),
    };
    jstr.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_typstwriter_MainActivity_getWarnings(
    mut env: JNIEnv,
    _class: JClass,
) -> jni::sys::jstring {
    let warnings_msg = get_last_warnings_msg();
    let jstr = match env.new_string(&warnings_msg) {
        Ok(s) => s,
        Err(_) => return std::ptr::null_mut(),
    };
    jstr.into_raw()
}
