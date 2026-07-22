package com.typstwriter;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;

public class MainActivity extends Activity {

    static {
        System.loadLibrary("typst_core");
    }

    private static native long compileToPdf(String source, String fontPath);
    private static native long compileToHtml(String source, String fontPath);
    private static native int getCompileResultLen(long resultPtr);
    private static native int getCompileResultFormat(long resultPtr);
    private static native byte[] getCompileResultData(long resultPtr);
    private static native void freeCompileResult(long resultPtr);
    private static native String getLastError();
    private static native String getWarnings();

    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final int SAVE_FILE_REQUEST = 101;
    private static final int OPEN_FILE_REQUEST = 102;
    private static final int EXPORT_FILE_REQUEST = 103;

    private static final String PREFS = "TypstWriterPrefs";
    private static final String PREF_FONT_SIZE = "editor_font_size";
    private static final String PREF_RECENT_COUNT = "recent_count";
    private static final int DEFAULT_FONT_SIZE = 14;
    private static final int MIN_FONT_SIZE = 8;
    private static final int MAX_FONT_SIZE = 40;
    private static final int MAX_RECENT = 10;

    private LinearLayout rootLayout;
    private View welcomeView;
    private View editorView;
    private LinearLayout recentFilesContainer;
    private EditText sourceEditor;
    private EditText fontSizeInput;
    private TextView statusText;
    private TextView warningsText;
    private Uri currentFileUri = null;
    private String currentFileName = "Untitled";
    private String originalStoredName = null;
    private EditText fileNameInput;
    private String exportFormat = "pdf";
    private int currentFontSize = DEFAULT_FONT_SIZE;

    // ── Data class for recent files ──

    static class RecentFile {
        String uri;
        String name;
        long time;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestStoragePermissions();

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        currentFontSize = prefs.getInt(PREF_FONT_SIZE, DEFAULT_FONT_SIZE);

        // Миграция со старого формата
        migrateOldRecent(prefs);

        rootLayout = new LinearLayout(this);
        rootLayout.setOrientation(LinearLayout.VERTICAL);

        welcomeView = createWelcomeView();
        editorView = createEditorView();

        rootLayout.addView(welcomeView);
        rootLayout.addView(editorView);

        setContentView(rootLayout);
        showWelcome();
    }

    // ════════════════════════════════════════════
    // Welcome screen
    // ════════════════════════════════════════════

    private View createWelcomeView() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 48, 48, 48);
        layout.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView title = new TextView(this);
        title.setText("Typst Writer");
        title.setTextSize(28);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        titleParams.bottomMargin = 40;
        layout.addView(title, titleParams);

        Button newBtn = makeWideButton("New file");
        newBtn.setOnClickListener(v -> {
            newFile();
            showEditor();
        });
        layout.addView(newBtn);

        Button openBtn = makeWideButton("Open file");
        openBtn.setOnClickListener(v -> openFile());
        layout.addView(openBtn);

        TextView recentLabel = new TextView(this);
        recentLabel.setText("Recent files");
        recentLabel.setTextSize(16);
        recentLabel.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        labelParams.topMargin = 32;
        labelParams.bottomMargin = 8;
        layout.addView(recentLabel, labelParams);

        recentFilesContainer = new LinearLayout(this);
        recentFilesContainer.setOrientation(LinearLayout.VERTICAL);
        layout.addView(recentFilesContainer);

        return layout;
    }

    private Button makeWideButton(String text) {
        Button btn = new Button(this);
        btn.setText(text);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.bottomMargin = 8;
        btn.setLayoutParams(p);
        return btn;
    }

    private void refreshRecentList() {
        recentFilesContainer.removeAllViews();
        try {
            ArrayList<RecentFile> files = getRecentFiles();

            if (files.isEmpty()) {
                TextView empty = new TextView(this);
                empty.setText("No recent files");
                empty.setTextSize(14);
                empty.setAlpha(0.5f);
                recentFilesContainer.addView(empty);
                return;
            }

            for (final RecentFile f : files) {
                Button btn = new Button(this);
                btn.setText(f.name);
                btn.setTextSize(12);
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                p.bottomMargin = 4;
                btn.setLayoutParams(p);
                btn.setOnClickListener(v -> openRecentFile(f.uri));
                recentFilesContainer.addView(btn);
            }
        } catch (Exception e) {
            clearAllRecent();
            TextView empty = new TextView(this);
            empty.setText("No recent files");
            empty.setTextSize(14);
            empty.setAlpha(0.5f);
            recentFilesContainer.addView(empty);
        }
    }

    private void showWelcome() {
        welcomeView.setVisibility(View.VISIBLE);
        editorView.setVisibility(View.GONE);
        refreshRecentList();
    }

    private void showEditor() {
        welcomeView.setVisibility(View.GONE);
        editorView.setVisibility(View.VISIBLE);
        sourceEditor.requestFocus();
    }

    // ════════════════════════════════════════════
    // Editor screen — only Save, Save As, Export
    // ════════════════════════════════════════════

    private View createEditorView() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(16, 16, 16, 16);

        // Top bar: Home + filename input + .typ suffix
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);

        Button homeBtn = new Button(this);
        homeBtn.setText("Home");
        homeBtn.setTextSize(14);
        homeBtn.setOnClickListener(v -> showWelcome());
        topBar.addView(homeBtn);

        fileNameInput = new EditText(this);
        fileNameInput.setText(currentFileName);
        fileNameInput.setTextSize(16);
        fileNameInput.setSingleLine(true);
        fileNameInput.setMaxLines(1);
        fileNameInput.setSelectAllOnFocus(true);
        LinearLayout.LayoutParams fnParams = new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        fnParams.setMarginStart(8);
        fileNameInput.setLayoutParams(fnParams);
        topBar.addView(fileNameInput);

        TextView extLabel = new TextView(this);
        extLabel.setText(".typ");
        extLabel.setTextSize(16);
        extLabel.setPadding(4, 0, 0, 0);
        topBar.addView(extLabel);

        layout.addView(topBar);

        // Font size
        LinearLayout sizeRow = new LinearLayout(this);
        sizeRow.setOrientation(LinearLayout.HORIZONTAL);
        sizeRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView sizeLabel = new TextView(this);
        sizeLabel.setText("Size: ");
        sizeLabel.setTextSize(14);
        sizeRow.addView(sizeLabel);

        fontSizeInput = new EditText(this);
        fontSizeInput.setText(String.valueOf(currentFontSize));
        fontSizeInput.setTextSize(14);
        fontSizeInput.setInputType(EditorInfo.TYPE_CLASS_NUMBER);
        LinearLayout.LayoutParams siParams = new LinearLayout.LayoutParams(
            120, LinearLayout.LayoutParams.WRAP_CONTENT);
        fontSizeInput.setLayoutParams(siParams);
        fontSizeInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_GO) {
                applyFontSizeFromInput();
                return true;
            }
            return false;
        });
        fontSizeInput.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) applyFontSizeFromInput();
        });
        sizeRow.addView(fontSizeInput);

        TextView sizeHint = new TextView(this);
        sizeHint.setText("  (Ctrl+/Ctrl-)");
        sizeHint.setTextSize(12);
        sizeHint.setAlpha(0.5f);
        sizeRow.addView(sizeHint);

        LinearLayout.LayoutParams srParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        srParams.topMargin = 4;
        layout.addView(sizeRow, srParams);

        // Editor
        sourceEditor = new EditText(this);
        sourceEditor.setHint("Enter Typst code...");
        sourceEditor.setMinLines(10);
        sourceEditor.setGravity(Gravity.TOP | Gravity.LEFT);
        sourceEditor.setTextSize(TypedValue.COMPLEX_UNIT_SP, currentFontSize);
        sourceEditor.setTypeface(Typeface.MONOSPACE);
        LinearLayout.LayoutParams edParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f);
        edParams.topMargin = 4;
        layout.addView(sourceEditor, edParams);

        // Key listener: Tab, Ctrl+S, Ctrl+/-
        sourceEditor.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;

            if (keyCode == KeyEvent.KEYCODE_TAB) {
                int start = sourceEditor.getSelectionStart();
                sourceEditor.getText().insert(start, "    ");
                return true;
            }

            boolean ctrl = (event.getMetaState() & KeyEvent.META_CTRL_ON) != 0;
            if (ctrl) {
                if (keyCode == KeyEvent.KEYCODE_S) {
                    saveFile();
                    return true;
                }
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

        // Save buttons
        LinearLayout saveRow = new LinearLayout(this);
        saveRow.setOrientation(LinearLayout.HORIZONTAL);
        saveRow.setGravity(Gravity.CENTER);
        saveRow.addView(makeSmallButton("Save", v -> saveFile()));
        saveRow.addView(makeSmallButton("Save As", v -> saveFileAs()));
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        saveParams.topMargin = 8;
        layout.addView(saveRow, saveParams);

        // Export buttons
        LinearLayout exportRow = new LinearLayout(this);
        exportRow.setOrientation(LinearLayout.HORIZONTAL);
        exportRow.setGravity(Gravity.CENTER);
        exportRow.addView(makeSmallButton("Export PDF", v -> exportTypst("pdf")));
        exportRow.addView(makeSmallButton("Export HTML", v -> exportTypst("html")));
        LinearLayout.LayoutParams expParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        expParams.topMargin = 4;
        layout.addView(exportRow, expParams);

        // Status
        statusText = new TextView(this);
        statusText.setText("Ready");
        statusText.setPadding(0, 8, 0, 0);
        layout.addView(statusText);

        // Warnings
        warningsText = new TextView(this);
        warningsText.setTextSize(12);
        warningsText.setTypeface(Typeface.MONOSPACE);
        warningsText.setPadding(0, 4, 0, 0);
        warningsText.setVisibility(View.GONE);
        layout.addView(warningsText);

        return layout;
    }

    private Button makeSmallButton(String text, View.OnClickListener listener) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextSize(12);
        btn.setOnClickListener(listener);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMargins(4, 0, 4, 0);
        btn.setLayoutParams(p);
        return btn;
    }

    // ════════════════════════════════════════════
    // Font size
    // ════════════════════════════════════════════

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
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit().putInt(PREF_FONT_SIZE, currentFontSize).apply();
    }

    // ════════════════════════════════════════════
    // Display name helper
    // ════════════════════════════════════════════

    private String getDisplayName(Uri uri) {
        Cursor cursor = getContentResolver().query(uri, null, null, null, null);
        if (cursor != null) {
            try {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (nameIndex >= 0 && cursor.moveToFirst()) {
                    String name = cursor.getString(nameIndex);
                    if (name != null && !name.isEmpty()) return name;
                }
            } finally {
                cursor.close();
            }
        }
        try {
            String docId = DocumentsContract.getDocumentId(uri);
            if (docId != null && docId.contains(":")) {
                String path = docId.substring(docId.lastIndexOf(':') + 1);
                if (path.contains("/")) {
                    return path.substring(path.lastIndexOf('/') + 1);
                }
                return path;
            }
            if (docId != null && docId.contains("/")) {
                return docId.substring(docId.lastIndexOf('/') + 1);
            }
        } catch (Exception ignored) {}
        String last = uri.getLastPathSegment();
        return last != null ? last : "Untitled";
    }

    private String getFilePath(Uri uri) {
        try {
            String docId = DocumentsContract.getDocumentId(uri);
            if (docId != null && docId.contains(":")) {
                String path = docId.substring(docId.lastIndexOf(':') + 1);
                if (path != null && !path.isEmpty()) return path;
            }
        } catch (Exception ignored) {}
        return getDisplayName(uri);
    }

    private String getFileNameFromUri(Uri uri) {
        String full = getDisplayName(uri);
        if (full != null && full.endsWith(".typ")) {
            return full.substring(0, full.length() - 4);
        }
        return full != null ? full : "Untitled";
    }

    private void updateFileNameFromUri(Uri uri) {
        currentFileName = getFileNameFromUri(uri);
        originalStoredName = currentFileName + ".typ";
        fileNameInput.setText(currentFileName);
    }

    // ════════════════════════════════════════════
    // Recent files — individual SharedPreferences keys
    // ════════════════════════════════════════════

    private ArrayList<RecentFile> getRecentFiles() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        int count = prefs.getInt(PREF_RECENT_COUNT, 0);
        ArrayList<RecentFile> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String uri = prefs.getString("recent_" + i + "_uri", null);
            String name = prefs.getString("recent_" + i + "_name", null);
            long time = prefs.getLong("recent_" + i + "_time", 0);
            if (uri != null && name != null) {
                RecentFile f = new RecentFile();
                f.uri = uri;
                f.name = name;
                f.time = time;
                list.add(f);
            }
        }
        Collections.sort(list, (a, b) -> Long.compare(b.time, a.time));
        return list;
    }

    private void saveRecentFiles(ArrayList<RecentFile> list) {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        // Очищаем старые ключи
        int oldCount = prefs.getInt(PREF_RECENT_COUNT, 0);
        for (int i = 0; i < oldCount; i++) {
            editor.remove("recent_" + i + "_uri");
            editor.remove("recent_" + i + "_name");
            editor.remove("recent_" + i + "_time");
        }
        // Записываем новые
        editor.putInt(PREF_RECENT_COUNT, list.size());
        for (int i = 0; i < list.size(); i++) {
            RecentFile f = list.get(i);
            editor.putString("recent_" + i + "_uri", f.uri);
            editor.putString("recent_" + i + "_name", f.name);
            editor.putLong("recent_" + i + "_time", f.time);
        }
        editor.apply();
    }

    private void addRecent(String uriStr, String name) {
        try {
            if (uriStr == null || uriStr.isEmpty()) return;
            if (name == null) name = "Untitled";
            ArrayList<RecentFile> list = getRecentFiles();
            // Удаляем дубликат
            for (int i = list.size() - 1; i >= 0; i--) {
                if (uriStr.equals(list.get(i).uri)) {
                    list.remove(i);
                }
            }
            // Добавляем в начало с текущим временем
            RecentFile f = new RecentFile();
            f.uri = uriStr;
            f.name = name;
            f.time = System.currentTimeMillis();
            list.add(0, f);
            // Ограничиваем размер
            while (list.size() > MAX_RECENT) {
                list.remove(list.size() - 1);
            }
            saveRecentFiles(list);
        } catch (Exception ignored) {}
    }

    private void clearAllRecent() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        int count = prefs.getInt(PREF_RECENT_COUNT, 0);
        for (int i = 0; i < count; i++) {
            editor.remove("recent_" + i + "_uri");
            editor.remove("recent_" + i + "_name");
            editor.remove("recent_" + i + "_time");
        }
        editor.putInt(PREF_RECENT_COUNT, 0);
        editor.apply();
    }

    private void migrateOldRecent(SharedPreferences prefs) {
        // Удаляем старые ключи форматов v1/v2
        if (prefs.contains("recent_files") || prefs.contains("recent_files_v2")
            || prefs.contains("recent_uris") || prefs.contains("recent_names")) {
            prefs.edit()
                .remove("recent_files")
                .remove("recent_files_v2")
                .remove("recent_uris")
                .remove("recent_names")
                .apply();
        }
    }

    private void openRecentFile(String uriStr) {
        try {
            if (uriStr == null || uriStr.isEmpty()) return;
            Uri uri = Uri.parse(uriStr);
            if (uri == null) return;
            try {
                getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {}
            InputStream is = getContentResolver().openInputStream(uri);
            if (is != null) {
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = is.read(buf)) != -1) {
                    bos.write(buf, 0, n);
                }
                is.close();
                sourceEditor.setText(bos.toString("UTF-8"));
                currentFileUri = uri;
                updateFileNameFromUri(uri);
                statusText.setText("Opened: " + getDisplayName(uri));
                // Обновляем время открытия
                addRecent(uri.toString(), getFilePath(uri));
                showEditor();
            }
        } catch (Exception e) {
            statusText.setText("Error: " + e.getMessage());
        }
    }

    // ════════════════════════════════════════════
    // Permissions
    // ════════════════════════════════════════════

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

    // ════════════════════════════════════════════
    // File operations
    // ════════════════════════════════════════════

    private void newFile() {
        sourceEditor.setText("");
        currentFileUri = null;
        currentFileName = "Untitled";
        originalStoredName = null;
        fileNameInput.setText("Untitled");
        statusText.setText("New file created");
        warningsText.setVisibility(View.GONE);
    }

    private void saveFile() {
        String newName = fileNameInput.getText().toString().trim();
        if (newName.isEmpty()) newName = "Untitled";
        fileNameInput.setText(newName);
        currentFileName = newName;
        if (currentFileUri != null) {
            String desiredName = currentFileName + ".typ";
            String currentStoredName = getDisplayName(currentFileUri);
            if (!desiredName.equals(currentStoredName)) {
                Uri renamed = renameWithConflictHandling(currentFileUri, desiredName);
                if (renamed != null) {
                    currentFileUri = renamed;
                    originalStoredName = desiredName;
                }
            }
            writeToFile(currentFileUri);
            updateFileNameFromUri(currentFileUri);
            statusText.setText("Saved: " + getDisplayName(currentFileUri));
            addRecent(currentFileUri.toString(), getFilePath(currentFileUri));
        } else {
            saveFileAs();
        }
    }

    private void saveFileAs() {
        currentFileName = fileNameInput.getText().toString().trim();
        if (currentFileName.isEmpty()) currentFileName = "Untitled";
        fileNameInput.setText(currentFileName);
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/octet-stream");
        intent.putExtra(Intent.EXTRA_TITLE, currentFileName + ".typ");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, SAVE_FILE_REQUEST);
    }

    private void openFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"text/plain", "application/octet-stream"});
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
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
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            if (requestCode == SAVE_FILE_REQUEST) {
                String intendedName = currentFileName + ".typ";
                writeToFile(uri);
                Uri renamed = renameWithConflictHandling(currentFileUri, intendedName);
                if (renamed != null && !renamed.equals(currentFileUri)) {
                    currentFileUri = renamed;
                }
                updateFileNameFromUri(currentFileUri);
                statusText.setText("Saved: " + getDisplayName(currentFileUri));
                addRecent(currentFileUri.toString(), getFilePath(currentFileUri));
                showEditor();
            } else if (requestCode == OPEN_FILE_REQUEST) {
                try {
                    getContentResolver().takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Exception ignored) {}
                readFromFile(uri);
                updateFileNameFromUri(uri);
                addRecent(uri.toString(), getFilePath(uri));
                showEditor();
            } else if (requestCode == EXPORT_FILE_REQUEST) {
                compileAndSave(uri, exportFormat);
            }
        } catch (Exception e) {
            statusText.setText("Error: " + e.getMessage());
            showEditor();
        }
    }

    private Uri renameWithConflictHandling(Uri uri, String desiredName) {
        try {
            String docId = DocumentsContract.getDocumentId(uri);
            if (docId == null || !docId.contains("/")) {
                // Для числовых ID без пути — пробуем прямой rename
                return DocumentsContract.renameDocument(getContentResolver(), uri, desiredName);
            }
            // Получаем родительский путь
            String parentId = docId.substring(0, docId.lastIndexOf('/'));
            Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(uri, parentId);
            java.util.HashSet<String> existingNames = new java.util.HashSet<>();
            Cursor c = getContentResolver().query(childrenUri,
                new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null);
            if (c != null) {
                try {
                    while (c.moveToNext()) {
                        String cn = c.getString(0);
                        if (cn != null) existingNames.add(cn);
                    }
                } finally {
                    c.close();
                }
            }
            // Ищем уникальное имя
            String finalName = desiredName;
            if (existingNames.contains(desiredName)) {
                int counter = 1;
                String baseName = desiredName.endsWith(".typ")
                    ? desiredName.substring(0, desiredName.length() - 4) : desiredName;
                do {
                    finalName = baseName + " (" + counter + ").typ";
                    counter++;
                } while (existingNames.contains(finalName));
            }
            return DocumentsContract.renameDocument(getContentResolver(), uri, finalName);
        } catch (Exception ignored) {}
        return uri;
    }

    private void writeToFile(Uri uri) {
        try {
            OutputStream os = getContentResolver().openOutputStream(uri, "wt");
            if (os != null) {
                os.write(sourceEditor.getText().toString().getBytes("UTF-8"));
                os.flush();
                os.close();
                currentFileUri = uri;
                try {
                    getContentResolver().takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                } catch (Exception ignored) {}
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
                sourceEditor.setText(bos.toString("UTF-8"));
                currentFileUri = uri;
                statusText.setText("Opened: " + getDisplayName(uri));
            } else {
                statusText.setText("Error: could not open file");
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
        warningsText.setVisibility(View.GONE);

        new Thread(() -> {
            try {
                long resultPtr;
                if ("html".equals(format)) {
                    resultPtr = compileToHtml(source, null);
                } else {
                    resultPtr = compileToPdf(source, null);
                }

                String warnings = getWarnings();

                if (resultPtr != 0) {
                    byte[] data = getCompileResultData(resultPtr);
                    int len = getCompileResultLen(resultPtr);
                    freeCompileResult(resultPtr);

                    if (data != null && len > 0) {
                        OutputStream os = getContentResolver().openOutputStream(uri, "wt");
                        if (os != null) {
                            os.write(data);
                            os.close();
                            final String warnMsg = (warnings != null && !warnings.isEmpty()) ? warnings : null;
                            final int size = len;
                            runOnUiThread(() -> {
                                statusText.setText("Exported: " + getDisplayName(uri) + " (" + size + " bytes)");
                                if (warnMsg != null) {
                                    warningsText.setText(warnMsg);
                                    warningsText.setVisibility(View.VISIBLE);
                                }
                            });
                        }
                    } else {
                        String error = getLastError();
                        String msg = (error != null && !error.isEmpty()) ? error : "Compilation failed: no data";
                        runOnUiThread(() -> statusText.setText(msg));
                    }
                } else {
                    String error = getLastError();
                    String msg = (error != null && !error.isEmpty()) ? error : "Compilation failed";
                    runOnUiThread(() -> statusText.setText(msg));
                }
            } catch (Exception e) {
                runOnUiThread(() -> statusText.setText("Error: " + e.getMessage()));
            }
        }).start();
    }
}
