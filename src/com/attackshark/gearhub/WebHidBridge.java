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
import android.util.Base64;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Slice of the WebHID API used by qmk.top / GearHub, backed by Android's
 * USB Host API.  The matching JavaScript shim is assets/webhid_shim.js.
 *
 * The X68HE exposes its configuration endpoint as vendor collection
 * usagePage 0xFFFF, usage 2, on USB interface 2 (VID 0x3151, PID 0x502D).
 * Feature reports are 64 bytes with report ID 0.
 *
 * Based on the MIT-licensed WebHID Bridge by Halil156, adapted for the
 * Attack Shark X68 HE.
 */
public class WebHidBridge {

    public interface StatusSink {
        void log(String message);
    }

    /** Lets the activity react as soon as the page finishes a connect call. */
    public interface ConnectListener {
        void onConnectResult(boolean success, String message);
    }

    private static final String TAG = "GearHubBridge";
    private static final int TARGET_VID = 12625; // 0x3151
    private static final int TARGET_PID = 20525; // 0x502D (X68HE wired)
    private static final int REPORT_LEN = 64;
    private static final int TIMEOUT_MS = 5000;
    private static final int FEATURE_TIMEOUT_MS = 1000;

    private final Activity activity;
    private final WebView webView;
    private final StatusSink status;
    private final UsbManager usbManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Object ioLock = new Object();

    private final Map<String, OpenDevice> openDevices = new HashMap<>();
    private final Map<String, CountDownLatch> pendingPermission = new HashMap<>();
    private final Map<String, Boolean> permissionGranted = new HashMap<>();

    private final String actionUsbPermission;
    private volatile ConnectListener connectListener;
    private boolean receiverRegistered = false;
    private volatile boolean closed = false;
    private int featureSendsLogged = 0;
    private int featureReadsLogged = 0;

    private static class OpenDevice {
        final UsbDevice device;
        final UsbDeviceConnection connection;
        final UsbInterface iface;
        final UsbEndpoint endpointIn;
        final UsbEndpoint endpointOut;
        volatile boolean reading = true;
        volatile UsbRequest inputRequest;
        boolean reportIdsUsed = false;
        JSONArray collections = new JSONArray();

        OpenDevice(UsbDevice device, UsbDeviceConnection connection, UsbInterface iface,
                   UsbEndpoint endpointIn, UsbEndpoint endpointOut) {
            this.device = device;
            this.connection = connection;
            this.iface = iface;
            this.endpointIn = endpointIn;
            this.endpointOut = endpointOut;
        }

        void stopReading() {
            reading = false;
            UsbRequest request = inputRequest;
            if (request != null) {
                try {
                    request.cancel();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private final BroadcastReceiver permissionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!actionUsbPermission.equals(intent.getAction())) return;
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (device == null) return;
            boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
            permissionGranted.put(device.getDeviceName(), granted);
            CountDownLatch latch = pendingPermission.get(device.getDeviceName());
            if (latch != null) latch.countDown();
            log("USB permission " + (granted ? "granted" : "denied"));
        }
    };

    public WebHidBridge(Activity activity, WebView webView, StatusSink status) {
        this.activity = activity;
        this.webView = webView;
        this.status = status;
        this.usbManager = (UsbManager) activity.getSystemService(Context.USB_SERVICE);
        this.actionUsbPermission = activity.getPackageName() + ".USB_PERMISSION";
    }

    // ------------------------------------------------------------------
    // Native helpers used by the auto-connect watchdog
    // ------------------------------------------------------------------

    /** Returns the X68HE if it is plugged in, otherwise null. */
    public UsbDevice findKeyboardDevice() {
        try {
            for (UsbDevice d : usbManager.getDeviceList().values()) {
                if (d.getVendorId() == TARGET_VID && d.getProductId() == TARGET_PID) return d;
            }
            for (UsbDevice d : usbManager.getDeviceList().values()) {
                String name = d.getProductName();
                if (name == null) continue;
                String lower = name.toLowerCase();
                if (lower.contains("x68") || lower.contains("attack") || lower.contains("shark")) {
                    return d;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** True while the configuration interface is claimed by the page. */
    public boolean isDeviceOpen(String deviceId) {
        if (deviceId == null) return false;
        synchronized (ioLock) {
            return openDevices.containsKey(deviceId);
        }
    }

    /** True when Android has already granted USB host access to the device. */
    public boolean hasUsbPermission(UsbDevice device) {
        return device != null && usbManager.hasPermission(device);
    }

    // ------------------------------------------------------------------
    // JavaScript entry point
    // ------------------------------------------------------------------

    /** Lets the shim put human-readable connection status in the app bar. */
    @JavascriptInterface
    public void uiLog(String message) {
        log(message);
    }

    public void setConnectListener(ConnectListener listener) {
        this.connectListener = listener;
    }

    /** Called by the shim when navigator.hid.requestDevice() settles. */
    @JavascriptInterface
    public void reportConnectResult(boolean success, String message) {
        ConnectListener listener = connectListener;
        if (listener != null) listener.onConnectResult(success, message);
    }

    @JavascriptInterface
    public void call(String method, String argsJson, int callId) {
        Thread t = new Thread(() -> {
            synchronized (ioLock) {
                try {
                    JSONObject args = new JSONObject(argsJson == null ? "{}" : argsJson);
                    Object result;
                    switch (method) {
                        case "requestDevice":
                            result = requestDevice(args);
                            break;
                        case "getDevices":
                            result = listKnownDevices();
                            break;
                        case "open":
                            result = openDevice(args);
                            break;
                        case "close":
                            result = closeDevice(args);
                            break;
                        case "sendReport":
                            result = sendReport(args, false);
                            break;
                        case "sendFeatureReport":
                            result = sendReport(args, true);
                            break;
                        case "receiveFeatureReport":
                            result = receiveFeatureReport(args);
                            break;
                        default:
                            throw new IllegalArgumentException("Unknown method: " + method);
                    }
                    resolveJs(callId, result);
                } catch (Exception e) {
                    Log.e(TAG, "call failed: " + method, e);
                    String msg = e.getMessage();
                    rejectJs(callId, msg == null || msg.isEmpty() ? e.toString() : msg);
                }
            }
        }, "hid-" + method);
        t.start();
    }

    private void resolveJs(int callId, Object result) {
        String json;
        if (result == null) {
            json = "null";
        } else if (result instanceof JSONObject || result instanceof JSONArray) {
            json = result.toString();
        } else {
            json = String.valueOf(result);
        }
        String escaped = JSONObject.quote(json);
        mainHandler.post(() -> evalJs(
                "window.__hidResolve(" + callId + "," + escaped + ")"));
    }

    private void rejectJs(int callId, String message) {
        String escaped = JSONObject.quote(message);
        mainHandler.post(() -> evalJs(
                "window.__hidReject(" + callId + "," + escaped + ")"));
    }

    private void evalJs(String js) {
        if (closed) return;
        try {
            webView.evaluateJavascript(js, null);
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------
    // Device discovery, permission, open
    // ------------------------------------------------------------------

    private JSONObject requestDevice(JSONObject args) throws Exception {
        JSONArray filters = args.optJSONArray("filters");
        if (filters == null) filters = new JSONArray();

        List<UsbDevice> all = new ArrayList<>(usbManager.getDeviceList().values());
        log("USB scan: " + all.size() + " device(s)");
        for (UsbDevice d : all) {
            log("  " + d.getDeviceName() + " "
                    + hex(d.getVendorId()) + ":" + hex(d.getProductId())
                    + (d.getProductName() == null ? "" : " " + d.getProductName()));
        }

        List<UsbDevice> candidates = new ArrayList<>();
        for (UsbDevice d : all) {
            if (matchesFilters(d, filters)) candidates.add(d);
        }
        if (candidates.isEmpty()) {
            log("No filter match; falling back to HID devices");
            for (UsbDevice d : all) {
                if (hasHidInterface(d)) candidates.add(d);
            }
        }
        if (candidates.isEmpty()) candidates.addAll(all);
        if (candidates.isEmpty()) {
            throw new IllegalStateException(
                    "No USB device found. Plug the X68HE into the phone with an OTG adapter.");
        }

        UsbDevice chosen = pickBest(candidates);
        log("Selected " + chosen.getDeviceName() + " "
                + hex(chosen.getVendorId()) + ":" + hex(chosen.getProductId()));

        if (!requestPermissionBlocking(chosen)) {
            throw new IllegalStateException("USB permission was denied.");
        }
        openInternal(chosen);
        return deviceInfoJson(chosen);
    }

    private UsbDevice pickBest(List<UsbDevice> candidates) {
        for (UsbDevice d : candidates) {
            if (d.getVendorId() == TARGET_VID && d.getProductId() == TARGET_PID) return d;
        }
        for (UsbDevice d : candidates) {
            String n = d.getProductName();
            if (n != null) {
                String l = n.toLowerCase();
                if (l.contains("x68") || l.contains("attack") || l.contains("shark")) return d;
            }
        }
        // Prefer the device with the most interfaces (the keyboard has several HID
        // collections, a mouse usually has fewer).
        UsbDevice best = candidates.get(0);
        for (UsbDevice d : candidates) {
            if (d.getInterfaceCount() > best.getInterfaceCount()) best = d;
        }
        return best;
    }

    private boolean matchesFilters(UsbDevice device, JSONArray filters) {
        if (filters.length() == 0) return true;
        for (int i = 0; i < filters.length(); i++) {
            JSONObject f = filters.optJSONObject(i);
            if (f == null) continue;
            if (f.has("vendorId") && f.optInt("vendorId") != device.getVendorId()) continue;
            if (f.has("productId") && f.optInt("productId") != device.getProductId()) continue;
            return true;
        }
        return false;
    }

    private boolean hasHidInterface(UsbDevice device) {
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            if (device.getInterface(i).getInterfaceClass() == UsbConstants.USB_CLASS_HID) {
                return true;
            }
        }
        return false;
    }

    private void ensureReceiverRegistered() {
        if (receiverRegistered) return;
        IntentFilter filter = new IntentFilter(actionUsbPermission);
        if (Build.VERSION.SDK_INT >= 33) {
            activity.registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            activity.registerReceiver(permissionReceiver, filter);
        }
        receiverRegistered = true;
    }

    private boolean requestPermissionBlocking(UsbDevice device) {
        if (usbManager.hasPermission(device)) return true;
        ensureReceiverRegistered();

        String name = device.getDeviceName();
        CountDownLatch latch = new CountDownLatch(1);
        pendingPermission.put(name, latch);
        permissionGranted.remove(name);

        int flags = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
        Intent intent = new Intent(actionUsbPermission).setPackage(activity.getPackageName());
        PendingIntent pi = PendingIntent.getBroadcast(activity, 0, intent, flags);

        log("Requesting USB permission — tap Allow");
        mainHandler.post(() -> usbManager.requestPermission(device, pi));

        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        pendingPermission.remove(name);
        return Boolean.TRUE.equals(permissionGranted.get(name));
    }

    private UsbInterface chooseInterface(UsbDevice device, UsbDeviceConnection connection) {
        List<UsbInterface> hids = new ArrayList<>();
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface iface = device.getInterface(i);
            if (iface.getInterfaceClass() == UsbConstants.USB_CLASS_HID) hids.add(iface);
        }
        if (hids.isEmpty()) {
            return device.getInterfaceCount() > 0 ? device.getInterface(0) : null;
        }

        // qmk.top's X68HE filter names usagePage 0xFFFF / usage 2 on
        // interface 2.  Trust the report descriptor first, preferring
        // interface 2 when more than one interface exposes that collection.
        UsbInterface vendorFallback = null;
        for (UsbInterface iface : hids) {
            try {
                byte[] raw = HidDescriptor.fetchRaw(connection, iface);
                if (raw == null) continue;
                JSONArray cols = HidDescriptor.parse(raw);
                if (HidDescriptor.hasVendorCollection(cols, 0xFFFF, 2)) {
                    if (iface.getId() == 2) return iface;
                    if (vendorFallback == null) vendorFallback = iface;
                }
            } catch (Exception ignored) {
            }
        }
        if (vendorFallback != null) return vendorFallback;

        // Fall back to the interface number qmk.top expects, then to the
        // last HID interface (the vendor collection is normally last).
        for (UsbInterface iface : hids) {
            if (iface.getId() == 2) return iface;
        }
        return hids.get(hids.size() - 1);
    }

    private void openInternal(UsbDevice device) throws Exception {
        String id = device.getDeviceName();
        if (openDevices.containsKey(id)) return;

        UsbDeviceConnection connection = usbManager.openDevice(device);
        if (connection == null) {
            throw new IllegalStateException("openDevice() returned null.");
        }

        UsbInterface iface = chooseInterface(device, connection);
        if (iface == null) {
            connection.close();
            throw new IllegalStateException("No usable USB interface found.");
        }

        if (!connection.claimInterface(iface, true)) {
            connection.close();
            throw new IllegalStateException("Could not claim USB interface " + iface.getId());
        }

        JSONArray collections = new JSONArray();
        try {
            byte[] raw = HidDescriptor.fetchRaw(connection, iface);
            if (raw != null) {
                collections = HidDescriptor.parse(raw);
                log("Interface " + iface.getId() + ": HID descriptor "
                        + raw.length + " bytes, " + collections.length() + " collection(s)");
                log("  raw: " + hexHead(raw, 64));
                log("  collections: " + collections.toString());
            } else {
                log("Interface " + iface.getId() + ": no HID descriptor returned");
            }
        } catch (Exception e) {
            log("Descriptor read failed: " + e.getMessage());
        }

        if (!HidDescriptor.hasVendorCollection(collections, 0xFFFF, 2)) {
            JSONObject synthetic = new JSONObject();
            synthetic.put("usagePage", 0xFFFF);
            synthetic.put("usage", 2);
            synthetic.put("type", 2);
            synthetic.put("children", new JSONArray());
            synthetic.put("inputReports", new JSONArray());
            synthetic.put("outputReports", new JSONArray());
            synthetic.put("featureReports", new JSONArray());
            collections.put(synthetic);
            log("Added synthetic vendor collection 0xFFFF:2");
        }

        UsbEndpoint epIn = null;
        UsbEndpoint epOut = null;
        for (int i = 0; i < iface.getEndpointCount(); i++) {
            UsbEndpoint ep = iface.getEndpoint(i);
            if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_INT) {
                if (ep.getDirection() == UsbConstants.USB_DIR_IN) epIn = ep;
                else epOut = ep;
            }
        }

        OpenDevice opened = new OpenDevice(device, connection, iface, epIn, epOut);
        opened.collections = collections;
        opened.reportIdsUsed = HidDescriptor.usesReportIds(collections);
        openDevices.put(id, opened);
        log("Opened interface " + iface.getId() + " ("
                + iface.getInterfaceClass() + "), reports: "
                + (epIn != null ? "IN " : "") + (epOut != null ? "OUT " : "")
                + (opened.reportIdsUsed ? "(IDs used)" : "(no report IDs)"));

        if (epIn != null) startInputReportLoop(id, opened, epIn);
    }

    private JSONObject deviceInfoJson(UsbDevice device) throws Exception {
        OpenDevice open = openDevices.get(device.getDeviceName());
        JSONObject info = new JSONObject();
        info.put("id", device.getDeviceName());
        info.put("vendorId", device.getVendorId());
        info.put("productId", device.getProductId());

        String name = null;
        try {
            name = device.getProductName();
        } catch (Exception ignored) {
        }
        if (name == null || name.isEmpty()) name = "Attack Shark X68 HE";
        info.put("productName", name);
        info.put("collections", open != null ? open.collections : new JSONArray());
        return info;
    }

    private JSONArray listKnownDevices() throws Exception {
        JSONArray arr = new JSONArray();
        for (OpenDevice o : new ArrayList<>(openDevices.values())) {
            arr.put(deviceInfoJson(o.device));
        }
        return arr;
    }

    private JSONObject openDevice(JSONObject args) throws Exception {
        String id = args.getString("deviceId");
        UsbDevice device = usbManager.getDeviceList().get(id);
        if (device == null) {
            OpenDevice open = openDevices.get(id);
            if (open != null) device = open.device;
        }
        if (device == null) {
            throw new IllegalStateException("Device is no longer connected: " + id);
        }
        openInternal(device);
        return deviceInfoJson(device);
    }

    private JSONObject closeDevice(JSONObject args) throws Exception {
        String id = args.getString("deviceId");
        OpenDevice open = openDevices.remove(id);
        if (open != null) {
            open.stopReading();
            try {
                open.connection.releaseInterface(open.iface);
            } catch (Exception ignored) {
            }
            try {
                open.connection.close();
            } catch (Exception ignored) {
            }
            log("Closed " + id);
        }
        return new JSONObject();
    }

    // ------------------------------------------------------------------
    // Reports
    // ------------------------------------------------------------------

    private JSONObject sendReport(JSONObject args, boolean feature) throws Exception {
        String id = args.getString("deviceId");
        int reportId = args.optInt("reportId", 0);
        byte[] payload = Base64.decode(args.getString("data"), Base64.DEFAULT);

        OpenDevice open = openDevices.get(id);
        if (open == null) throw new IllegalStateException("Device is not open: " + id);

        if (feature && featureSendsLogged < 16) {
            featureSendsLogged++;
            log("SET_FEATURE id=" + reportId + " len=" + payload.length
                    + " data=" + hexHead(payload, 16));
        }

        if (!feature && open.endpointOut != null) {
            byte[] buf;
            if (open.reportIdsUsed) {
                buf = new byte[payload.length + 1];
                buf[0] = (byte) reportId;
                System.arraycopy(payload, 0, buf, 1, payload.length);
            } else {
                buf = payload;
            }
            int n = open.connection.bulkTransfer(open.endpointOut, buf, buf.length, TIMEOUT_MS);
            if (n >= 0) return new JSONObject();
        }

        // Control-transfer SET_REPORT carries the report ID in wValue, so the
        // payload itself must not include it.
        int type = feature ? 0x03 : 0x02;
        int value = (type << 8) | (reportId & 0xFF);
        int n = -1;
        for (int attempt = 0; attempt < 3; attempt++) {
            n = open.connection.controlTransfer(
                    0x21, 0x09, value, open.iface.getId(),
                    payload, payload.length, TIMEOUT_MS);
            if (n >= 0) break;
            Thread.sleep(30);
        }
        if (n < 0) {
            throw new IllegalStateException("SET_REPORT failed (reportId=" + reportId + ")");
        }
        return new JSONObject();
    }

    private JSONObject receiveFeatureReport(JSONObject args) throws Exception {
        String id = args.getString("deviceId");
        int reportId = args.optInt("reportId", 0);

        OpenDevice open = openDevices.get(id);
        if (open == null) throw new IllegalStateException("Device is not open: " + id);

        byte[] buffer = new byte[REPORT_LEN];
        int n = -1;
        for (int attempt = 0; attempt < 3; attempt++) {
            n = open.connection.controlTransfer(
                    0xA1, 0x01, (0x03 << 8) | (reportId & 0xFF), open.iface.getId(),
                    buffer, buffer.length, FEATURE_TIMEOUT_MS);
            if (n > 0) break;
            Thread.sleep(20);
        }
        if (n < 0) {
            log("GET_REPORT failed (reportId=" + reportId + ")");
            throw new IllegalStateException("GET_REPORT failed (reportId=" + reportId + ")");
        }
        if (featureReadsLogged < 16) {
            featureReadsLogged++;
            log("GET_FEATURE id=" + reportId + " n=" + n
                    + " data=" + hexHead(buffer, Math.min(n, 16)));
        }

        byte[] data;
        if (reportId == 0) {
            // WebHID returns a fixed-size report; pad short reads to 64 bytes.
            data = new byte[REPORT_LEN];
            System.arraycopy(buffer, 0, data, 0, Math.min(n, REPORT_LEN));
        } else {
            // HID GET_REPORT includes the report ID as the first byte; qmk.top
            // slices it off when reportId != 0.
            data = new byte[n];
            System.arraycopy(buffer, 0, data, 0, n);
        }
        return new JSONObject().put("data", Base64.encodeToString(data, Base64.NO_WRAP));
    }

    private void startInputReportLoop(String deviceId, OpenDevice opened, UsbEndpoint endpoint) {
        Thread t = new Thread(() -> {
            // Interrupt endpoints must be read asynchronously with UsbRequest;
            // bulkTransfer is not valid for them on Android.
            UsbRequest request = new UsbRequest();
            ByteBuffer buffer = ByteBuffer.allocateDirect(REPORT_LEN);
            boolean sawReport = false;
            try {
                if (!request.initialize(opened.connection, endpoint)) {
                    log("Input request init failed");
                    return;
                }
                opened.inputRequest = request;
                while (opened.reading) {
                    buffer.clear();
                    if (!request.queue(buffer)) break;
                    UsbRequest completed = opened.connection.requestWait();
                    if (completed == null) break;
                    if (completed != request) continue;

                    int n = buffer.position();
                    if (n <= 0) continue;
                    buffer.position(0);
                    byte[] raw = new byte[n];
                    buffer.get(raw, 0, n);

                    int reportId;
                    byte[] data;
                    if (opened.reportIdsUsed) {
                        reportId = raw[0] & 0xFF;
                        data = Arrays.copyOfRange(raw, 1, n);
                    } else {
                        reportId = 0;
                        data = raw;
                    }
                    // qmk.top's WebHID listener requires exactly a 64-byte
                    // payload (listenToReports(0, 64)); pad short reports.
                    if (data.length != REPORT_LEN) {
                        data = Arrays.copyOf(data, REPORT_LEN);
                    }
                    if (!sawReport) {
                        sawReport = true;
                        StringBuilder head = new StringBuilder();
                        for (int i = 0; i < Math.min(12, data.length); i++) {
                            head.append(String.format("%02X ", data[i] & 0xFF));
                        }
                        log("First input report: id=" + reportId + ", " + n
                                + " bytes, head=" + head.toString().trim());
                    }
                    String b64 = Base64.encodeToString(data, Base64.NO_WRAP);
                    mainHandler.post(() -> evalJs(
                            "window.__hidDispatchInputReport("
                                    + JSONObject.quote(deviceId) + ","
                                    + reportId + ","
                                    + JSONObject.quote(b64) + ")"));
                }
            } catch (Exception e) {
                if (opened.reading) {
                    log("Input loop error: " + e.getMessage());
                }
            } finally {
                opened.inputRequest = null;
                try {
                    request.close();
                } catch (Exception ignored) {
                }
                if (opened.reading) {
                    // The interrupt pipe stopped unexpectedly (for example the
                    // keyboard was unplugged).  Release the stale connection so
                    // the next Connect can open the device again.
                    synchronized (ioLock) {
                        if (openDevices.get(deviceId) == opened) {
                            openDevices.remove(deviceId);
                            opened.reading = false;
                            try {
                                opened.connection.releaseInterface(opened.iface);
                            } catch (Exception ignored) {
                            }
                            try {
                                opened.connection.close();
                            } catch (Exception ignored) {
                            }
                            log("Input loop ended; released " + deviceId);
                        }
                    }
                    mainHandler.post(() -> evalJs(
                            "window.__hidDispatchDisconnect("
                                    + JSONObject.quote(deviceId) + ")"));
                }
            }
        }, "hid-input");
        t.start();
    }

    // ------------------------------------------------------------------
    // Lifecycle / helpers
    // ------------------------------------------------------------------

    /** Closes a device that was physically unplugged and notifies the page. */
    public void onUsbDetached(UsbDevice device) {
        if (device == null) return;
        String id = device.getDeviceName();
        synchronized (ioLock) {
            OpenDevice open = openDevices.remove(id);
            if (open != null) {
                open.stopReading();
                try {
                    open.connection.releaseInterface(open.iface);
                } catch (Exception ignored) {
                }
                try {
                    open.connection.close();
                } catch (Exception ignored) {
                }
                log("USB detached: " + id);
            }
        }
        mainHandler.post(() -> evalJs(
                "window.__hidDispatchDisconnect(" + JSONObject.quote(id) + ")"));
    }

    public void teardown() {
        closed = true;
        synchronized (ioLock) {
            if (receiverRegistered) {
                try {
                    activity.unregisterReceiver(permissionReceiver);
                } catch (Exception ignored) {
                }
                receiverRegistered = false;
            }
            for (OpenDevice open : new ArrayList<>(openDevices.values())) {
                open.stopReading();
                try {
                    open.connection.releaseInterface(open.iface);
                } catch (Exception ignored) {
                }
                try {
                    open.connection.close();
                } catch (Exception ignored) {
                }
            }
            openDevices.clear();
        }
    }

    private void log(String message) {
        Log.d(TAG, message);
        if (status != null) status.log(message);
    }

    private static String hex(int value) {
        return String.format("0x%04X", value);
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
