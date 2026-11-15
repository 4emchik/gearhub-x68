// Fake WebSocket for the official Attack Shark X6 web configurator.
//
// The site creates `new WebSocket("ws://localhost:10086")` and expects the
// Windows helper to answer with a two-byte version plus device-presence
// messages.  On Android there is no helper, so this shim replaces that socket
// and forwards the hex messages to X6Bridge over the AndroidX6 interface.
//
// Message formats (from the Windows helper):
//   version           03 00          (report 3.0 => page skips read commands)
//   device present    81 01 00 85 40 <batteryStatus> <batteryLevel>
//   device update     81 01 00 85 41 <batteryStatus> <batteryLevel>
//   device removed    81 00 85
//   80/81 is wired/wireless; 0x85 (133) is the X6 device ID.
(function () {
  if (window.__x6ShimInstalled) return;
  window.__x6ShimInstalled = true;

  var NativeWebSocket = window.WebSocket;
  var HELPER_URL = /^wss?:\/\/localhost:10086\/?$/i;
  var CONNECTING = 0;
  var OPEN = 1;
  var CLOSING = 2;
  var CLOSED = 3;
  var activeSocket = null;

  function log(message) {
    try {
      if (window.AndroidX6 && window.AndroidX6.uiLog) window.AndroidX6.uiLog(String(message));
    } catch (e) { }
  }

  function hex2(value) {
    value = value & 0xff;
    return (value < 16 ? '0' : '') + value.toString(16);
  }

  function presence(wireless, status, level, update) {
    var head = (wireless ? '81' : '80') + '010085' + (update ? '41' : '40');
    return head + hex2(status) + hex2(level);
  }

  function toHex(bytes) {
    var out = '';
    for (var i = 0; i < bytes.length; i++) out += hex2(bytes[i]);
    return out;
  }

  function makeEvent(type, data) {
    return {
      type: type,
      data: data,
      target: null,
      currentTarget: null,
      origin: '',
      code: 1000,
      reason: '',
      wasClean: true
    };
  }

  function FakeWebSocket(url, protocols) {
    if (!HELPER_URL.test(String(url))) {
      return new NativeWebSocket(url, protocols);
    }

    this.url = String(url);
    this.readyState = CONNECTING;
    this.protocol = '';
    this.extensions = '';
    this.bufferedAmount = 0;
    this.binaryType = 'blob';
    this.onopen = null;
    this.onmessage = null;
    this.onerror = null;
    this.onclose = null;
    this._listeners = {};
    activeSocket = this;

    var self = this;
    setTimeout(function () { self.__open(); }, 0);
  }

  FakeWebSocket.prototype.addEventListener = function (type, listener) {
    if (!listener) return;
    if (!this._listeners[type]) this._listeners[type] = [];
    this._listeners[type].push(listener);
  };

  FakeWebSocket.prototype.removeEventListener = function (type, listener) {
    var list = this._listeners[type];
    if (!list) return;
    this._listeners[type] = list.filter(function (item) { return item !== listener; });
  };

  FakeWebSocket.prototype.dispatchEvent = function (event) {
    event.target = this;
    event.currentTarget = this;
    var list = this._listeners[event.type] || [];
    for (var i = 0; i < list.length; i++) {
      try {
        if (typeof list[i] === 'function') list[i].call(this, event);
        else if (list[i] && typeof list[i].handleEvent === 'function') {
          list[i].handleEvent(event);
        }
      } catch (e) {
        log('X6 socket listener error: ' + e);
      }
    }
    var handler = this['on' + event.type];
    if (typeof handler === 'function') {
      try {
        handler.call(this, event);
      } catch (e) {
        log('X6 socket handler error: ' + e);
      }
    }
    return true;
  };

  FakeWebSocket.prototype.__open = function () {
    if (this.readyState !== CONNECTING) return;
    this.readyState = OPEN;
    this.dispatchEvent(makeEvent('open'));

    // Report helper 3.0.  The page treats <= 3.0 as "skip device reads",
    // which avoids the unsupported GET_REPORT commands.  Its driver-update
    // tip is hidden separately.
    this.__message('0300');

    try {
      if (window.AndroidX6 && window.AndroidX6.getState) {
        var state = JSON.parse(window.AndroidX6.getState());
        if (state && state.connected) {
          this.__message(presence(
                  !!state.wireless,
                  Number(state.batteryStatus) || 2,
                  Number(state.batteryLevel) || 100,
                  false));
        }
      }
    } catch (e) {
      log('X6 state unavailable: ' + e);
    }
  };

  FakeWebSocket.prototype.__message = function (hex) {
    if (this.readyState !== OPEN) return;
    this.dispatchEvent(makeEvent('message', hex));
  };

  FakeWebSocket.prototype.send = function (data) {
    if (this.readyState !== OPEN) {
      log('X6 socket send while not open');
      return;
    }
    var hex;
    if (typeof data === 'string') {
      hex = data;
    } else {
      try {
        hex = toHex(new Uint8Array(data));
      } catch (e) {
        log('X6 socket cannot encode message: ' + e);
        return;
      }
    }
    try {
      if (window.AndroidX6 && window.AndroidX6.send) window.AndroidX6.send(hex);
      else log('AndroidX6 bridge is missing');
    } catch (e) {
      log('X6 socket send failed: ' + e);
    }
  };

  FakeWebSocket.prototype.close = function (code, reason) {
    if (this.readyState === CLOSED) return;
    this.readyState = CLOSED;
    if (activeSocket === this) activeSocket = null;
    var event = makeEvent('close');
    event.code = code || 1000;
    event.reason = reason || '';
    event.wasClean = true;
    this.dispatchEvent(event);
  };

  FakeWebSocket.CONNECTING = CONNECTING;
  FakeWebSocket.OPEN = OPEN;
  FakeWebSocket.CLOSING = CLOSING;
  FakeWebSocket.CLOSED = CLOSED;
  FakeWebSocket.prototype.CONNECTING = CONNECTING;
  FakeWebSocket.prototype.OPEN = OPEN;
  FakeWebSocket.prototype.CLOSING = CLOSING;
  FakeWebSocket.prototype.CLOSED = CLOSED;

  window.WebSocket = FakeWebSocket;

  // Events from X6Bridge.
  window.__x6Report = function (hex) {
    if (activeSocket) activeSocket.__message(hex);
  };
  window.__x6Device = function (wireless, status, level, update) {
    if (activeSocket) {
      activeSocket.__message(presence(
              !!wireless,
              Number(status) || 2,
              Number(level) || 100,
              !!update));
    }
  };
  window.__x6Disconnect = function () {
    if (activeSocket) activeSocket.__message('810085');
  };

  // The page compares the helper version with 3.5 and shows a full-page
  // "install the driver" tip when they differ.  Our helper deliberately
  // reports 3.0, so hide that component (scoped id data-v-2467292e).
  function hideTipNode(node) {
    if (!node || node.nodeType !== 1) return;
    try {
      if (node.hasAttribute && node.hasAttribute('data-v-2467292e')) {
        node.style.setProperty('display', 'none', 'important');
      }
      if (node.querySelectorAll) {
        var marked = node.querySelectorAll('[data-v-2467292e]');
        for (var i = 0; i < marked.length; i++) {
          marked[i].style.setProperty('display', 'none', 'important');
        }
      }
    } catch (e) { }
  }

  function sweepTip() {
    try {
      var marked = document.querySelectorAll('[data-v-2467292e]');
      for (var i = 0; i < marked.length; i++) {
        marked[i].style.setProperty('display', 'none', 'important');
      }
      // Future-proof fallback: hide only containers that carry the driver
      // tip text, never the normal configurator.
      var containers = document.querySelectorAll('.app-container');
      for (var j = 0; j < containers.length; j++) {
        var text = containers[j].textContent || '';
        if (text.indexOf('驱动支持更新') >= 0
            || text.indexOf('需安装驱动所需文件') >= 0
            || text.indexOf('Please download and install the driver') >= 0
            || text.indexOf('necessary files for the driver') >= 0) {
          containers[j].style.setProperty('display', 'none', 'important');
        }
      }
    } catch (e) { }
  }

  function installTipHider() {
    try {
      var style = document.createElement('style');
      style.id = '__x6_tip_css';
      style.textContent = '[data-v-2467292e]{display:none!important}';
      (document.head || document.documentElement).appendChild(style);
      sweepTip();
      if (window.MutationObserver && document.documentElement) {
        var sweepTimer = null;
        var observer = new MutationObserver(function (mutations) {
          for (var i = 0; i < mutations.length; i++) {
            var added = mutations[i].addedNodes;
            for (var j = 0; j < added.length; j++) hideTipNode(added[j]);
          }
          if (!sweepTimer) {
            sweepTimer = setTimeout(function () {
              sweepTimer = null;
              sweepTip();
            }, 200);
          }
        });
        observer.observe(document.documentElement, { childList: true, subtree: true });
      }
    } catch (e) { }
  }

  if (document.head) installTipHider();
  else document.addEventListener('DOMContentLoaded', installTipHider);

  console.log('X6 WebSocket bridge shim installed');
})();
