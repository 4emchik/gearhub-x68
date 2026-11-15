// WebHID polyfill for Android WebView, backed by the native AndroidHID bridge.
// Based on the MIT-licensed WebHID Bridge by Halil156, adapted for the
// Attack Shark X68 HE and qmk.top / GearHub.
(function () {
  if (navigator.hid && navigator.hid.__isAndroidBridge) return;

  // qmk.top can switch to its Windows-only native (IOT/gRPC) transport via
  // this flag.  Android must always use the WebHID bridge.
  try { localStorage.setItem('IOTSwitch', 'false'); } catch (e) { }

  var TARGET_USAGE_PAGE = 65535; // 0xFFFF
  var TARGET_USAGE = 2;

  // Simple EventTarget polyfill for older WebViews
  class BridgeEventTarget {
    constructor() {
      this._listeners = {};
    }
    addEventListener(type, listener) {
      if (!this._listeners[type]) this._listeners[type] = [];
      this._listeners[type].push(listener);
    }
    removeEventListener(type, listener) {
      if (!this._listeners[type]) return;
      this._listeners[type] = this._listeners[type].filter(function (l) { return l !== listener; });
    }
    dispatchEvent(event) {
      var type = event.type;
      var listeners = this._listeners[type] || [];
      listeners.forEach(function (l) {
        if (typeof l === 'function') l.call(this, event);
        else if (l && typeof l.handleEvent === 'function') l.handleEvent(event);
      }, this);
      if (this['on' + type]) this['on' + type](event);
      return true;
    }
  }

  var ETarget = window.EventTarget || BridgeEventTarget;

  var nextCallId = 1;
  var pending = {};
  var openDevices = {};

  function callNative(method, argsObj) {
    return new Promise(function (resolve, reject) {
      var callId = nextCallId++;
      pending[callId] = { resolve: resolve, reject: reject };
      if (window.AndroidHID) {
        AndroidHID.call(method, JSON.stringify(argsObj || {}), callId);
      } else {
        reject(new Error("AndroidHID interface not found"));
      }
    });
  }

  function uiLog(message) {
    try {
      if (window.AndroidHID && AndroidHID.uiLog) AndroidHID.uiLog(String(message));
    } catch (e) { }
  }

  window.__hidResolve = function (callId, jsonResult) {
    var p = pending[callId];
    if (!p) return;
    delete pending[callId];
    p.resolve(jsonResult ? JSON.parse(jsonResult) : undefined);
  };
  window.__hidReject = function (callId, message) {
    var p = pending[callId];
    if (!p) return;
    delete pending[callId];
    p.reject(new Error(message));
  };

  window.__hidDispatchInputReport = function (deviceId, reportId, base64Data) {
    var dev = openDevices[deviceId];
    if (!dev) return;
    var bytes = base64ToUint8Array(base64Data);
    var event = new CustomEvent('inputreport', { detail: { reportId: reportId, data: new DataView(bytes.buffer), device: dev } });
    event.reportId = reportId;
    event.data = new DataView(bytes.buffer);
    event.device = dev;
    dev.dispatchEvent(event);
  };

  window.__hidDispatchDisconnect = function (deviceId) {
    var dev = openDevices[deviceId];
    delete openDevices[deviceId];
    if (!dev) return;
    dev.opened = false;
    try {
      var onDevice = new CustomEvent('disconnect', { detail: { device: dev } });
      onDevice.device = dev;
      dev.dispatchEvent(onDevice);
    } catch (e) { }
    try {
      var onHid = new CustomEvent('disconnect', { detail: { device: dev } });
      onHid.device = dev;
      (navigator.hid || hidInstance).dispatchEvent(onHid);
    } catch (e) { }
  };

  function base64ToUint8Array(b64) {
    var binary = atob(b64 || '');
    var bytes = new Uint8Array(binary.length);
    for (var i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
    return bytes;
  }
  function uint8ArrayToBase64(bytes) {
    var binary = '';
    for (var i = 0; i < bytes.length; i++) binary += String.fromCharCode(bytes[i]);
    return btoa(binary);
  }
  function toUint8Array(data) {
    if (data instanceof Uint8Array) return data;
    if (data instanceof ArrayBuffer) return new Uint8Array(data);
    if (data && data.buffer instanceof ArrayBuffer) return new Uint8Array(data.buffer, data.byteOffset || 0, data.byteLength);
    return new Uint8Array(data);
  }

  // Keep the real USB identity. qmk.top has an exact profile for the X68HE
  // (VID 12625 / PID 20525, type "keyboard"), which uses feature reports and
  // GET_REPORT.  Only add the vendor collection if the descriptor omitted it.
  function normalizeInfo(info) {
    if (!info) return info;
    var out = {};
    for (var k in info) if (Object.prototype.hasOwnProperty.call(info, k)) out[k] = info[k];
    out.productName = info.productName || 'Attack Shark X68 HE';

    var cols = Array.isArray(info.collections) ? info.collections.slice() : [];
    // qmk.top chooses the device profile by finding a filter whose
    // usage/usagePage appears in device.collections.  The X68HE profile needs
    // usagePage 0xFFFF / usage 2.
    var hasExactVendor = cols.some(function (c) {
      return c && c.usagePage === TARGET_USAGE_PAGE && c.usage === TARGET_USAGE;
    });
    if (!hasExactVendor) {
      cols.push({
        usagePage: TARGET_USAGE_PAGE,
        usage: TARGET_USAGE,
        type: 2,
        children: [],
        inputReports: [],
        outputReports: [],
        featureReports: []
      });
    }
    out.collections = cols;
    return out;
  }

  class HIDDeviceShim extends ETarget {
    constructor(info) {
      super();
      this.__id = info.id;
      this.vendorId = info.vendorId;
      this.productId = info.productId;
      this.productName = info.productName || '';
      this.collections = info.collections || [];
      this.opened = false;
      openDevices[this.__id] = this;
    }
    async open() {
      await callNative('open', { deviceId: this.__id });
      this.opened = true;
    }
    async close() {
      await callNative('close', { deviceId: this.__id });
      this.opened = false;
    }
    async sendReport(reportId, data) {
      var bytes = toUint8Array(data);
      await callNative('sendReport', { deviceId: this.__id, reportId: reportId, data: uint8ArrayToBase64(bytes) });
    }
    async sendFeatureReport(reportId, data) {
      var bytes = toUint8Array(data);
      await callNative('sendFeatureReport', { deviceId: this.__id, reportId: reportId, data: uint8ArrayToBase64(bytes) });
    }
    async receiveFeatureReport(reportId) {
      var res = await callNative('receiveFeatureReport', { deviceId: this.__id, reportId: reportId });
      var bytes = base64ToUint8Array(res.data);
      return new DataView(bytes.buffer);
    }
    async forget() {
      await callNative('close', { deviceId: this.__id });
      delete openDevices[this.__id];
    }
  }

  class HIDShim extends ETarget {
    constructor() {
      super();
      this.__isAndroidBridge = true;
    }
    async requestDevice(options) {
      var res;
      try {
        res = await callNative('requestDevice', { filters: (options && options.filters) || [] });
      } catch (err) {
        uiLog('USB connect failed: ' + (err && err.message ? err.message : err));
        throw err;
      }
      var list = (Array.isArray(res) ? res : [res]).map(normalizeInfo);
      var devices = list.map(function (info) {
        return openDevices[info.id] || new HIDDeviceShim(info);
      });
      uiLog('USB device granted: ' + devices.length);
      // qmk.top listens for navigator.hid "connect" events; announce the
      // device so it can attach without a second user click.  Delay a little
      // so a direct requestDevice() caller can register the device first and
      // avoid a duplicate handshake.
      devices.forEach(function (d) {
        setTimeout(function () {
          try {
            var event = new CustomEvent('connect', { detail: { device: d } });
            event.device = d;
            (navigator.hid || hidInstance).dispatchEvent(event);
          } catch (e) {
            try { hidInstance.dispatchEvent({ type: 'connect', device: d }); } catch (e2) { }
          }
        }, 250);
      });
      return devices;
    }
    async getDevices() {
      var res = await callNative('getDevices', {});
      var list = (Array.isArray(res) ? res : []).map(normalizeInfo);
      return list.map(function (info) {
        return openDevices[info.id] || new HIDDeviceShim(info);
      });
    }
  }

  var hidInstance = new HIDShim();

  if (!navigator.hid) {
    Object.defineProperty(navigator, 'hid', {
      value: hidInstance,
      configurable: true,
      enumerable: true
    });
  } else {
    try {
      navigator.hid.requestDevice = hidInstance.requestDevice.bind(hidInstance);
      navigator.hid.getDevices = hidInstance.getDevices.bind(hidInstance);
      navigator.hid.__isAndroidBridge = true;
    } catch (e) { }
  }

  if (!navigator.usb) {
    navigator.usb = new ETarget();
    navigator.usb.getDevices = async function () { return []; };
  }

  console.log("WebHID Bridge Shim Injected Successfully");
})();
