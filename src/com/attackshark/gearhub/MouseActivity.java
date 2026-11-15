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
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
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
import android.widget.TextView;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Fullscreen wrapper for the official Attack Shark X6 web configurator.
 *
 * The page normally talks to a Windows helper over ws://localhost:10086.
 * assets/x6shim.js replaces that socket and routes the messages to X6Bridge,
 * which uses Android's USB Host API.  There are no app buttons: the receiver
 * is detected, permission is requested, and the device is opened automatically.
 *
 * A hidden three-finger tap opens the diagnostic log.
 */
public class MouseActivity extends Activity implements X6Bridge.StatusSink {

    private static final String TAG = "GearHubX6";
    private static final String START_URL = "https://szslxd-tech.com/#/";
    private static final String DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) "
                    + "AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/131.0.0.0 Safari/537.36";
    private static final String VIEWPORT =
            "width=1280, initial-scale=0.4, minimum-scale=0.1, "
                    + "maximum-scale=4, user-scalable=yes";

    private static final long AUTO_CONNECT_TICK_MS = 1500L;

    private WebView webView;
    private X6Bridge bridge;
    private String shimJs = "";
    private final StringBuilder logBuffer = new StringBuilder();
    private boolean usbReceiverRegistered = false;

    private boolean foreground = false;
    private boolean pageReady = false;
    private long lastThreeFingerAt = 0L;

    private final Handler autoConnectHandler = new Handler(Looper.getMainLooper());
    private final Runnable autoConnectTick = new Runnable() {
        @Override
        public void run() {
            try {
                if (foreground && bridge != null) bridge.ensureConnected();
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
                autoConnectHandler.postDelayed(() -> {
                    if (foreground && bridge != null) bridge.ensureConnected();
                }, 900L);
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                log("USB device detached"
                        + (device != null ? ": " + device.getDeviceName() : ""));
                if (bridge != null && device != null) {
                    final UsbDevice detached = device;
                    new Thread(() -> bridge.onUsbDetached(detached), "x6-detach").start();
                }
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
        GearHubApp.mark(this, "MouseActivity.onCreate");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        shimJs = readAsset("x6shim.js");

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
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setSupportMultipleWindows(false);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUserAgentString(DESKTOP_UA);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        WebView.setWebContentsDebuggingEnabled(true);

        bridge = new X6Bridge(this, webView, this);
        webView.addJavascriptInterface(bridge, "AndroidX6");

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

        // The whole window is the configurator: no status bar, no buttons.
        setContentView(webView);
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
            public WebResourceResponse shouldInterceptRequest(
                    WebView view, WebResourceRequest request) {
                try {
                    if (request.isForMainFrame()
                            && "GET".equalsIgnoreCase(request.getMethod())
                            && isSite(request.getUrl())
                            && !isDownload(request.getUrl())) {
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
                                + "var metas=document.querySelectorAll('meta[name=\\\"viewport\\\"]');"
                                + "for(var j=0;j<metas.length;j++)metas[j].setAttribute('content','" + VIEWPORT + "');"
                                + "if(!metas.length){var m=document.createElement('meta');m.name='viewport';m.setAttribute('content','" + VIEWPORT + "');document.head.appendChild(m);}"
                                + "var q=document.querySelectorAll('[data-v-2467292e]');"
                                + "for(var i=0;i<q.length;i++){q[i].style.setProperty('display','none','important');}"
                                + "}catch(e){}})();", null);
                pageReady = true;
                if (bridge != null) bridge.ensureConnected();
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleNavigation(url);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleNavigation(request.getUrl().toString());
            }
        });

        webView.loadUrl(START_URL);
        GearHubApp.mark(this, "page load requested");

        appendLog("GearHub build 6 (versionName 1.5), X6 driver starting — auto-connect enabled");
        autoConnectHandler.postDelayed(autoConnectTick, 1200L);
    }

    private boolean handleNavigation(String url) {
        Uri uri = Uri.parse(url);
        if (isSite(uri) && !isDownload(uri)) return false;

        // The page redirects to myapp://WebDriver when it cannot reach the
        // helper, and its driver buttons point at Windows/macOS installers.
        // Both are pointless (and disruptive) on Android.
        if (isDownload(uri)) {
            log("Blocked driver download: " + url);
        } else {
            log("Blocked external navigation: " + url);
        }
        return true;
    }

    private boolean isSite(Uri uri) {
        if (uri == null) return false;
        String scheme = uri.getScheme();
        if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) {
            return false;
        }
        String host = uri.getHost();
        if (host == null) return false;
        host = host.toLowerCase();
        return host.equals("szslxd-tech.com") || host.equals("www.szslxd-tech.com");
    }

    private boolean isDownload(Uri uri) {
        if (uri == null) return false;
        String path = uri.getPath();
        return path != null && path.toLowerCase().startsWith("/downloads/");
    }

    private WebResourceResponse injectShimIntoMainDocument(String url) {
        if (shimJs == null || shimJs.isEmpty()) return null;
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

            InputStream raw = connection.getInputStream();
            String encoding = connection.getContentEncoding();
            InputStream in = raw;
            if (encoding != null && encoding.toLowerCase().contains("gzip")) {
                in = new GZIPInputStream(raw);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();

            String html = new String(out.toByteArray(), StandardCharsets.UTF_8);
            String script = "<meta name=\"viewport\" content=\"" + VIEWPORT + "\">\n"
                    + "<script>\n"
                    + shimJs.replace("</script>", "<\\/script>")
                    + "\n</script>";

            // Insert just before </head> so our viewport wins and the shim
            // runs before the site's deferred module scripts.
            int headEnd = html.toLowerCase().lastIndexOf("</head>");
            String injected;
            if (headEnd >= 0) {
                injected = html.substring(0, headEnd) + script + html.substring(headEnd);
            } else {
                Matcher matcher = Pattern.compile("(?i)<head[^>]*>").matcher(html);
                injected = matcher.find()
                        ? html.substring(0, matcher.end()) + script + html.substring(matcher.end())
                        : script + html;
            }

            Log.d(TAG, "Injected X6 helper shim into " + url);
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
                    "CRASH in MouseActivity.onCreate: " + t + "\n");
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
            view.setText("GearHub X6 could not start.\n"
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
                .setTitle("GearHub X6 log")
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
        clipboard.setPrimaryClip(ClipData.newPlainText("GearHub X6 log", text));
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
        autoConnectHandler.removeCallbacks(autoConnectTick);
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
        if (bridge != null) {
            autoConnectHandler.postDelayed(() -> {
                if (foreground) bridge.ensureConnected();
            }, 500L);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) enterFullscreen();
    }

    @Override
    protected void onDestroy() {
        autoConnectHandler.removeCallbacksAndMessages(null);
        if (usbReceiverRegistered) {
            try {
                unregisterReceiver(usbReceiver);
            } catch (Exception ignored) {
            }
            usbReceiverRegistered = false;
        }
        if (bridge != null) {
            final X6Bridge closingBridge = bridge;
            new Thread(closingBridge::teardown, "x6-teardown").start();
        }
        if (webView != null) {
            webView.removeJavascriptInterface("AndroidX6");
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
