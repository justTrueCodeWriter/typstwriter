package com.typstwriter;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
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
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;

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

    private static final String PREFS_NAME = "TypstWriterPrefs";
    private static final String PREF_FONT_SIZE = "editor_font_size";
    private static final String PREF_RECENT = "recent_files";
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
    private String exportFormat = "pdf";
    private int currentFontSize = DEFAULT_FONT_SIZE;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestStoragePermissions();

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        currentFontSize = prefs.getInt(PREF_FONT_SIZE, DEFAULT_FONT_SIZE);

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
        ArrayList<String> uris = getRecentUris();
        ArrayList<String> names = getRecentNames();

        if (uris.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("No recent files");
            empty.setTextSize(14);
            empty.setAlpha(0.5f);
            recentFilesContainer.addView(empty);
            return;
        }

        for (int i = 0; i < uris.size(); i++) {
            final String uriStr = uris.get(i);
            Button btn = new Button(this);
            btn.setText(names.get(i));
            btn.setTextSize(14);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            p.bottomMargin = 4;
            btn.setLayoutParams(p);
            btn.setOnClickListener(v -> openRecentFile(uriStr));
            recentFilesContainer.addView(btn);
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
    // Editor screen
    // ════════════════════════════════════════════

    private View createEditorView() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(16, 16, 16, 16);

        // Top bar: Home + title
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);

        Button homeBtn = new Button(this);
        homeBtn.setText("Home");
        homeBtn.setTextSize(14);
        homeBtn.setOnClickListener(v -> showWelcome());
        topBar.addView(homeBtn);

        TextView title = new TextView(this);
        title.setText("  Typst Writer");
        title.setTextSize(18);
        topBar.addView(title);

        layout.addView(topBar);

        // Font size row
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

        // File buttons
        LinearLayout fileRow = new LinearLayout(this);
        fileRow.setOrientation(LinearLayout.HORIZONTAL);
        fileRow.setGravity(Gravity.CENTER);

        fileRow.addView(makeSmallButton("New", v -> newFile()));
        fileRow.addView(makeSmallButton("Open", v -> openFile()));
        fileRow.addView(makeSmallButton("Save", v -> saveFile()));
        fileRow.addView(makeSmallButton("Save As", v -> saveFileAs()));

        LinearLayout.LayoutParams frParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        frParams.topMargin = 8;
        layout.addView(fileRow, frParams);

        // Export buttons
        LinearLayout exportRow = new LinearLayout(this);
        exportRow.setOrientation(LinearLayout.HORIZONTAL);
        exportRow.setGravity(Gravity.CENTER);

        exportRow.addView(makeSmallButton("Export PDF", v -> exportTypst("pdf")));
        exportRow.addView(makeSmallButton("Export HTML", v -> exportTypst("html")));

        LinearLayout.LayoutParams erParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        erParams.topMargin = 4;
        layout.addView(exportRow, erParams);

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

    private Button makeSmallButton(String text, android.view.View.OnClickListener listener) {
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
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit().putInt(PREF_FONT_SIZE, currentFontSize).apply();
    }

    // ════════════════════════════════════════════
    // Recent files — stored as "uri1\nname1\nuri2\nname2\n..."
    // ════════════════════════════════════════════

    private static final String RECENT_SEP = "\n";

    private String getRecentRaw() {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(PREF_RECENT, "");
    }

    private void saveRecentRaw(String raw) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString(PREF_RECENT, raw).apply();
    }

    private ArrayList<String> getRecentUris() {
        ArrayList<String> result = new ArrayList<>();
        String raw = getRecentRaw();
        if (raw.isEmpty()) return result;
        String[] parts = raw.split(RECENT_SEP, -1);
        for (int i = 0; i + 1 < parts.length; i += 2) {
            result.add(parts[i]);
        }
        return result;
    }

    private ArrayList<String> getRecentNames() {
        ArrayList<String> result = new ArrayList<>();
        String raw = getRecentRaw();
        if (raw.isEmpty()) return result;
        String[] parts = raw.split(RECENT_SEP, -1);
        for (int i = 1; i + 1 < parts.length; i += 2) {
            result.add(parts[i]);
        }
        return result;
    }

    private void addRecent(String uriStr, String name) {
        ArrayList<String> uris = getRecentUris();
        ArrayList<String> names = getRecentNames();
        int idx = uris.indexOf(uriStr);
        if (idx >= 0) {
            uris.remove(idx);
            if (idx < names.size()) names.remove(idx);
        }
        uris.add(0, uriStr);
        names.add(0, name);
        while (uris.size() > MAX_RECENT) {
            uris.remove(uris.size() - 1);
            names.remove(names.size() - 1);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < uris.size(); i++) {
            if (i > 0) sb.append(RECENT_SEP);
            sb.append(uris.get(i)).append(RECENT_SEP).append(names.get(i));
        }
        saveRecentRaw(sb.toString());
    }

    private void removeRecent(String uriStr) {
        ArrayList<String> uris = getRecentUris();
        ArrayList<String> names = getRecentNames();
        int idx = uris.indexOf(uriStr);
        if (idx >= 0) {
            uris.remove(idx);
            if (idx < names.size()) names.remove(idx);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < uris.size(); i++) {
            if (i > 0) sb.append(RECENT_SEP);
            sb.append(uris.get(i)).append(RECENT_SEP).append(names.get(i));
        }
        saveRecentRaw(sb.toString());
    }

    private void openRecentFile(String uriStr) {
        try {
            Uri uri = Uri.parse(uriStr);
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
                statusText.setText("Opened: " + uri.getLastPathSegment());
                showEditor();
            } else {
                removeRecent(uriStr);
                showWelcome();
            }
        } catch (Exception e) {
            // URI невалиден (разрешения SAF потеряны) — удаляем из недавних
            removeRecent(uriStr);
            statusText.setText("File no longer accessible, removed from recent");
            showWelcome();
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
        statusText.setText("New file created");
        warningsText.setVisibility(View.GONE);
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
                showEditor();
            } else if (requestCode == EXPORT_FILE_REQUEST) {
                compileAndSave(uri, exportFormat);
            }
        }
    }

    private void writeToFile(Uri uri) {
        try {
            OutputStream os = getContentResolver().openOutputStream(uri);
            if (os != null) {
                os.write(sourceEditor.getText().toString().getBytes("UTF-8"));
                os.close();
                currentFileUri = uri;
                statusText.setText("Saved: " + uri.getLastPathSegment());
                addRecent(uri.toString(), uri.getLastPathSegment());
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
                statusText.setText("Opened: " + uri.getLastPathSegment());
                addRecent(uri.toString(), uri.getLastPathSegment());
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
                        OutputStream os = getContentResolver().openOutputStream(uri);
                        if (os != null) {
                            os.write(data);
                            os.close();
                            final String warnMsg = (warnings != null && !warnings.isEmpty()) ? warnings : null;
                            final int size = len;
                            runOnUiThread(() -> {
                                statusText.setText("Exported: " + uri.getLastPathSegment() + " (" + size + " bytes)");
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
