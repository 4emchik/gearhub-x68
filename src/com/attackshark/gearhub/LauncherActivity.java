package com.attackshark.gearhub;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Launcher entry point for the combined GearHub app.
 *
 * If exactly one supported device is attached, the matching configurator is
 * opened immediately.  When both or neither are present, a minimal chooser is
 * shown (the buttons still work without hardware, which is useful for
 * inspecting the pages).
 */
public class LauncherActivity extends Activity {

    private static final int KEYBOARD_VID = 12625;  // 0x3151
    private static final int KEYBOARD_PID = 20525;  // 0x502D

    private UsbManager usbManager;
    private TextView statusView;
    private boolean usbReceiverRegistered = false;
    private boolean waitingForDevice = false;
    private boolean launching = false;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                handler.postDelayed(LauncherActivity.this::refresh, 700L);
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                handler.postDelayed(LauncherActivity.this::refresh, 200L);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        buildUi();
        registerUsbReceiver();
        enterFullscreen();

        if (!routeIfSingle()) {
            refresh();
        }
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.WHITE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(28), dp(24), dp(28), dp(24));
        root.setBackgroundColor(Color.WHITE);
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.mipmap.ic_launcher);
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        root.addView(icon, centered(dp(92), dp(92)));

        TextView title = new TextView(this);
        title.setText("GearHub");
        title.setTextColor(Color.BLACK);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        root.addView(title, topMargin(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 6));

        TextView subtitle = new TextView(this);
        subtitle.setText("Attack Shark X68 HE  •  X6");
        subtitle.setTextColor(0xFF666666);
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        subtitle.setGravity(Gravity.CENTER);
        root.addView(subtitle, topMargin(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 2));

        statusView = new TextView(this);
        statusView.setTextColor(Color.BLACK);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        statusView.setGravity(Gravity.CENTER);
        root.addView(statusView, topMargin(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 20));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.topMargin = dp(20);
        root.addView(buttons, rowParams);

        Button keyboard = makeButton("X68 HE keyboard");
        keyboard.setOnClickListener(v -> launch(MainActivity.class));
        LinearLayout.LayoutParams keyboardParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        keyboardParams.rightMargin = dp(6);
        buttons.addView(keyboard, keyboardParams);

        Button mouse = makeButton("X6 mouse");
        mouse.setOnClickListener(v -> launch(MouseActivity.class));
        LinearLayout.LayoutParams mouseParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        mouseParams.leftMargin = dp(6);
        buttons.addView(mouse, mouseParams);

        TextView note = new TextView(this);
        note.setText("Devices are detected automatically.");
        note.setTextColor(0xFF777777);
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        note.setGravity(Gravity.CENTER);
        root.addView(note, topMargin(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 14));

        setContentView(scroll);
    }

    private Button makeButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextColor(Color.WHITE);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        button.setMinHeight(dp(52));
        button.setPadding(dp(18), dp(12), dp(18), dp(12));

        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.BLACK);
        background.setCornerRadius(dp(14));
        button.setBackground(background);
        return button;
    }

    private void registerUsbReceiver() {
        try {
            IntentFilter filter = new IntentFilter();
            filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
            filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(usbReceiver, filter);
            }
            usbReceiverRegistered = true;
        } catch (Exception ignored) {
        }
    }

    private boolean routeIfSingle() {
        UsbDevice keyboard = findKeyboard();
        UsbDevice mouse = X6Bridge.findDevice(usbManager);
        if (keyboard != null && mouse == null) {
            launch(MainActivity.class);
            return true;
        }
        if (mouse != null && keyboard == null) {
            launch(MouseActivity.class);
            return true;
        }
        waitingForDevice = keyboard == null && mouse == null;
        return false;
    }

    private void refresh() {
        if (launching || isFinishing()) return;
        UsbDevice keyboard = findKeyboard();
        UsbDevice mouse = X6Bridge.findDevice(usbManager);

        if (keyboard != null && mouse != null) {
            statusView.setText("Both devices detected — choose one.");
            waitingForDevice = false;
        } else if (keyboard != null) {
            statusView.setText("X68 HE keyboard detected.");
            if (waitingForDevice) {
                waitingForDevice = false;
                launch(MainActivity.class);
            }
        } else if (mouse != null) {
            statusView.setText("X6 receiver detected.");
            if (waitingForDevice) {
                waitingForDevice = false;
                launch(MouseActivity.class);
            }
        } else {
            statusView.setText("Plug in the X68 HE keyboard or the X6 receiver.");
            waitingForDevice = true;
        }
    }

    private UsbDevice findKeyboard() {
        if (usbManager == null) return null;
        try {
            for (UsbDevice d : usbManager.getDeviceList().values()) {
                if (d.getVendorId() == KEYBOARD_VID && d.getProductId() == KEYBOARD_PID) {
                    return d;
                }
            }
            for (UsbDevice d : usbManager.getDeviceList().values()) {
                String name = d.getProductName();
                if (name != null && name.toLowerCase().contains("x68")) return d;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void launch(Class<?> target) {
        if (launching) return;
        launching = true;
        Intent intent = new Intent(this, target);
        startActivity(intent);
        finish();
        overridePendingTransition(0, 0);
    }

    private LinearLayout.LayoutParams topMargin(int width, int height, int marginDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height);
        params.topMargin = dp(marginDp);
        return params;
    }

    private LinearLayout.LayoutParams centered(int width, int height) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height);
        params.gravity = Gravity.CENTER_HORIZONTAL;
        return params;
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics()));
    }

    @SuppressWarnings("deprecation")
    private void enterFullscreen() {
        try {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            if (Build.VERSION.SDK_INT >= 28) {
                lp.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            }
            getWindow().setAttributes(lp);
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) enterFullscreen();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (usbReceiverRegistered) {
            try {
                unregisterReceiver(usbReceiver);
            } catch (Exception ignored) {
            }
            usbReceiverRegistered = false;
        }
        super.onDestroy();
    }
}
