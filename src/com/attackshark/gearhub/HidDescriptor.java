package com.attackshark.gearhub;

import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbInterface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads a USB HID report descriptor and turns it into objects shaped roughly
 * like WebHID's HIDDevice.collections entries.
 *
 * Ported from the MIT-licensed WebHID Bridge project by Halil156.
 */
public final class HidDescriptor {

    private static final int GET_DESCRIPTOR = 0x06;
    private static final int DESCRIPTOR_TYPE_REPORT = 0x22;
    private static final int TIMEOUT_MS = 5000;

    private HidDescriptor() {
    }

    public static byte[] fetchRaw(UsbDeviceConnection connection, UsbInterface iface) {
        byte[] buffer = new byte[4096];
        int n = connection.controlTransfer(
                0x81, GET_DESCRIPTOR, DESCRIPTOR_TYPE_REPORT << 8,
                iface.getId(), buffer, buffer.length, TIMEOUT_MS);
        if (n <= 0) return null;
        byte[] out = new byte[n];
        System.arraycopy(buffer, 0, out, 0, n);
        return out;
    }

    public static JSONArray parse(byte[] bytes) {
        JSONArray root = new JSONArray();
        ArrayList<JSONObject> stack = new ArrayList<>();

        int usagePage = 0;
        int logicalMin = 0;
        int logicalMax = 0;
        int reportSize = 0;
        int reportCount = 0;
        int reportId = 0;
        ArrayList<int[]> globalStack = new ArrayList<>();

        ArrayList<Integer> usages = new ArrayList<>();
        int usageMin = -1;
        int usageMax = -1;

        int i = 0;
        while (i < bytes.length) {
            int prefix = bytes[i] & 0xFF;
            if (prefix == 0xFE) break; // long item, not used here
            int size = (prefix & 0x03) == 3 ? 4 : (prefix & 0x03);
            int type = (prefix >> 2) & 0x03;
            int tag = (prefix >> 4) & 0x0F;
            i++;
            if (i + size > bytes.length) break;
            int data = 0;
            for (int b = 0; b < size; b++) {
                data |= (bytes[i + b] & 0xFF) << (8 * b);
            }
            int signedData = size == 1 ? (byte) data : (size == 2 ? (short) data : data);
            i += size;

            switch (type) {
                case 1: // Global
                    switch (tag) {
                        case 0x0: usagePage = data; break;
                        case 0x1: logicalMin = signedData; break;
                        case 0x2: logicalMax = signedData; break;
                        case 0x7: reportSize = data; break;
                        case 0x8: reportId = data; break;
                        case 0x9: reportCount = data; break;
                        case 0xA:
                            globalStack.add(new int[]{
                                    usagePage, logicalMin, logicalMax,
                                    reportSize, reportCount, reportId});
                            break;
                        case 0xB:
                            if (!globalStack.isEmpty()) {
                                int[] g = globalStack.remove(globalStack.size() - 1);
                                usagePage = g[0];
                                logicalMin = g[1];
                                logicalMax = g[2];
                                reportSize = g[3];
                                reportCount = g[4];
                                reportId = g[5];
                            }
                            break;
                    }
                    break;

                case 2: // Local
                    switch (tag) {
                        case 0x0: usages.add(data); break;
                        case 0x1: usageMin = data; break;
                        case 0x2: usageMax = data; break;
                    }
                    break;

                case 0: // Main
                    switch (tag) {
                        case 0x8: // Input
                            addReportItem(stack, "inputReports", usagePage, logicalMin,
                                    logicalMax, reportSize, reportCount, reportId,
                                    usages, usageMin, usageMax);
                            clearLocal(usages);
                            usageMin = -1;
                            usageMax = -1;
                            break;
                        case 0x9: // Output
                            addReportItem(stack, "outputReports", usagePage, logicalMin,
                                    logicalMax, reportSize, reportCount, reportId,
                                    usages, usageMin, usageMax);
                            clearLocal(usages);
                            usageMin = -1;
                            usageMax = -1;
                            break;
                        case 0xB: // Feature
                            addReportItem(stack, "featureReports", usagePage, logicalMin,
                                    logicalMax, reportSize, reportCount, reportId,
                                    usages, usageMin, usageMax);
                            clearLocal(usages);
                            usageMin = -1;
                            usageMax = -1;
                            break;
                        case 0xA: { // Collection
                            int collectionUsage = !usages.isEmpty()
                                    ? usages.get(0)
                                    : (usageMin >= 0 ? usageMin : 0);
                            JSONObject col = new JSONObject();
                            try {
                                col.put("usagePage", usagePage);
                                col.put("usage", collectionUsage);
                                col.put("type", data);
                                col.put("children", new JSONArray());
                                col.put("inputReports", new JSONArray());
                                col.put("outputReports", new JSONArray());
                                col.put("featureReports", new JSONArray());
                                if (stack.isEmpty()) {
                                    root.put(col);
                                } else {
                                    stack.get(stack.size() - 1)
                                            .getJSONArray("children").put(col);
                                }
                            } catch (Exception ignored) {
                            }
                            stack.add(col);
                            clearLocal(usages);
                            usageMin = -1;
                            usageMax = -1;
                            break;
                        }
                        case 0xC: // End Collection
                            if (!stack.isEmpty()) stack.remove(stack.size() - 1);
                            clearLocal(usages);
                            usageMin = -1;
                            usageMax = -1;
                            break;
                    }
                    break;
            }
        }
        return root;
    }

    /** True if a top-level or nested collection matches usagePage:usage. */
    public static boolean hasVendorCollection(JSONArray collections, int usagePage, int usage) {
        if (collections == null) return false;
        for (int i = 0; i < collections.length(); i++) {
            JSONObject c = collections.optJSONObject(i);
            if (c != null && collectionMatches(c, usagePage, usage)) return true;
        }
        return false;
    }

    private static boolean collectionMatches(JSONObject c, int usagePage, int usage) {
        if (c.optInt("usagePage", -1) == usagePage && c.optInt("usage", -1) == usage) {
            return true;
        }
        JSONArray children = c.optJSONArray("children");
        if (children != null) {
            for (int i = 0; i < children.length(); i++) {
                JSONObject child = children.optJSONObject(i);
                if (child != null && collectionMatches(child, usagePage, usage)) return true;
            }
        }
        return false;
    }

    /** True if any report in the descriptor declares a nonzero report ID. */
    public static boolean usesReportIds(JSONArray collections) {
        if (collections == null) return false;
        for (int i = 0; i < collections.length(); i++) {
            JSONObject c = collections.optJSONObject(i);
            if (c != null && collectionUsesReportIds(c)) return true;
        }
        return false;
    }

    private static boolean collectionUsesReportIds(JSONObject c) {
        String[] keys = {"inputReports", "outputReports", "featureReports"};
        for (String key : keys) {
            JSONArray reports = c.optJSONArray(key);
            if (reports == null) continue;
            for (int i = 0; i < reports.length(); i++) {
                JSONObject report = reports.optJSONObject(i);
                if (report != null && report.optInt("reportId", 0) != 0) return true;
            }
        }
        JSONArray children = c.optJSONArray("children");
        if (children != null) {
            for (int i = 0; i < children.length(); i++) {
                JSONObject child = children.optJSONObject(i);
                if (child != null && collectionUsesReportIds(child)) return true;
            }
        }
        return false;
    }

    private static void clearLocal(List<Integer> usages) {
        usages.clear();
    }

    private static void addReportItem(
            ArrayList<JSONObject> stack, String key,
            int usagePage, int logicalMin, int logicalMax,
            int reportSize, int reportCount, int reportId,
            List<Integer> usages, int usageMin, int usageMax) {
        if (stack.isEmpty()) return;
        try {
            JSONObject col = stack.get(stack.size() - 1);
            JSONArray reports = col.getJSONArray(key);
            JSONObject report = null;
            for (int i = 0; i < reports.length(); i++) {
                JSONObject r = reports.getJSONObject(i);
                if (r.getInt("reportId") == reportId) {
                    report = r;
                    break;
                }
            }
            if (report == null) {
                report = new JSONObject();
                report.put("reportId", reportId);
                report.put("items", new JSONArray());
                reports.put(report);
            }
            JSONObject item = new JSONObject();
            item.put("usagePage", usagePage);
            if (!usages.isEmpty()) {
                JSONArray u = new JSONArray();
                for (int v : usages) u.put(v);
                item.put("usages", u);
            } else if (usageMin >= 0 && usageMax >= 0) {
                item.put("usageMinimum", usageMin);
                item.put("usageMaximum", usageMax);
            }
            item.put("reportSize", reportSize);
            item.put("reportCount", reportCount);
            item.put("logicalMinimum", logicalMin);
            item.put("logicalMaximum", logicalMax);
            report.getJSONArray("items").put(item);
        } catch (Exception ignored) {
        }
    }
}
