package com.attackshark.gearhub;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/**
 * Asks Android for USB host access to the X6 receiver/cable.  The X68 HE
 * keyboard permission is requested by WebHidBridge when qmk.top opens the
 * keyboard, so this helper only handles the second device and the two prompts
 * do not race each other.
 */
public class UsbPermissionRequester {

    private static final String TAG = "GearHubUsb";
    private static final long DENIED_RETRY_MS = 30000L;

    private final Activity activity;
    private final UsbManager usbManager;
    private final String actionPermission;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayDeque<UsbDevice> queue = new ArrayDeque<>();
    private final Map<String, Long> deniedAt = new HashMap<>();

    private final BroadcastReceiver permissionReceiver;
    private boolean receiverRegistered = false;
    private boolean inFlight = false;
    private boolean destroyed = false;
    private int requestCode = 0;
    private String currentDeviceName;

    public UsbPermissionRequester(Activity activity) {
        this.activity = activity;
        this.usbManager = (UsbManager) activity.getSystemService(Context.USB_SERVICE);
        this.actionPermission =
                activity.getPackageName() + ".USB_PERMISSION_BOTH";

        this.permissionReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!actionPermission.equals(intent.getAction())) return;

                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                boolean granted =
                        intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                Log.d(TAG, "USB permission " + (granted ? "granted" : "denied")
                        + (device == null ? "" : " for " + device.getDeviceName()));

                if (device != null) {
                    if (granted) {
                        deniedAt.remove(device.getDeviceName());
                    } else {
                        deniedAt.put(device.getDeviceName(), System.currentTimeMillis());
                    }
                }

                inFlight = false;
                currentDeviceName = null;
                handler.postDelayed(UsbPermissionRequester.this::requestNext, 300L);
            }
        };

        try {
            IntentFilter filter = new IntentFilter(actionPermission);
            if (Build.VERSION.SDK_INT >= 33) {
                activity.registerReceiver(permissionReceiver, filter,
                        Context.RECEIVER_NOT_EXPORTED);
            } else {
                activity.registerReceiver(permissionReceiver, filter);
            }
            receiverRegistered = true;
        } catch (Exception e) {
            Log.w(TAG, "permission receiver failed: " + e.getMessage());
        }
    }

    /** Requests access for every attached keyboard or X6 that is not granted. */
    public void ensurePermissions() {
        if (destroyed) return;
        enqueueCurrentDevices();
        requestNext();
    }

    private void enqueueCurrentDevices() {
        if (usbManager == null) return;
        try {
            for (UsbDevice device : usbManager.getDeviceList().values()) {
                if (!isSupported(device)) continue;
                if (usbManager.hasPermission(device)) continue;

                String name = device.getDeviceName();
                Long denied = deniedAt.get(name);
                if (denied != null
                        && System.currentTimeMillis() - denied < DENIED_RETRY_MS) {
                    continue;
                }
                if (inFlight && name.equals(currentDeviceName)) continue;
                if (contains(name)) continue;

                queue.addLast(device);
            }
        } catch (Exception e) {
            Log.w(TAG, "device scan failed: " + e.getMessage());
        }
    }

    private boolean contains(String deviceName) {
        for (UsbDevice queued : queue) {
            if (queued.getDeviceName().equals(deviceName)) return true;
        }
        return false;
    }

    private void requestNext() {
        if (destroyed || inFlight) return;

        while (!queue.isEmpty()) {
            UsbDevice queued = queue.pollFirst();
            UsbDevice device = findCurrent(queued.getDeviceName());
            if (device == null || !isSupported(device)) continue;
            if (usbManager.hasPermission(device)) continue;

            inFlight = true;
            currentDeviceName = device.getDeviceName();
            try {
                Intent intent = new Intent(actionPermission)
                        .setPackage(activity.getPackageName());
                int flags = Build.VERSION.SDK_INT >= 31
                        ? PendingIntent.FLAG_MUTABLE : 0;
                PendingIntent pendingIntent = PendingIntent.getBroadcast(
                        activity, ++requestCode, intent, flags);
                Log.d(TAG, "requesting access for " + currentDeviceName);
                usbManager.requestPermission(device, pendingIntent);
                return;
            } catch (Exception e) {
                Log.w(TAG, "permission request failed: " + e.getMessage());
                inFlight = false;
                currentDeviceName = null;
            }
        }
    }

    private UsbDevice findCurrent(String deviceName) {
        if (usbManager == null) return null;
        try {
            for (UsbDevice device : usbManager.getDeviceList().values()) {
                if (device.getDeviceName().equals(deviceName)) return device;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static boolean isSupported(UsbDevice device) {
        return X6Bridge.isX6(device);
    }

    public void destroy() {
        if (destroyed) return;
        destroyed = true;
        handler.removeCallbacksAndMessages(null);
        queue.clear();
        if (receiverRegistered) {
            try {
                activity.unregisterReceiver(permissionReceiver);
            } catch (Exception ignored) {
            }
            receiverRegistered = false;
        }
    }
}
