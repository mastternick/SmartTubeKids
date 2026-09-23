package com.liskovsoft.smartyoutubetv2.common.diag;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.util.TypedValue;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * KIDS DIAGNOSTIC BUILD — full-screen scrollable text with the crash/trace.
 * The user photographs this screen instead of using adb logcat.
 *
 * Temporary class; remove once the black screen is fixed.
 */
public class DiagnosticActivity extends Activity {
    public static final String EXTRA_MESSAGE = "diag_message";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String message = getIntent() != null ? getIntent().getStringExtra(EXTRA_MESSAGE) : null;

        String body;
        if (message != null && !message.isEmpty()) {
            body = message;
        } else {
            body = "No message. Breadcrumbs:\n" + DiagnosticProvider.dump();
        }

        TextView tv = new TextView(this);
        tv.setText("=== SMARTTUBE KIDS DIAGNOSTIC ===\n\n" + body + "\n\n=== END === (photograph this screen)");
        tv.setTextColor(Color.YELLOW);
        tv.setBackgroundColor(Color.BLACK);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setPadding(24, 24, 24, 24);
        tv.setMovementMethod(new ScrollingMovementMethod());

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.BLACK);
        scroll.addView(tv);

        setContentView(scroll);
    }
}
