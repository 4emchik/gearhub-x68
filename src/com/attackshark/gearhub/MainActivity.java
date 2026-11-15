package com.attackshark.gearhub;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fullscreen WebView wrapper for the official qmk.top (GearHub) configurator.
 *
 * Android's WebView does not expose WebHID, so the page is served with a
 * small JavaScript shim injected into <head>. The shim talks to
 * WebHidBridge over the AndroidHID JavaScript interface, which uses the
 * Android USB Host API.
 *
 * There are no startup screens or choosers: the keyboard is detected and
 * connected automatically.  A hidden three-finger tap opens the diagnostic
 * log.  If only the X6 is attached, the app opens the mouse configurator;
 * when both are attached, a small draggable "X6" switch opens it.
 */
public class MainActivity extends Activity implements WebHidBridge.StatusSink {

    private static final String TAG = "GearHubX68";
    private static final String START_URL = "https://qmk.top/";
    private static final String DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) "
                    + "AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/131.0.0.0 Safari/537.36";

    /** How often the watchdog checks for the keyboard. */
    private static final long AUTO_CONNECT_TICK_MS = 1500L;
    /** Minimum delay between two connect attempts. */
    private static final long CONNECT_RETRY_MS = 3000L;
    /** Minimum delay between two Android USB permission prompts. */
    private static final long PERMISSION_RETRY_MS = 20000L;
    /** A permission request can block natively for 30s; never stack them. */
    private static final long REQUEST_TIMEOUT_MS = 35000L;

    private WebView webView;
    private WebHidBridge bridge;
    private String shimJs = "";
    private final StringBuilder logBuffer = new StringBuilder();
    private boolean usbReceiverRegistered = false;
    private UsbPermissionRequester permissionRequester;
    private UsbManager usbManager;
    private FrameLayout rootLayout;
    private TextView mouseSwitch;

    private boolean foreground = false;
    private boolean pageReady = false;
    private boolean reattachRequested = false;
    private boolean connectInFlight = false;
    private long connectStartedAt = 0L;
    private long lastConnectAttemptAt = 0L;
    private long lastPermissionPromptAt = 0L;
    private long lastThreeFingerAt = 0L;

    private final Handler autoConnectHandler = new Handler(Looper.getMainLooper());
    private final Runnable autoConnectTick = new Runnable() {
        @Override
        public void run() {
            try {
                autoConnectOnce();
            } catch (Exception e) {
                log("Auto-connect error: " + e.getMessage());
            }
            autoConnectHandler.postDelayed(this, AUTO_CONNECT_TICK_MS);
        }
    };

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            String action = intent.getAction();
            if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                log("USB device attached"
                        + (device != null ? ": " + device.getDeviceName() : ""));
                if (webView != null) {
                    webView.postDelayed(MainActivity.this::autoConnectOnce, 900);
                }
                if (permissionRequester != null) {
                    autoConnectHandler.postDelayed(
                            permissionRequester::ensurePermissions, 700L);
                }
                updateMouseSwitch();
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                log("USB device detached"
                        + (device != null ? ": " + device.getDeviceName() : ""));
                if (bridge != null && device != null) {
                    final UsbDevice detached = device;
                    new Thread(() -> bridge.onUsbDetached(detached), "usb-detach").start();
                }
                updateMouseSwitch();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        try {
            super.onCreate(savedInstanceState);
            buildUi(savedInstanceState);
        } catch (Throwable t) {
            reportStartupCrash(t);
        }
    }

    private void buildUi(Bundle savedInstanceState) {
        GearHubApp.mark(this, "MainActivity.onCreate");
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

        // qmk.top has no X6 profile.  When the mouse is the only device, open
        // its own configurator straight away instead of an empty qmk screen.
        if (routeToMouseIfOnlyDevice()) return;

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        shimJs = readAsset("webhid_shim.js");

        webView = new WebView(this);
        webView.setBackgroundColor(Color.WHITE);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUserAgentString(DESKTOP_UA);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        WebView.setWebContentsDebuggingEnabled(true);

        bridge = new WebHidBridge(this, webView, this);
        bridge.setConnectListener((success, message) -> autoConnectHandler.post(() -> {
            connectInFlight = false;
            if (success) {
                log("Auto-connect ready");
            } else {
                log("Auto-connect failed: " + message);
            }
        }));
        webView.addJavascriptInterface(bridge, "AndroidHID");

        try {
            IntentFilter usbFilter = new IntentFilter();
            usbFilter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
            usbFilter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(usbReceiver, usbFilter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(usbReceiver, usbFilter);
            }
            usbReceiverRegistered = true;
        } catch (Exception e) {
            log("USB hotplug receiver failed: " + e.getMessage());
        }

        // The whole window is the configurator.  The only overlay is a tiny,
        // draggable X6 switch, shown when both devices are attached because
        // qmk.top itself cannot list the mouse.
        rootLayout = new FrameLayout(this);
        rootLayout.addView(webView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        mouseSwitch = buildMouseSwitch();
        FrameLayout.LayoutParams switchParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.END);
        switchParams.rightMargin = dp(10);
        switchParams.bottomMargin = dp(10);
        rootLayout.addView(mouseSwitch, switchParams);
        setContentView(rootLayout);
        updateMouseSwitch();
        enterFullscreen();
        GearHubApp.mark(this, "content view ready");

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage message) {
                String line = "Site " + message.messageLevel() + ": " + message.message();
                Log.d("SiteConsole", line);
                appendLog(line);
                return true;
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                try {
                    if (request.isForMainFrame()
                            && "GET".equalsIgnoreCase(request.getMethod())
                            && isQmkTop(request.getUrl())) {
                        WebResourceResponse injected = injectShimIntoMainDocument(
                                request.getUrl().toString());
                        if (injected != null) return injected;
                    }
                } catch (Exception e) {
                    Log.w(TAG, "intercept failed: " + e.getMessage());
                }
                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                pageReady = false;
                // Fallback if the main-document interception did not happen.
                if (shimJs != null && !shimJs.isEmpty()) {
                    view.evaluateJavascript(shimJs, null);
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                view.evaluateJavascript(
                        "(function(){try{"
                                + "var m=document.querySelector('meta[name=\\\"viewport\\\"]');"
                                + "if(!m){m=document.createElement('meta');m.name='viewport';document.head.appendChild(m);}"
                                + "m.setAttribute('content','width=1280, initial-scale=0.35, minimum-scale=0.1, maximum-scale=4, user-scalable=yes');"
                                + "}catch(e){}})();", null);
                pageReady = true;
                reattachRequested = true;
                connectInFlight = false;
                autoConnectHandler.removeCallbacks(autoConnectTick);
                autoConnectHandler.postDelayed(autoConnectTick, 1200L);
                autoConnectHandler.postDelayed(MainActivity.this::autoConnectOnce, 4000L);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                Uri uri = Uri.parse(url);
                if (isQmkTop(uri)) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (Exception ignored) {
                }
                return true;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return shouldOverrideUrlLoading(view, request.getUrl().toString());
            }
        });

        webView.loadUrl(START_URL);
        GearHubApp.mark(this, "page load requested");

        appendLog("GearHub build 9 (versionName 1.8), qmk.top starting — auto-connect enabled");
        permissionRequester = new UsbPermissionRequester(this);
        permissionRequester.ensurePermissions();
        autoConnectHandler.postDelayed(autoConnectTick, AUTO_CONNECT_TICK_MS);
    }

    // ------------------------------------------------------------------
    // Device routing / X6 switch
    // ------------------------------------------------------------------

    /**
     * The X6 is not in qmk.top's device filters.  If it is the only supported
     * device on the bus, show its own configurator instead of qmk's empty
     * device list.  When both are attached qmk stays the primary screen and
     * the small X6 switch opens the mouse page.
     */
    private boolean routeToMouseIfOnlyDevice() {
        if (usbManager == null) return false;
        UsbDevice mouse = X6Bridge.findDevice(usbManager);
        if (mouse == null) return false;
        if (findKeyboard(usbManager) != null) return false;

        Log.d(TAG, "Only the X6 is attached; opening the mouse configurator");
        try {
            startActivity(new Intent(this, MouseActivity.class));
        } catch (Exception e) {
            Log.w(TAG, "mouse page failed to start: " + e.getMessage());
            return false;
        }
        finish();
        return true;
    }

    private static UsbDevice findKeyboard(UsbManager manager) {
        if (manager == null) return null;
        try {
            for (UsbDevice device : manager.getDeviceList().values()) {
                if (device.getVendorId() == 12625 && device.getProductId() == 20525) {
                    return device;
                }
            }
            for (UsbDevice device : manager.getDeviceList().values()) {
                try {
                    String name = device.getProductName();
                    if (name == null) continue;
                    String lower = name.toLowerCase();
                    if (lower.contains("x68") || lower.contains("attack")
                            || lower.contains("shark")) {
                        return device;
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** Shows the X6 switch only while a receiver or cable is attached. */
    private void updateMouseSwitch() {
        if (mouseSwitch == null) return;
        boolean attached = X6Bridge.findDevice(usbManager) != null;
        mouseSwitch.setVisibility(attached ? View.VISIBLE : View.GONE);
    }

    private TextView buildMouseSwitch() {
        TextView chip = new TextView(this);
        chip.setText("X6");
        chip.setTextColor(Color.WHITE);
        chip.setTextSize(12f);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(12), dp(6), dp(12), dp(6));
        chip.setContentDescription("Open X6 mouse settings");

        GradientDrawable background = new GradientDrawable();
        background.setColor(0xCC202124);
        background.setCornerRadius(dp(16));
        background.setStroke(dp(1), 0x66FFFFFF);
        chip.setBackground(background);
        chip.setAlpha(0.85f);
        chip.setVisibility(View.GONE);

        chip.setOnClickListener(v ->
                startActivity(new Intent(this, MouseActivity.class)));
        chip.setOnTouchListener(new View.OnTouchListener() {
            private float downX;
            private float downY;
            private int startRight;
            private int startBottom;
            private boolean dragging;

            @Override
            public boolean onTouch(View view, MotionEvent event) {
                FrameLayout.LayoutParams params =
                        (FrameLayout.LayoutParams) view.getLayoutParams();
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = event.getRawX();
                        downY = event.getRawY();
                        startRight = params.rightMargin;
                        startBottom = params.bottomMargin;
                        dragging = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - downX;
                        float dy = event.getRawY() - downY;
                        if (!dragging && Math.abs(dx) < dp(6) && Math.abs(dy) < dp(6)) {
                            return true;
                        }
                        dragging = true;
                        int maxRight = Math.max(dp(4),
                                rootLayout.getWidth() - view.getWidth() - dp(4));
                        int maxBottom = Math.max(dp(4),
                                rootLayout.getHeight() - view.getHeight() - dp(4));
                        params.rightMargin = Math.min(maxRight,
                                Math.max(dp(4), startRight - (int) dx));
                        params.bottomMargin = Math.min(maxBottom,
                                Math.max(dp(4), startBottom - (int) dy));
                        view.setLayoutParams(params);
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!dragging) view.performClick();
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        return true;
                    default:
                        return false;
                }
            }
        });
        return chip;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    // ------------------------------------------------------------------
    // Auto-connect
    // ------------------------------------------------------------------

    /**
     * Called on a timer, when the page finishes loading and on USB attach.
     * Keeps the page attached to the X68HE without any user interaction.
     */
    private void autoConnectOnce() {
        if (!foreground || !pageReady || webView == null || bridge == null) return;

        long now = System.currentTimeMillis();
        UsbDevice keyboard = bridge.findKeyboardDevice();
        if (keyboard == null) {
            connectInFlight = false;
            return;
        }

        String deviceId = keyboard.getDeviceName();
        boolean alreadyOpen = bridge.isDeviceOpen(deviceId);
        if (alreadyOpen && !reattachRequested) {
            connectInFlight = false;
            return;
        }

        if (connectInFlight) {
            if (now - connectStartedAt < REQUEST_TIMEOUT_MS) return;
            log("Auto-connect attempt timed out; retrying");
            connectInFlight = false;
        }

        if (!alreadyOpen && now - lastConnectAttemptAt < CONNECT_RETRY_MS) return;

        if (!bridge.hasUsbPermission(keyboard)) {
            // Android shows its USB host permission dialog.  Rate-limit it so
            // a denial does not produce an endless popup loop.
            if (now - lastPermissionPromptAt < PERMISSION_RETRY_MS) return;
            lastPermissionPromptAt = now;
            log("Waiting for USB permission — tap Allow");
        } else {
            lastPermissionPromptAt = 0L;
        }

        reattachRequested = false;
        lastConnectAttemptAt = now;
        connectInFlight = true;
        connectStartedAt = now;
        log(alreadyOpen
                ? "Re-attaching page to already-open " + deviceId
                : "Auto-connecting to " + deviceId);
        requestDeviceFromJs();
    }

    private void requestDeviceFromJs() {
        if (webView == null) return;
        String js = "(function(){"
                + "if(!navigator.hid){console.error('WebHID shim not available');return;}"
                + "navigator.hid.requestDevice({filters:[]})"
                + ".then(function(d){"
                + "console.log('HID request OK: '+d.length);"
                + "try{AndroidHID.reportConnectResult(true,'ok');}catch(e){}"
                + "})"
                + ".catch(function(e){"
                + "console.error('HID request failed: '+e.message);"
                + "try{AndroidHID.reportConnectResult(false,String(e&&e.message?e.message:e));}catch(e2){}"
                + "});"
                + "})();";
        webView.evaluateJavascript(js, null);
    }

    // ------------------------------------------------------------------
    // Fullscreen
    // ------------------------------------------------------------------

    @SuppressWarnings("deprecation")
    private void enterFullscreen() {
        try {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            if (Build.VERSION.SDK_INT >= 28) {
                lp.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            }
            getWindow().setAttributes(lp);
            // The deprecated flags still work on every API level and avoid
            // the WindowInsetsController timing issues seen on some ROMs.
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
        } catch (Throwable t) {
            Log.w(TAG, "fullscreen failed: " + t);
        }
    }

    private void reportStartupCrash(Throwable t) {
        String trace = Log.getStackTraceString(t);
        try {
            GearHubApp.write(this, GearHubApp.CRASH_FILE, trace);
            GearHubApp.append(this, GearHubApp.STARTUP_FILE,
                    "CRASH in onCreate: " + t + "\n");
        } catch (Throwable ignored) {
        }
        try {
            ClipboardManager clipboard =
                    (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("GearHub crash", trace));
        } catch (Throwable ignored) {
        }
        try {
            TextView view = new TextView(this);
            view.setText("GearHub could not start the keyboard screen.\n"
                    + "The trace below is copied to the clipboard.\n\n" + trace);
            view.setTextSize(11f);
            view.setPadding(32, 32, 32, 32);
            setContentView(view);
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------
    // Hidden diagnostics gesture
    // ------------------------------------------------------------------

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN
                && ev.getPointerCount() == 3) {
            long now = SystemClock.uptimeMillis();
            if (now - lastThreeFingerAt > 1500L) {
                lastThreeFingerAt = now;
                autoConnectHandler.post(this::showLogDialog);
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    private boolean isQmkTop(Uri uri) {
        if (uri == null) return false;
        String host = uri.getHost();
        return host != null && (host.equals("qmk.top") || host.endsWith(".qmk.top"));
    }

    private WebResourceResponse injectShimIntoMainDocument(String url) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(15000);
            connection.setRequestProperty("User-Agent", DESKTOP_UA);
            connection.setRequestProperty("Accept-Encoding", "identity");
            connection.setRequestProperty("Accept", "text/html,application/xhtml+xml");

            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                Log.w(TAG, "main document HTTP " + code);
                return null;
            }

            InputStream in = connection.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();

            String html = new String(out.toByteArray(), StandardCharsets.UTF_8);
            String script = "<script>\n"
                    + shimJs.replace("</script>", "<\\/script>")
                    + "\n</script>";

            Matcher matcher = Pattern.compile("(?i)<head[^>]*>").matcher(html);
            String injected = matcher.find()
                    ? html.substring(0, matcher.end()) + script + html.substring(matcher.end())
                    : script + html;

            Log.d(TAG, "Injected WebHID shim into " + url);
            return new WebResourceResponse("text/html", "UTF-8",
                    new ByteArrayInputStream(injected.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            Log.w(TAG, "Could not inject shim: " + e.getMessage());
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private String readAsset(String name) {
        try (InputStream in = getAssets().open(name);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.e(TAG, "asset read failed: " + name, e);
            return "";
        }
    }

    @Override
    public void log(String message) {
        Log.d(TAG, message);
        appendLog(message);
    }

    private void appendLog(String line) {
        synchronized (logBuffer) {
            logBuffer.append(line).append('\n');
            if (logBuffer.length() > 24000) {
                logBuffer.delete(0, logBuffer.length() - 24000);
            }
        }
    }

    private void showLogDialog() {
        String text;
        synchronized (logBuffer) {
            text = logBuffer.length() == 0 ? "No log entries yet." : logBuffer.toString();
        }
        new AlertDialog.Builder(this)
                .setTitle("GearHub X68 HE log")
                .setMessage(text)
                .setPositiveButton("Close", (d, w) -> enterFullscreen())
                .setNeutralButton("Copy", (d, w) -> copyLog())
                .show();
    }

    private void copyLog() {
        String text;
        synchronized (logBuffer) {
            text = logBuffer.toString();
        }
        ClipboardManager clipboard =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("GearHub X68 HE log", text));
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onPause() {
        foreground = false;
        if (webView != null) webView.onPause();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        foreground = true;
        enterFullscreen();
        if (webView != null) webView.onResume();
        autoConnectHandler.removeCallbacks(autoConnectTick);
        autoConnectHandler.postDelayed(autoConnectTick, AUTO_CONNECT_TICK_MS);
        if (permissionRequester != null) permissionRequester.ensurePermissions();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) enterFullscreen();
    }

    @Override
    protected void onDestroy() {
        autoConnectHandler.removeCallbacksAndMessages(null);
        if (permissionRequester != null) {
            permissionRequester.destroy();
            permissionRequester = null;
        }
        if (usbReceiverRegistered) {
            try {
                unregisterReceiver(usbReceiver);
            } catch (Exception ignored) {
            }
            usbReceiverRegistered = false;
        }
        if (bridge != null) {
            final WebHidBridge closingBridge = bridge;
            new Thread(closingBridge::teardown, "bridge-teardown").start();
        }
        if (webView != null) {
            webView.removeJavascriptInterface("AndroidHID");
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
