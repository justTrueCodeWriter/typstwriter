package com.typstwriter;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.method.MetaKeyKeyListener;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.widget.TextView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.view.Gravity;
import java.io.InputStream;
import java.io.OutputStream;

public class MainActivity extends Activity {

    static {
        System.loadLibrary("typst_core");
    }

    // Native methods
    private static native long compileToPdf(String source, String fontPath);
    private static native long compileToHtml(String source, String fontPath);
    private static native int getCompileResultLen(long resultPtr);
    private static native int getCompileResultFormat(long resultPtr);
    private static native byte[] getCompileResultData(long resultPtr);
    private static native void freeCompileResult(long resultPtr);
    private static native String getLastError();

    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final int SAVE_FILE_REQUEST = 101;
    private static final int OPEN_FILE_REQUEST = 102;
    private static final int EXPORT_FILE_REQUEST = 103;

    private static final String PREFS_NAME = "TypstWriterPrefs";
    private static final String PREF_FONT_SIZE = "editor_font_size";
    private static final int DEFAULT_FONT_SIZE = 14;
    private static final int MIN_FONT_SIZE = 8;
    private static final int MAX_FONT_SIZE = 40;

    private EditText sourceEditor;
    private EditText fontSizeInput;
    private TextView statusText;
    private String currentFilePath = null;
    private Uri currentFileUri = null;
    private String exportFormat = "pdf";
    private int currentFontSize = DEFAULT_FONT_SIZE;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestStoragePermissions();

        // Загружаем сохранённый размер шрифта
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        currentFontSize = prefs.getInt(PREF_FONT_SIZE, DEFAULT_FONT_SIZE);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(16, 16, 16, 16);

        TextView title = new TextView(this);
        title.setText("Typst Writer");
        title.setTextSize(24);
        title.setGravity(Gravity.CENTER);
        layout.addView(title);

        // Поле размера шрифта
        LinearLayout sizeLayout = new LinearLayout(this);
        sizeLayout.setOrientation(LinearLayout.HORIZONTAL);
        sizeLayout.setGravity(Gravity.CENTER);

        TextView sizeLabel = new TextView(this);
        sizeLabel.setText("Font size: ");
        sizeLabel.setTextSize(14);
        sizeLayout.addView(sizeLabel);

        fontSizeInput = new EditText(this);
        fontSizeInput.setText(String.valueOf(currentFontSize));
        fontSizeInput.setTextSize(14);
        fontSizeInput.setInputType(EditorInfo.TYPE_CLASS_NUMBER);
        LinearLayout.LayoutParams sizeInputParams = new LinearLayout.LayoutParams(
            120, LinearLayout.LayoutParams.WRAP_CONTENT);
        fontSizeInput.setLayoutParams(sizeInputParams);
        fontSizeInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_GO) {
                applyFontSizeFromInput();
                return true;
            }
            return false;
        });
        // Применяем размер при потере фокуса
        fontSizeInput.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                applyFontSizeFromInput();
            }
        });
        sizeLayout.addView(fontSizeInput);

        TextView sizeHint = new TextView(this);
        sizeHint.setText("  (Ctrl+/Ctrl-)");
        sizeHint.setTextSize(12);
        sizeHint.setAlpha(0.5f);
        sizeLayout.addView(sizeHint);

        LinearLayout.LayoutParams sizeParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sizeParams.topMargin = 8;
        layout.addView(sizeLayout, sizeParams);

        // Редактор
        sourceEditor = new EditText(this);
        sourceEditor.setHint("Enter Typst code...");
        sourceEditor.setMinLines(10);
        sourceEditor.setGravity(Gravity.TOP | Gravity.LEFT);
        sourceEditor.setTextSize(TypedValue.COMPLEX_UNIT_SP, currentFontSize);
        sourceEditor.setTypeface(android.graphics.Typeface.MONOSPACE);
        LinearLayout.LayoutParams editorParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f);
        editorParams.topMargin = 8;
        layout.addView(sourceEditor, editorParams);

        // Tab → 4 пробела + Ctrl+/Ctrl-
        sourceEditor.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) {
                return false;
            }

            // Tab вставляет 4 пробела
            if (keyCode == KeyEvent.KEYCODE_TAB) {
                int start = sourceEditor.getSelectionStart();
                sourceEditor.getText().insert(start, "    ");
                return true;
            }

            // Ctrl+Plus / Ctrl+Minus
            boolean ctrlPressed = (event.getMetaState() & KeyEvent.META_CTRL_ON) != 0;
            if (ctrlPressed) {
                if (keyCode == KeyEvent.KEYCODE_EQUALS || keyCode == KeyEvent.KEYCODE_NUMPAD_ADD) {
                    changeFontSize(1);
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_MINUS || keyCode == KeyEvent.KEYCODE_NUMPAD_SUBTRACT) {
                    changeFontSize(-1);
                    return true;
                }
            }

            return false;
        });

        // File buttons
        LinearLayout fileLayout = new LinearLayout(this);
        fileLayout.setOrientation(LinearLayout.HORIZONTAL);
        fileLayout.setGravity(Gravity.CENTER);

        Button newButton = new Button(this);
        newButton.setText("New");
        newButton.setOnClickListener(v -> newFile());
        fileLayout.addView(newButton);

        Button openButton = new Button(this);
        openButton.setText("Open");
        openButton.setOnClickListener(v -> openFile());
        fileLayout.addView(openButton);

        Button saveButton = new Button(this);
        saveButton.setText("Save");
        saveButton.setOnClickListener(v -> saveFile());
        fileLayout.addView(saveButton);

        Button saveAsButton = new Button(this);
        saveAsButton.setText("Save As");
        saveAsButton.setOnClickListener(v -> saveFileAs());
        fileLayout.addView(saveAsButton);

        LinearLayout.LayoutParams fileParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        fileParams.topMargin = 8;
        layout.addView(fileLayout, fileParams);

        // Export buttons
        LinearLayout exportLayout = new LinearLayout(this);
        exportLayout.setOrientation(LinearLayout.HORIZONTAL);
        exportLayout.setGravity(Gravity.CENTER);

        Button pdfButton = new Button(this);
        pdfButton.setText("Export PDF");
        pdfButton.setOnClickListener(v -> exportTypst("pdf"));
        exportLayout.addView(pdfButton);

        Button htmlButton = new Button(this);
        htmlButton.setText("Export HTML");
        htmlButton.setOnClickListener(v -> exportTypst("html"));
        exportLayout.addView(htmlButton);

        LinearLayout.LayoutParams exportParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        exportParams.topMargin = 8;
        layout.addView(exportLayout, exportParams);

        statusText = new TextView(this);
        statusText.setText("Ready");
        statusText.setPadding(0, 16, 0, 0);
        layout.addView(statusText);

        setContentView(layout);
        sourceEditor.setText("#set page(width: 10cm, height: auto)\n\n= Hello from Typst Writer!\n\nThis is a test document compiled with native Typst library.");
    }

    private void changeFontSize(int delta) {
        currentFontSize = Math.max(MIN_FONT_SIZE, Math.min(MAX_FONT_SIZE, currentFontSize + delta));
        sourceEditor.setTextSize(TypedValue.COMPLEX_UNIT_SP, currentFontSize);
        fontSizeInput.setText(String.valueOf(currentFontSize));
        saveFontSize();
    }

    private void applyFontSizeFromInput() {
        try {
            int size = Integer.parseInt(fontSizeInput.getText().toString().trim());
            size = Math.max(MIN_FONT_SIZE, Math.min(MAX_FONT_SIZE, size));
            currentFontSize = size;
            sourceEditor.setTextSize(TypedValue.COMPLEX_UNIT_SP, currentFontSize);
            fontSizeInput.setText(String.valueOf(currentFontSize));
            saveFontSize();
        } catch (NumberFormatException ignored) {
            fontSizeInput.setText(String.valueOf(currentFontSize));
        }
    }

    private void saveFontSize() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        prefs.edit().putInt(PREF_FONT_SIZE, currentFontSize).apply();
    }

    private void requestStoragePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                }, PERMISSION_REQUEST_CODE);
            }
        }
    }

    private void newFile() {
        sourceEditor.setText("");
        currentFilePath = null;
        currentFileUri = null;
        statusText.setText("New file created");
    }

    private void saveFile() {
        if (currentFileUri != null) {
            writeToFile(currentFileUri);
        } else {
            saveFileAs();
        }
    }

    private void saveFileAs() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TITLE, "document.typ");
        startActivityForResult(intent, SAVE_FILE_REQUEST);
    }

    private void openFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"text/plain", "application/octet-stream"});
        startActivityForResult(intent, OPEN_FILE_REQUEST);
    }

    private void exportTypst(String format) {
        String source = sourceEditor.getText().toString();
        if (source.isEmpty()) {
            statusText.setText("Error: Source code is empty");
            return;
        }

        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        if ("html".equals(format)) {
            intent.setType("text/html");
            intent.putExtra(Intent.EXTRA_TITLE, "output.html");
        } else {
            intent.setType("application/pdf");
            intent.putExtra(Intent.EXTRA_TITLE, "output.pdf");
        }
        exportFormat = format;
        startActivityForResult(intent, EXPORT_FILE_REQUEST);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            try {
                if (requestCode == OPEN_FILE_REQUEST) {
                    getContentResolver().takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
                }
            } catch (Exception ignored) {}
            if (requestCode == SAVE_FILE_REQUEST) {
                writeToFile(uri);
            } else if (requestCode == OPEN_FILE_REQUEST) {
                readFromFile(uri);
            } else if (requestCode == EXPORT_FILE_REQUEST) {
                compileAndSave(uri, exportFormat);
            }
        }
    }

    private void writeToFile(Uri uri) {
        try {
            OutputStream os = getContentResolver().openOutputStream(uri);
            if (os != null) {
                os.write(sourceEditor.getText().toString().getBytes());
                os.close();
                currentFileUri = uri;
                currentFilePath = uri.getPath();
                statusText.setText("Saved to: " + uri.getLastPathSegment());
            }
        } catch (Exception e) {
            statusText.setText("Error saving: " + e.getMessage());
        }
    }

    private void readFromFile(Uri uri) {
        try {
            InputStream is = getContentResolver().openInputStream(uri);
            if (is != null) {
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = is.read(buf)) != -1) {
                    bos.write(buf, 0, n);
                }
                is.close();
                String content = bos.toString("UTF-8");
                sourceEditor.setText(content);
                currentFileUri = uri;
                currentFilePath = uri.getPath();
                statusText.setText("Opened: " + uri.getLastPathSegment());
            }
        } catch (Exception e) {
            statusText.setText("Error opening: " + e.getMessage());
        }
    }

    private void compileAndSave(Uri uri, String format) {
        String source = sourceEditor.getText().toString();
        if (source.isEmpty()) {
            statusText.setText("Error: Source code is empty");
            return;
        }

        statusText.setText("Compiling...");

        new Thread(() -> {
            try {
                long resultPtr;
                if ("html".equals(format)) {
                    resultPtr = compileToHtml(source, null);
                } else {
                    resultPtr = compileToPdf(source, null);
                }

                if (resultPtr != 0) {
                    byte[] data = getCompileResultData(resultPtr);
                    int len = getCompileResultLen(resultPtr);
                    freeCompileResult(resultPtr);

                    if (data != null && len > 0) {
                        OutputStream os = getContentResolver().openOutputStream(uri);
                        if (os != null) {
                            os.write(data);
                            os.close();
                            final int size = len;
                            runOnUiThread(() -> {
                                statusText.setText("Exported to: " + uri.getLastPathSegment() + "\nSize: " + size + " bytes");
                            });
                        }
                    } else {
                        String error = getLastError();
                        if (error != null && !error.isEmpty()) {
                            runOnUiThread(() -> statusText.setText("Compilation error:\n" + error));
                        } else {
                            runOnUiThread(() -> statusText.setText("Compilation failed: no data"));
                        }
                    }
                } else {
                    String error = getLastError();
                    if (error != null && !error.isEmpty()) {
                        runOnUiThread(() -> statusText.setText("Compilation error:\n" + error));
                    } else {
                        runOnUiThread(() -> statusText.setText("Compilation failed"));
                    }
                }
            } catch (Exception e) {
                runOnUiThread(() -> statusText.setText("Error: " + e.getMessage()));
            }
        }).start();
    }
}
