package com.liskovsoft.smartyoutubetv2.common.diag;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.util.TypedValue;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

/**
 * KIDS DIAGNOSTIC BUILD v2 — runs in a SEPARATE process (":diag", see manifest)
 * so it can draw even when the main process is dead or its main thread is blocked.
 *
 * Shows: the crash message (intent extra) and/or the breadcrumb + crash files.
 * The user photographs this screen — no adb needed.
 *
 * Temporary class; remove once the black screen is fixed.
 */
public class DiagnosticActivity extends Activity {
    public static final String EXTRA_MESSAGE = "diag_message";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String message = getIntent() != null ? getIntent().getStringExtra(EXTRA_MESSAGE) : null;

        StringBuilder sb = new StringBuilder();
        sb.append("=== SMARTTUBE KIDS DIAGNOSTIC v2 ===\n\n");

        if (message != null && !message.isEmpty()) {
            sb.append(message).append("\n\n");
        }

        // Also read the persisted files (they survive main-process death)
        String crash = readFile(DiagnosticProvider.CRASH_FILE);
        if (crash != null && !crash.isEmpty() && (message == null || !message.contains("FATAL EXCEPTION"))) {
            sb.append("--- CRASH FILE ---\n").append(crash).append("\n");
        }

        String crumbs = readFile(DiagnosticProvider.BREADCRUMB_FILE);
        if (crumbs != null && !crumbs.isEmpty()) {
            sb.append("--- BREADCRUMBS ---\n").append(crumbs).append("\n");
        }

        sb.append("=== END (photograph this screen) ===");

        TextView tv = new TextView(this);
        tv.setText(sb.toString());
        tv.setTextColor(Color.YELLOW);
        tv.setBackgroundColor(Color.BLACK);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setPadding(24, 24, 24, 24);
        tv.setMovementMethod(new ScrollingMovementMethod());

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.BLACK);
        scroll.addView(tv);

        setContentView(scroll);
    }

    private String readFile(String name) {
        try {
            File f = new File(getExternalFilesDir(null), name);
            if (!f.exists()) return null;
            BufferedReader reader = new BufferedReader(new FileReader(f));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            reader.close();
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }
}
