package com.attackshark.gearhub;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Diagnostic screen shown after a crash.  Runs in a separate process and
 * displays the stored stack trace plus the startup breadcrumbs.
 */
public class CrashActivity extends Activity {

    static final String EXTRA_TRACE = "trace";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String trace = getIntent().getStringExtra(EXTRA_TRACE);
        if (trace == null) trace = GearHubApp.read(this, GearHubApp.CRASH_FILE);
        if (trace == null) trace = "(no trace recorded)";
        String startup = GearHubApp.read(this, GearHubApp.STARTUP_FILE);
        String full = "GearHub crashed.\n\n"
                + "Stack trace (also copied to clipboard):\n"
                + trace
                + "\n\nStartup breadcrumbs:\n"
                + (startup == null ? "(none)" : startup);

        copy(full);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        TextView text = new TextView(this);
        text.setText(full);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        text.setTextColor(Color.BLACK);
        text.setPadding(32, 32, 32, 16);
        text.setMovementMethod(new ScrollingMovementMethod());
        text.setTextIsSelectable(true);
        root.addView(text, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        Button copy = new Button(this);
        copy.setText("Copy trace again");
        copy.setAllCaps(false);
        copy.setOnClickListener(v -> copy(full));
        root.addView(copy, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        ((LinearLayout.LayoutParams) copy.getLayoutParams()).gravity = Gravity.CENTER_HORIZONTAL;

        setContentView(root);
    }

    private void copy(String text) {
        try {
            ClipboardManager clipboard =
                    (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("GearHub crash", text));
        } catch (Throwable ignored) {
        }
    }
}
