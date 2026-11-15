package com.attackshark.gearhub;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.hardware.usb.UsbRequest;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * USB bridge for the Attack Shark X6 receiver.
 *
 * The official X6 web configurator (szslxd-tech.com) talks to a Windows helper
 * over a WebSocket at ws://localhost:10086.  This bridge takes the helper's
 * place on Android: assets/x6shim.js replaces that WebSocket and forwards the
 * hex messages here, and the feature reports are sent to the X6 configuration
 * interface through Android's USB Host API.
 *
 * X6 receiver: 1D57:FA60 (2.4 GHz dongle) or 1D57:FA61 (cable), configuration
 * interface 2, interrupt IN endpoint 0x83.  The web app prefixes every device
 * message with [connectionMethod, deviceId]; the helper strips those two bytes
 * and forwards the rest as a HID feature report.  The first remaining byte is
 * the HID report ID (4 = DPI, 5 = lighting, ...).
 */
public class X6Bridge {

    public interface StatusSink {
        void log(String message);
    }

    private static final String TAG = "GearHubX6";
    public static final int VENDOR_ID = 0x1D57;        // 7511
    public static final int PID_DONGLE = 0xFA60;       // 64096
    public static final int PID_CABLE = 0xFA61;        // 64097
    private static final int CONFIG_INTERFACE = 2;
    private static final int REPORT_LEN = 64;
    private static final int TIMEOUT_MS = 1500;
    private static final long PERMISSION_RETRY_MS = 20000L;
    private static final long OPEN_RETRY_MS = 3000L;

    private final Activity activity;
    private final WebView webView;
    private final StatusSink status;
    private final UsbManager usbManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Object ioLock = new Object();
    private final ExecutorService sender = Executors.newSingleThreadExecutor();

    private final String actionPermission;
    private final BroadcastReceiver permissionReceiver;
    private boolean receiverRegistered = false;
    private volatile boolean closed = false;

    private OpenDevice open;
    private boolean opening = false;
    private int openAttempt = 0;
    private long lastPermissionAt = 0L;
    private long lastOpenAttemptAt = 0L;
    private int sentLogged = 0;
    private int receivedLogged = 0;

    private volatile boolean connected = false;
    private volatile boolean wireless = true;
    private volatile int batteryStatus = 2;
    private volatile int batteryLevel = 100;

    private static class OpenDevice {
        final UsbDevice device;
        final UsbDeviceConnection connection;
        final UsbInterface iface;
        final UsbEndpoint endpointIn;
        volatile boolean reading = true;
        volatile UsbRequest inputRequest;
        private boolean closed = false;

        OpenDevice(UsbDevice device, UsbDeviceConnection connection,
                   UsbInterface iface, UsbEndpoint endpointIn) {
            this.device = device;
            this.connection = connection;
            this.iface = iface;
            this.endpointIn = endpointIn;
        }

        synchronized boolean closeOnce() {
            if (closed) return false;
            closed = true;
            reading = false;
            UsbRequest request = inputRequest;
            if (request != null) {
                try {
                    request.cancel();
                } catch (Exception ignored) {
                }
            }
            return true;
        }
    }

    public X6Bridge(Activity activity, WebView webView, StatusSink status) {
        this.activity = activity;
        this.webView = webView;
        this.status = status;
        this.usbManager = (UsbManager) activity.getSystemService(Context.USB_SERVICE);
        this.actionPermission = activity.getPackageName() + ".X6_USB_PERMISSION";

        this.permissionReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!actionPermission.equals(intent.getAction())) return;
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                boolean granted =
                        intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                log("USB permission " + (granted ? "granted" : "denied")
                        + (device == null ? "" : " for " + device.getDeviceName()));
                if (granted && device != null) {
                    lastPermissionAt = 0L;
                    openDeviceAsync(device);
                } else {
                    lastPermissionAt = System.currentTimeMillis();
                }
            }
        };
    }

    // ------------------------------------------------------------------
    // Device discovery
    // ------------------------------------------------------------------

    /** True when this USB device is an X6 receiver or its wired interface. */
    public static boolean isX6(UsbDevice device) {
        if (device == null) return false;
        int vid = device.getVendorId();
        int pid = device.getProductId();
        if (vid == VENDOR_ID && (pid == PID_DONGLE || pid == PID_CABLE)) return true;
        if (vid != VENDOR_ID) return false;
        String name = null;
        try {
            name = device.getProductName();
        } catch (Exception ignored) {
        }
        if (name == null) return false;
        String lower = name.toLowerCase();
        return lower.contains("x6");
    }

    /** Returns the X6 receiver if it is plugged in, otherwise null. */
    public static UsbDevice findDevice(UsbManager manager) {
        if (manager == null) return null;
        UsbDevice fallback = null;
        try {
            for (UsbDevice d : manager.getDeviceList().values()) {
                if (d.getVendorId() == VENDOR_ID) {
                    int pid = d.getProductId();
                    if (pid == PID_DONGLE || pid == PID_CABLE) return d;
                    if (fallback == null && isX6(d)) fallback = d;
                }
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    /** Returns the wireless flag for the currently attached device. */
    public static boolean isWireless(UsbDevice device) {
        return device == null || device.getProductId() != PID_CABLE;
    }

    // ------------------------------------------------------------------
    // Auto-connect
    // ------------------------------------------------------------------

    /**
     * Called on a timer and after USB hotplug events.  Requests permission the
     * first time, opens the configuration interface, and reconnects after a
     * detach without any user action.
     */
    public void ensureConnected() {
        if (closed) return;

        UsbDevice device = findDevice(usbManager);
        if (device == null) {
            if (isConnectedOrOpening()) closeOpen("receiver not attached");
            return;
        }

        OpenDevice current;
        synchronized (ioLock) {
            current = open;
            if (current != null
                    && current.device.getDeviceName().equals(device.getDeviceName())) {
                return;
            }
            if (opening) return;
        }
        if (current != null) closeOpen("receiver changed");

        if (!usbManager.hasPermission(device)) {
            long now = System.currentTimeMillis();
            if (now - lastPermissionAt < PERMISSION_RETRY_MS) return;
            lastPermissionAt = now;
            requestPermission(device);
            return;
        }

        long now = System.currentTimeMillis();
        synchronized (ioLock) {
            if (closed || opening || open != null) return;
            if (now - lastOpenAttemptAt < OPEN_RETRY_MS) return;
            lastOpenAttemptAt = now;
        }
        openDeviceAsync(device);
    }

    private boolean isConnectedOrOpening() {
        synchronized (ioLock) {
            return open != null || opening || connected;
        }
    }

    private void ensureReceiverRegistered() {
        if (receiverRegistered || closed) return;
        IntentFilter filter = new IntentFilter(actionPermission);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                activity.registerReceiver(permissionReceiver, filter,
                        Context.RECEIVER_NOT_EXPORTED);
            } else {
                activity.registerReceiver(permissionReceiver, filter);
            }
            receiverRegistered = true;
        } catch (Exception e) {
            log("USB permission receiver failed: " + e.getMessage());
        }
    }

    private void requestPermission(UsbDevice device) {
        ensureReceiverRegistered();
        int flags = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
        Intent intent = new Intent(actionPermission).setPackage(activity.getPackageName());
        PendingIntent pi = PendingIntent.getBroadcast(activity, 0, intent, flags);
        log("Requesting USB permission for the X6 — tap Allow");
        mainHandler.post(() -> {
            try {
                usbManager.requestPermission(device, pi);
            } catch (Exception e) {
                log("USB permission request failed: " + e.getMessage());
            }
        });
    }

    private void openDeviceAsync(UsbDevice device) {
        if (closed) return;
        int attempt;
        synchronized (ioLock) {
            if (closed || opening || open != null) return;
            opening = true;
            attempt = ++openAttempt;
        }

        Thread t = new Thread(() -> {
            UsbDeviceConnection connection = null;
            UsbInterface iface = null;
            boolean claimed = false;
            try {
                connection = usbManager.openDevice(device);
                if (connection == null) {
                    throw new IllegalStateException("openDevice() returned null");
                }
                iface = chooseInterface(device);
                if (iface == null) {
                    throw new IllegalStateException("no usable interface");
                }
                if (!connection.claimInterface(iface, true)) {
                    throw new IllegalStateException("could not claim interface " + iface.getId());
                }
                claimed = true;

                UsbEndpoint endpointIn = findInterruptIn(iface);
                OpenDevice opened = new OpenDevice(device, connection, iface, endpointIn);

                synchronized (ioLock) {
                    if (closed || attempt != openAttempt) {
                        // A detach or teardown invalidated this attempt.
                        try {
                            connection.releaseInterface(iface);
                        } catch (Exception ignored) {
                        }
                        try {
                            connection.close();
                        } catch (Exception ignored) {
                        }
                        if (attempt == openAttempt) opening = false;
                        return;
                    }
                    open = opened;
                    connected = true;
                    wireless = isWireless(device);
                    batteryStatus = 2;
                    batteryLevel = 100;
                    opening = false;
                }

                log("X6 opened: " + device.getDeviceName()
                        + " interface " + iface.getId()
                        + (endpointIn == null ? " (no IN endpoint)" : " IN 0x"
                        + String.format("%02X", endpointIn.getAddress())));
                notifyDevice(true);
                if (endpointIn != null) {
                    startInputLoop(opened);
                } else {
                    log("X6 has no interrupt IN endpoint; ACKs may not arrive");
                }
            } catch (Exception e) {
                if (connection != null && claimed) {
                    try {
                        connection.releaseInterface(iface);
                    } catch (Exception ignored) {
                    }
                }
                if (connection != null) {
                    try {
                        connection.close();
                    } catch (Exception ignored) {
                    }
                }
                synchronized (ioLock) {
                    if (attempt == openAttempt) {
                        opening = false;
                        open = null;
                        connected = false;
                    }
                }
                if (!closed) log("Could not open X6: " + e.getMessage());
            }
        }, "x6-open");
        t.setDaemon(true);
        t.start();
    }

    private UsbInterface chooseInterface(UsbDevice device) {
        UsbInterface firstHid = null;
        UsbInterface firstAny = null;
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface iface = device.getInterface(i);
            if (iface.getId() == CONFIG_INTERFACE) return iface;
            if (iface.getInterfaceClass() == UsbConstants.USB_CLASS_HID) {
                if (firstHid == null) firstHid = iface;
            } else if (firstAny == null) {
                firstAny = iface;
            }
        }
        if (firstHid != null) return firstHid;
        return firstAny;
    }

    private UsbEndpoint findInterruptIn(UsbInterface iface) {
        UsbEndpoint fallback = null;
        if (iface == null) return null;
        for (int i = 0; i < iface.getEndpointCount(); i++) {
            UsbEndpoint ep = iface.getEndpoint(i);
            if (ep.getType() != UsbConstants.USB_ENDPOINT_XFER_INT) continue;
            if (ep.getDirection() != UsbConstants.USB_DIR_IN) continue;
            if ((ep.getAddress() & 0xFF) == 0x83) return ep;
            if (fallback == null) fallback = ep;
        }
        return fallback;
    }

    // ------------------------------------------------------------------
    // Interrupt input
    // ------------------------------------------------------------------

    private void startInputLoop(OpenDevice opened) {
        Thread t = new Thread(() -> {
            UsbRequest request = new UsbRequest();
            ByteBuffer buffer = ByteBuffer.allocateDirect(REPORT_LEN);
            boolean sawReport = false;
            try {
                if (!request.initialize(opened.connection, opened.endpointIn)) {
                    log("X6 input request init failed");
                    return;
                }
                opened.inputRequest = request;
                while (opened.reading && !closed) {
                    buffer.clear();
                    if (!opened.reading || closed) break;
                    if (!request.queue(buffer)) break;
                    UsbRequest completed = opened.connection.requestWait();
                    if (completed == null) break;
                    if (completed != request) continue;

                    int n = buffer.position();
                    if (n <= 0) continue;
                    buffer.position(0);
                    byte[] raw = new byte[n];
                    buffer.get(raw, 0, n);

                    byte[] report = normalizeInputReport(raw);
                    String hex = hex(report);
                    if (!sawReport) {
                        sawReport = true;
                        log("First X6 input report (" + n + " bytes): " + hexHead(report, 20));
                    } else if (receivedLogged < 12) {
                        receivedLogged++;
                        log("X6 IN " + hexHead(report, 20));
                    }
                    evalJs("window.__x6Report&&window.__x6Report("
                            + JSONObject.quote(hex) + ")");
                    handleReport(report);
                }
            } catch (Exception e) {
                if (opened.reading && !closed) {
                    log("X6 input loop error: " + e.getMessage());
                }
            } finally {
                opened.inputRequest = null;
                try {
                    request.close();
                } catch (Exception ignored) {
                }

                boolean wasConnected;
                synchronized (ioLock) {
                    wasConnected = open == opened && connected;
                    if (open == opened) {
                        open = null;
                        connected = false;
                    }
                }
                if (opened.closeOnce()) {
                    try {
                        opened.connection.releaseInterface(opened.iface);
                    } catch (Exception ignored) {
                    }
                    try {
                        opened.connection.close();
                    } catch (Exception ignored) {
                    }
                    if (wasConnected && !closed) {
                        log("X6 input loop ended; released receiver");
                        notifyDisconnectJs();
                    }
                }
            }
        }, "x6-input");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Some HID endpoints pad short reports to the endpoint packet size.  The
     * web app dispatches acknowledgements by exact length (5 bytes), so trim
     * trailing padding from the known 5-byte report types.
     */
    private static byte[] normalizeInputReport(byte[] raw) {
        if (raw == null) return null;
        if (raw.length > 5
                && (raw[0] & 0xFF) == 0x03
                && (raw[1] & 0xFF) == 0x10) {
            int command = raw[2] & 0xFF;
            if (command == 0x50 || command == 0x40 || command == 0x10) {
                return Arrays.copyOf(raw, 5);
            }
        }
        if (raw.length > 4 && (raw[0] & 0xFF) == 0x10) {
            int command = raw[1] & 0xFF;
            if (command == 0x50 || command == 0x40 || command == 0x10) {
                return Arrays.copyOf(raw, 4);
            }
        }
        return raw;
    }

    /**
     * The X6 reports DPI-stage events and battery state over the interrupt
     * endpoint.  Raw reports are forwarded to the page unchanged; battery
     * reports are also translated into the helper's 7-byte presence format.
     */
    private void handleReport(byte[] raw) {
        if (raw == null) return;
        int command;
        int stage;
        int levelByte;
        if (raw.length >= 5
                && (raw[0] & 0xFF) == 0x03
                && (raw[1] & 0xFF) == 0x10) {
            command = raw[2] & 0xFF;
            stage = raw[3] & 0xFF;
            levelByte = raw[4] & 0xFF;
        } else if (raw.length >= 4
                && (raw[0] & 0xFF) == 0x10
                && (raw[1] & 0xFF) == 0x40) {
            // Report ID already stripped by the HID layer.
            command = 0x40;
            stage = raw[2] & 0xFF;
            levelByte = raw[3] & 0xFF;
        } else {
            return;
        }
        if (command != 0x40) return;

        int level = levelByte * 10;
        if (level < 0) level = 0;
        if (level > 100) level = 100;
        batteryStatus = stage == 3 ? 3 : 2;
        batteryLevel = level;
        log("X6 battery: stage=" + stage + ", " + level + "%");
        notifyDevice(false);
    }

    // ------------------------------------------------------------------
    // JavaScript interface
    // ------------------------------------------------------------------

    /** Helper version + device state for a newly created shim socket. */
    @JavascriptInterface
    public String getState() {
        try {
            JSONObject state = new JSONObject();
            state.put("connected", connected);
            state.put("wireless", wireless);
            state.put("batteryStatus", batteryStatus);
            state.put("batteryLevel", batteryLevel);
            return state.toString();
        } catch (Exception e) {
            return "{\"connected\":false}";
        }
    }

    /**
     * Receives a hex message from the fake helper socket.  The web app prefixes
     * messages with [connectionMethod, deviceId]; those two bytes are removed
     * before the feature report is sent.
     */
    @JavascriptInterface
    public void send(String hex) {
        if (closed || hex == null) return;
        byte[] message;
        try {
            message = parseHex(hex);
        } catch (Exception e) {
            log("Ignoring malformed X6 command: " + e.getMessage());
            return;
        }
        if (message.length < 3) return;

        byte[] payload = Arrays.copyOfRange(message, 2, message.length);
        int reportId = payload[0] & 0xFF;
        if (reportId == 0xF0 || reportId >= 0xF0) {
            // Read/version commands are not supported by this bridge.  The
            // shim reports helper 3.0 so the page skips them.
            log("Ignoring X6 read command " + hexHead(payload, 12));
            return;
        }
        if (reportId == 0 || reportId > 0x7F) {
            log("Ignoring X6 command with report ID " + reportId);
            return;
        }

        byte[] data = Arrays.copyOfRange(payload, 1, payload.length);
        try {
            sender.execute(() -> sendFeatureReport(reportId, data));
        } catch (Exception e) {
            log("Could not queue X6 report " + reportId + ": " + e.getMessage());
        }
    }

    @JavascriptInterface
    public void uiLog(String message) {
        log(String.valueOf(message));
    }

    // ------------------------------------------------------------------
    // Feature reports
    // ------------------------------------------------------------------

    private void sendFeatureReport(int reportId, byte[] data) {
        OpenDevice od;
        synchronized (ioLock) {
            od = open;
        }
        if (od == null || !connected) {
            log("X6 is not open; dropping report " + reportId);
            return;
        }

        if (sentLogged < 12) {
            sentLogged++;
            log("SET_FEATURE id=" + reportId + " len=" + data.length
                    + " data=" + hexHead(data, 16));
        }

        int value = (0x03 << 8) | (reportId & 0xFF);
        int n = -1;
        try {
            // Standard USB HID: report ID goes in wValue, not in the payload.
            n = od.connection.controlTransfer(
                    0x21, 0x09, value, od.iface.getId(),
                    data, data.length, TIMEOUT_MS);

            if (n < 0) {
                // Some vendor firmwares expect the report ID repeated as the
                // first data byte.  Try that before declaring failure.
                byte[] withId = new byte[data.length + 1];
                withId[0] = (byte) reportId;
                System.arraycopy(data, 0, withId, 1, data.length);
                n = od.connection.controlTransfer(
                        0x21, 0x09, value, od.iface.getId(),
                        withId, withId.length, TIMEOUT_MS);
                if (n >= 0) {
                    log("SET_FEATURE id=" + reportId + " accepted with ID in payload");
                }
            }

            if (n < 0) {
                // Last resort: treat the command as report 0 with the ID as
                // the first data byte.
                byte[] withId = new byte[data.length + 1];
                withId[0] = (byte) reportId;
                System.arraycopy(data, 0, withId, 1, data.length);
                n = od.connection.controlTransfer(
                        0x21, 0x09, 0x0300, od.iface.getId(),
                        withId, withId.length, TIMEOUT_MS);
                if (n >= 0) {
                    log("SET_FEATURE id=" + reportId + " accepted as report 0");
                }
            }
        } catch (Exception e) {
            log("SET_FEATURE id=" + reportId + " error: " + e.getMessage());
            return;
        }

        if (n < 0) {
            log("SET_FEATURE id=" + reportId + " failed");
        }
    }

    // ------------------------------------------------------------------
    // Lifecycle / helpers
    // ------------------------------------------------------------------

    /** Called when Android reports that a USB device was unplugged. */
    public void onUsbDetached(UsbDevice device) {
        if (device == null) return;
        OpenDevice od;
        synchronized (ioLock) {
            od = open;
        }
        if (od != null && od.device.getDeviceName().equals(device.getDeviceName())) {
            closeOpen("receiver detached");
        } else {
            // An open attempt may still be in flight; invalidate it.
            synchronized (ioLock) {
                if (opening) openAttempt++;
                opening = false;
            }
        }
    }

    private void closeOpen(String reason) {
        OpenDevice od;
        boolean wasConnected;
        synchronized (ioLock) {
            od = open;
            wasConnected = connected;
            open = null;
            connected = false;
            opening = false;
            openAttempt++;
        }

        boolean notify = od == null;
        if (od != null) {
            notify = od.closeOnce();
            if (notify) {
                try {
                    od.connection.releaseInterface(od.iface);
                } catch (Exception ignored) {
                }
                try {
                    od.connection.close();
                } catch (Exception ignored) {
                }
            }
            log("X6 closed (" + reason + ")");
        }
        if (wasConnected && notify && !closed) notifyDisconnectJs();
    }

    public void teardown() {
        if (closed) return;
        closed = true;
        closeOpen("activity closed");
        if (receiverRegistered) {
            try {
                activity.unregisterReceiver(permissionReceiver);
            } catch (Exception ignored) {
            }
            receiverRegistered = false;
        }
        sender.shutdownNow();
    }

    private void notifyDevice(boolean initial) {
        if (closed) return;
        evalJs("window.__x6Device&&window.__x6Device("
                + (wireless ? 1 : 0) + ","
                + batteryStatus + ","
                + batteryLevel + ","
                + (initial ? 1 : 0) + ")");
    }

    private void notifyDisconnectJs() {
        if (closed) return;
        evalJs("window.__x6Disconnect&&window.__x6Disconnect()");
    }

    private void evalJs(String js) {
        if (closed || webView == null) return;
        mainHandler.post(() -> {
            if (closed || webView == null) return;
            try {
                webView.evaluateJavascript(js, null);
            } catch (Exception ignored) {
            }
        });
    }

    private void log(String message) {
        Log.d(TAG, message);
        if (status != null) status.log(message);
    }

    private static byte[] parseHex(String hex) {
        StringBuilder clean = new StringBuilder(hex.length());
        for (int i = 0; i < hex.length(); i++) {
            char c = hex.charAt(i);
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t' || c == ':') continue;
            if (Character.digit(c, 16) < 0) {
                throw new IllegalArgumentException("not hexadecimal");
            }
            clean.append(c);
        }
        if ((clean.length() & 1) != 0) {
            throw new IllegalArgumentException("odd number of hex digits");
        }
        if (clean.length() > 512) {
            throw new IllegalArgumentException("message too long");
        }
        byte[] out = new byte[clean.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) ((Character.digit(clean.charAt(i * 2), 16) << 4)
                    | Character.digit(clean.charAt(i * 2 + 1), 16));
        }
        return out;
    }

    private static String hex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }

    private static String hexHead(byte[] data, int max) {
        if (data == null || data.length == 0) return "(empty)";
        int n = Math.min(Math.max(max, 0), data.length);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format("%02X", data[i] & 0xFF));
        }
        if (data.length > n) sb.append(" ...");
        return sb.toString();
    }
}
