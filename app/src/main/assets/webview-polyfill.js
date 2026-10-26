/*
 * WebView 兼容补丁。
 *
 * DSH 前端按较新的 Chromium 编写，而本机 WebView（Chromium 114）缺其中一部分
 * 标准 API，缺失时会直接抛 TypeError 导致界面起不来。这里按标准语义补齐，
 * 且全部是幂等的：浏览器已实现就原样保留，不做覆盖。
 */
(function () {
  'use strict';

  // ── 启动期错误上报 ─────────────────────────────────────────────────
  //
  // DSH 官方前端的入口 bundle 用了较新的**语法**（如 class static block，
  // Chromium 94+）。语法补不了 —— 下面那些补丁只能补**运行时 API**（缺函数时挂一个）。
  // 一旦命中语法门槛，整份 chunk 在**解析阶段**就失败、一行都不执行：
  // HTML 与 CSS 都正常（页面是官方前端自己的白底启动页），`#root` 却永远是空的，
  // 宿主侧什么都看不到，用户只看到白屏。
  //
  // 真机现象（用户反馈的 45 秒录屏，vivo Y52s / Android 10）：
  //   `onPageFinished` 触发了、启动层走到了「正在准备界面…」、页面底色也是官方白，
  //   但状态栏全程停在主题里的深色 —— 说明连插件的 `onStatus` 都没上报过，
  //   即页面里的 JS 一行都没跑起来。12 秒后启动层被兜底收起，露出白屏。
  //
  // 所以这里把错误抓出来交给原生显示（`DSHAndroid.reportWebError`），
  // 让「白屏」变成「白屏 + 原因」—— 否则这类失败在设备上完全无声。
  //
  // ⚠️ 必须 `capture: true`：资源加载失败（script/link 404）**不冒泡**，
  //   只有捕获阶段能拿到，而它的表现与语法错误一模一样（都是页面起不来）。
  // ⚠️ 只报第一条：启动失败时后续错误多半是它的连锁反应，第一条才是原因。
  (function () {
    var reported = false;

    function report(text) {
      if (reported) return;
      reported = true;
      try {
        var bridge = window.DSHAndroid;
        if (bridge && typeof bridge.reportWebError === 'function') {
          bridge.reportWebError(String(text).slice(0, 300));
        }
      } catch (e) {
        /* 桥不可用（桌面端）时只留在控制台 */
      }
      try {
        console.error('[dsh-webview] ' + text);
      } catch (e) {
        /* 忽略 */
      }
    }

    window.addEventListener(
      'error',
      function (event) {
        var target = event.target;
        // 资源加载失败：这类 event 上没有 message，只有 target
        if (target && target !== window && target.tagName) {
          report('资源加载失败: ' + (target.src || target.href || target.tagName));
          return;
        }
        report(
          (event.message || (event.error && event.error.message) || '脚本错误') +
            (event.filename ? ' @' + event.filename + ':' + event.lineno : '')
        );
      },
      true
    );

    window.addEventListener('unhandledrejection', function (event) {
      var reason = event.reason;
      report('未处理的 Promise 拒绝: ' + ((reason && reason.message) || String(reason)));
    });
  })();

  // ── 小工具：造一个 AbortError / TimeoutError 风格的 DOMException ────────
  //
  // 规范里 abort 的默认 reason 是 `DOMException(name='AbortError')`。
  // 老内核可能没有 DOMException 构造器（或它不可 new），退化成带 name 的 Error ——
  // 关键是 `err.name`，上层普遍按 name 判定，而不是 instanceof。
  function abortError(name, message) {
    var n = name || 'AbortError';
    var m = message || 'This operation was aborted';
    try {
      return new DOMException(m, n);
    } catch (e) {
      var err = new Error(m);
      err.name = n;
      return err;
    }
  }

  // Chromium 119：Promise.withResolvers
  if (typeof Promise.withResolvers !== 'function') {
    Promise.withResolvers = function () {
      var resolve, reject;
      var promise = new Promise(function (res, rej) {
        resolve = res;
        reject = rej;
      });
      return { promise: promise, resolve: resolve, reject: reject };
    };
  }

  // Chromium 100：AbortSignal.prototype.throwIfAborted
  //
  // ⚠️ **这是线上真实踩到的缺口**（粉丝截图：点「选择工作区目录」弹
  //    `client api: directoryPicker/list failed: signal.throwIfAborted is not a function`）。
  //    12 个插件 bundle 在用，其中就有目录选择器的客户端。
  //
  // ⚠️ 它当初被漏掉的原因值得记一笔：`AbortSignal.any`（116）被人工收进了清单，
  //    而同一个接口的实例方法 `throwIfAborted` 没被收 —— 人工名单按「接口名」列，
  //    漏的就是「同一个接口的不同成员」。所以现在改成从产物反向扫描
  //    （tools/audit-runtime-apis.mjs），不再靠人脑枚举。
  if (typeof AbortSignal !== 'undefined' && typeof AbortSignal.prototype.throwIfAborted !== 'function') {
    AbortSignal.prototype.throwIfAborted = function () {
      if (this.aborted) {
        // 规范：aborted 为真时抛出 reason（缺省是一个 DOMException AbortError）
        throw this.reason !== undefined ? this.reason : abortError();
      }
    };
  }

  // Chromium 103：AbortSignal.timeout
  if (typeof AbortSignal !== 'undefined' && typeof AbortSignal.timeout !== 'function') {
    AbortSignal.timeout = function (ms) {
      var controller = new AbortController();
      var timer = setTimeout(function () {
        controller.abort(abortError('TimeoutError', 'The operation timed out.'));
      }, ms);
      // 别让这个定时器拖住事件循环（浏览器里无 unref，做了也无害）
      if (timer && typeof timer.unref === 'function') timer.unref();
      return controller.signal;
    };
  }

  // Chromium 116：AbortSignal.any
  if (typeof AbortSignal !== 'undefined' && typeof AbortSignal.any !== 'function') {
    AbortSignal.any = function (signals) {
      var controller = new AbortController();
      var signal = controller.signal;
      var list = Array.prototype.slice.call(signals);

      for (var i = 0; i < list.length; i++) {
        if (list[i].aborted) {
          controller.abort(list[i].reason);
          return signal;
        }
      }

      var onAbort = function (event) {
        for (var j = 0; j < list.length; j++) {
          list[j].removeEventListener('abort', onAbort);
        }
        controller.abort(event.target.reason);
      };

      for (var k = 0; k < list.length; k++) {
        list[k].addEventListener('abort', onAbort);
      }
      return signal;
    };
  }

  // Chromium 93：AbortSignal.abort（静态）
  if (typeof AbortSignal !== 'undefined' && typeof AbortSignal.abort !== 'function') {
    AbortSignal.abort = function (reason) {
      var controller = new AbortController();
      controller.abort(reason !== undefined ? reason : abortError());
      return controller.signal;
    };
  }

  // Chromium 117：Object.groupBy
  if (typeof Object.groupBy !== 'function') {
    Object.groupBy = function (items, callback) {
      if (items == null) throw new TypeError('items is null or undefined');
      if (typeof callback !== 'function') throw new TypeError('callback is not a function');

      var result = Object.create(null);
      var index = 0;
      for (var item of items) {
        var key = callback(item, index++);
        var name = typeof key === 'symbol' ? key : String(key);
        if (!Object.prototype.hasOwnProperty.call(result, name)) {
          Object.defineProperty(result, name, {
            value: [],
            writable: true,
            enumerable: true,
            configurable: true,
          });
        }
        result[name].push(item);
      }
      return result;
    };
  }

  // Chromium 117：Map.groupBy
  if (typeof Map.groupBy !== 'function') {
    Map.groupBy = function (items, callback) {
      if (items == null) throw new TypeError('items is null or undefined');
      if (typeof callback !== 'function') throw new TypeError('callback is not a function');

      var result = new Map();
      var index = 0;
      for (var item of items) {
        var key = callback(item, index++);
        var group = result.get(key);
        if (group === undefined) {
          group = [];
          result.set(key, group);
        }
        group.push(item);
      }
      return result;
    };
  }

  // Chromium 122：Set 集合运算
  var setOps = {
    union: function (other) {
      var out = new Set(this);
      for (var v of other) out.add(v);
      return out;
    },
    intersection: function (other) {
      var out = new Set();
      for (var v of other) if (this.has(v)) out.add(v);
      return out;
    },
    difference: function (other) {
      var out = new Set();
      for (var v of this) if (!other.has(v)) out.add(v);
      return out;
    },
    symmetricDifference: function (other) {
      var out = new Set();
      for (var v of this) if (!other.has(v)) out.add(v);
      for (var w of other) if (!this.has(w)) out.add(w);
      return out;
    },
    isSubsetOf: function (other) {
      for (var v of this) if (!other.has(v)) return false;
      return true;
    },
    isSupersetOf: function (other) {
      for (var v of other) if (!this.has(v)) return false;
      return true;
    },
    isDisjointFrom: function (other) {
      for (var v of this) if (other.has(v)) return false;
      return true;
    },
  };
  for (var op in setOps) {
    if (typeof Set.prototype[op] !== 'function') {
      Object.defineProperty(Set.prototype, op, {
        value: setOps[op],
        writable: true,
        enumerable: false,
        configurable: true,
      });
    }
  }

  // Chromium 128：Promise.try
  if (typeof Promise.try !== 'function') {
    Promise.try = function (fn) {
      var args = Array.prototype.slice.call(arguments, 1);
      return new Promise(function (resolve) {
        resolve(fn.apply(undefined, args));
      });
    };
  }

  // Chromium 136：RegExp.escape
  if (typeof RegExp.escape !== 'function') {
    RegExp.escape = function (input) {
      var str = String(input);
      var out = '';
      for (var i = 0; i < str.length; i++) {
        var ch = str.charAt(i);
        if (i === 0 && /[0-9A-Za-z]/.test(ch)) {
          // 首字符是字母数字时必须写成转义序列，否则会被当成反向引用
          out += '\\x' + ch.charCodeAt(0).toString(16).padStart(2, '0');
        } else if (/[\\^$.*+?()[\]{}|/\-]/.test(ch)) {
          out += '\\' + ch;
        } else {
          out += ch;
        }
      }
      return out;
    };
  }

  // Chromium 120：**非特殊 scheme** 的 host 解析（`foo://host/path` 里的那个 host）
  //
  // ⚠️ **这是真机踩到的缺口**（CDY-TN90 / Android 10 / WebView 114）：
  //    右栏「工作区文件」点开任何一个文件，正文永远只有一句「文件资源服务不可用」，
  //    不报错、不发请求，像是这个功能整个不存在。
  //
  // 根因在前端的地址解析（`@deepseek-ai/dsh-client-resources/lib/client.js`）：
  //
  //     parsed = new URL(address);                       // dsh-resource://file/session/<id>/<path>
  //     if (parsed.protocol !== 'dsh-resource:') return undefined;
  //     return parsed.hostname === '' ? undefined : parsed.hostname.toLowerCase();
  //
  // 而 Chromium 直到 **120** 才实现**非特殊 scheme**（不是 http/https/ws/wss/file/ftp
  // 那几个）的 authority 解析。旧内核上实测：
  //
  //     new URL('foo://bar/baz')
  //       旧内核 → hostname ''  pathname '//bar/baz'   ← `//bar` 被整段当成路径
  //       规范   → hostname 'bar' pathname '/baz'
  //
  // 于是 `protocolOf()` 恒为 undefined → registry 认为「该协议没有 provider」→
  // 快照停在 `status: 'none'` → 前端渲染成「文件资源服务不可用」。
  // ⚠️ 受害的不止 file 一个协议：`plan`、`subagentchat` 同样挂着 `dsh-resource:`，
  //    凡是走 ResourceRegistry 的地址解析**都**受影响。
  //
  // ⚠️ 别把它当成后端问题：右栏的文件**列表**走得通（那是普通 RPC），
  //    列表里点文件没反应才是这里 —— 两者只差在这一个 `new URL()` 上。
  //
  // 修法：只补内核确实算不出来的那三样 —— `host` / `hostname` / `port`。
  // 判据是「原生 hostname 为空 **且** href 里确实写着 `scheme://authority`」，
  // 两者同时成立才接手，因此：
  //   - 内核已支持（新版 WebView）时下面的探测直接不成立，一个字节都不改；
  //   - 特殊 scheme、以及 `foo:/path`、`foo:path`、`blob:http://…` 这些**本就没有**
  //     authority 的形态，原生结果与规范一致，也走不到补丁分支。
  //
  // ⚠️ 只接管 getter，**setter 原样转发给原生**：赋值语义（及其触发的 href 重写）
  //    仍由内核负责，垫片不重建 URL 序列化。
  (function () {
    var PROBE = 'dsh-webview-scheme-probe://probe-host/probe-path';

    function probeHostname() {
      try {
        return new URL(PROBE).hostname;
      } catch (e) {
        return null;
      }
    }

    // 已支持 → 不动；行为既不是 '' 也不是 'probe-host'（比如解析直接抛错）→ 不敢动
    if (probeHostname() !== '') return;

    var dHost = Object.getOwnPropertyDescriptor(URL.prototype, 'host');
    var dName = Object.getOwnPropertyDescriptor(URL.prototype, 'hostname');
    var dPort = Object.getOwnPropertyDescriptor(URL.prototype, 'port');
    if (!dHost || !dName || !dPort || !dHost.get || !dName.get || !dPort.get) return;

    var nativeHost = dHost.get;
    var nativeName = dName.get;
    var nativePort = dPort.get;

    // `scheme://authority…`：只认 href 里字面写着的 authority
    var AUTHORITY_RE = /^([A-Za-z][A-Za-z0-9+.-]*:)\/\/([^/?#]*)/;

    function authorityOf(self) {
      var m = AUTHORITY_RE.exec(String(self.href));
      if (!m) return null;
      var authority = m[2];
      if (authority === '') return null;
      var at = authority.lastIndexOf('@');
      if (at >= 0) authority = authority.slice(at + 1); // 丢掉 userinfo
      return authority === '' ? null : authority;
    }

    // `[::1]:8080` 与 `host:8080` 两种形态（规范里 IPv6 字面量带方括号）
    function splitAuthority(authority) {
      if (authority.charAt(0) === '[') {
        var end = authority.indexOf(']');
        if (end < 0) return { host: authority, port: '' };
        var rest = authority.slice(end + 1);
        return {
          host: authority.slice(0, end + 1),
          port: rest.charAt(0) === ':' ? rest.slice(1) : '',
        };
      }
      var colon = authority.lastIndexOf(':');
      if (colon < 0) return { host: authority, port: '' };
      return { host: authority.slice(0, colon), port: authority.slice(colon + 1) };
    }

    Object.defineProperty(URL.prototype, 'hostname', {
      get: function () {
        var native = nativeName.call(this);
        if (native !== '') return native;
        var authority = authorityOf(this);
        return authority === null ? native : splitAuthority(authority).host.toLowerCase();
      },
      set: dName.set,
      configurable: true,
      enumerable: true,
    });

    Object.defineProperty(URL.prototype, 'host', {
      get: function () {
        var native = nativeHost.call(this);
        if (native !== '') return native;
        var authority = authorityOf(this);
        if (authority === null) return native;
        var parts = splitAuthority(authority);
        var host = parts.host.toLowerCase();
        return parts.port === '' ? host : host + ':' + parts.port;
      },
      set: dHost.set,
      configurable: true,
      enumerable: true,
    });

    Object.defineProperty(URL.prototype, 'port', {
      get: function () {
        var native = nativePort.call(this);
        if (native !== '') return native;
        var authority = authorityOf(this);
        return authority === null ? native : splitAuthority(authority).port;
      },
      set: dPort.set,
      configurable: true,
      enumerable: true,
    });
  })();

  // Chromium 120：URL.canParse
  if (typeof URL.canParse !== 'function') {
    URL.canParse = function (url, base) {
      try {
        new URL(url, base);
        return true;
      } catch (e) {
        return false;
      }
    };
  }

  // Chromium 130：Uint8Array.fromBase64 / toBase64
  if (typeof Uint8Array.fromBase64 !== 'function') {
    Uint8Array.fromBase64 = function (base64) {
      var binary = atob(base64);
      var bytes = new Uint8Array(binary.length);
      for (var i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
      return bytes;
    };
  }
  if (typeof Uint8Array.prototype.toBase64 !== 'function') {
    Object.defineProperty(Uint8Array.prototype, 'toBase64', {
      value: function () {
        var binary = '';
        for (var i = 0; i < this.length; i++) binary += String.fromCharCode(this[i]);
        return btoa(binary);
      },
      writable: true,
      enumerable: false,
      configurable: true,
    });
  }

  // Chromium 130：Uint8Array.prototype.toHex / Uint8Array.fromHex
  //
  // ⚠️ 实测 11 个插件 bundle 在用 `toHex`（含 dsh-client-connection）。
  //    它与 toBase64 是同一批提案，当初也只补了 base64 那一半。
  if (typeof Uint8Array.fromHex !== 'function') {
    Uint8Array.fromHex = function (hex) {
      var s = String(hex);
      if (s.length % 2 !== 0) throw new SyntaxError('Invalid hex string');
      var out = new Uint8Array(s.length / 2);
      for (var i = 0; i < out.length; i++) {
        var byte = parseInt(s.substr(i * 2, 2), 16);
        if (byte !== byte) throw new SyntaxError('Invalid hex string');
        out[i] = byte;
      }
      return out;
    };
  }
  if (typeof Uint8Array.prototype.toHex !== 'function') {
    Object.defineProperty(Uint8Array.prototype, 'toHex', {
      value: function () {
        var out = '';
        for (var i = 0; i < this.length; i++) {
          out += (this[i] < 16 ? '0' : '') + this[i].toString(16);
        }
        return out;
      },
      writable: true,
      enumerable: false,
      configurable: true,
    });
  }

  // Chromium 126：URL.parse（静态，失败返回 null 而不是抛错）
  if (typeof URL.parse !== 'function') {
    URL.parse = function (url, base) {
      try {
        return new URL(url, base);
      } catch (e) {
        return null;
      }
    };
  }

  // Chromium 114：ArrayBuffer.prototype.transfer / transferToFixedLength
  //
  // ⚠️ 语义要点：transfer 之后**原 buffer 被 detach**（byteLength 变 0），
  //    所有权转到新 buffer。这里用「复制 + 把原 buffer 的字节长度清零」模拟。
  //    真正的 detach 无法在 JS 里做到（那是引擎内部状态），实测调用方
  //    （pdf.js 里的 wasm 内存搬运）只关心「拿到等长的新内容」，
  //    不依赖原 buffer 抛错 —— 已核对 client.pdf.js 的用法。
  if (typeof ArrayBuffer.prototype.transfer !== 'function') {
    Object.defineProperty(ArrayBuffer.prototype, 'transfer', {
      value: function (newLength) {
        var len = newLength === undefined ? this.byteLength : newLength;
        var out = new ArrayBuffer(len);
        var copyLen = Math.min(this.byteLength, len);
        new Uint8Array(out).set(new Uint8Array(this, 0, copyLen));
        try {
          // 尽力而为的 detach 近似：多数引擎里 byteLength 是只读的，失败就算了
          Object.defineProperty(this, 'byteLength', { value: 0, configurable: true });
        } catch (e) {
          /* 忽略：拿不到真 detach 不影响调用方用法 */
        }
        return out;
      },
      writable: true,
      enumerable: false,
      configurable: true,
    });
  }
  if (typeof ArrayBuffer.prototype.transferToFixedLength !== 'function') {
    Object.defineProperty(ArrayBuffer.prototype, 'transferToFixedLength', {
      value: function (newLength) {
        return ArrayBuffer.prototype.transfer.call(this, newLength);
      },
      writable: true,
      enumerable: false,
      configurable: true,
    });
  }

  // Chromium 92：crypto.randomUUID
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID !== 'function') {
    crypto.randomUUID = function () {
      var bytes = new Uint8Array(16);
      if (typeof crypto.getRandomValues === 'function') {
        crypto.getRandomValues(bytes);
      } else {
        for (var i = 0; i < 16; i++) bytes[i] = (Math.random() * 256) | 0;
      }
      // 版本 4（随机）+ 变体 10xx，与规范一致
      bytes[6] = (bytes[6] & 0x0f) | 0x40;
      bytes[8] = (bytes[8] & 0x3f) | 0x80;
      var hex = [];
      for (var j = 0; j < 16; j++) hex.push((bytes[j] < 16 ? '0' : '') + bytes[j].toString(16));
      var s = hex.join('');
      return s.slice(0, 8) + '-' + s.slice(8, 12) + '-' + s.slice(12, 16) + '-' +
        s.slice(16, 20) + '-' + s.slice(20);
    };
  }

  // Chromium 133：Map.prototype.getOrInsert / getOrInsertComputed
  if (typeof Map.prototype.getOrInsert !== 'function') {
    Object.defineProperty(Map.prototype, 'getOrInsert', {
      value: function (key, defaultValue) {
        if (this.has(key)) return this.get(key);
        this.set(key, defaultValue);
        return defaultValue;
      },
      writable: true,
      enumerable: false,
      configurable: true,
    });
  }
  if (typeof Map.prototype.getOrInsertComputed !== 'function') {
    Object.defineProperty(Map.prototype, 'getOrInsertComputed', {
      value: function (key, callback) {
        if (this.has(key)) return this.get(key);
        var value = callback(key);
        this.set(key, value);
        return value;
      },
      writable: true,
      enumerable: false,
      configurable: true,
    });
  }

  // Chromium 130：Array.fromAsync
  if (typeof Array.fromAsync !== 'function') {
    Array.fromAsync = function (items, mapFn, thisArg) {
      return (async function () {
        var result = [];
        var index = 0;
        for await (var item of items) {
          result.push(mapFn ? await mapFn.call(thisArg, item, index++) : item);
        }
        return result;
      })();
    };
  }

  // Chromium 95：reportError
  //
  // 前端 bundle 用它上报未捕获异常。缺这个函数时**调用点自己**会抛 TypeError ——
  // 一个本来可恢复的错误因此升级成致命错误，比不报还糟。
  // 标准语义是「按未捕获异常处理」，这里用异步抛出近似，WebView 照常走 window.onerror。
  if (typeof reportError !== 'function') {
    self.reportError = function (error) {
      setTimeout(function () {
        throw error;
      }, 0);
    };
  }

  // ── 内核 83 及更旧：语法降级之后仍缺的**运行时 API** ────────────────
  //
  // 上面那些是「新版内核也缺」的补丁。这一段是另一回事：官方 bundle 已被
  // `tools/build-webview-compat.ps1` 降到 chrome74 编译（语法层面能解析了），
  // 但 **esbuild 只管语法，不管 API** —— 降级产物里仍在调用这些函数。
  // 实测降级后的两个大 bundle 里还有：
  //   index  : .at() ×14、Object.hasOwn ×7、replaceAll ×5、reportError ×2、
  //            AggregateError ×1、WeakRef ×1
  //   vendor : .at() ×12
  //
  // ⚠️ 这几处的版本门槛都比 74 高，而它们**必须**和降级 bundle 一起生效 ——
  //    少任何一个，对应调用点就会抛 TypeError。参见 MainActivity 的
  //    `MIN_WEBVIEW_MAJOR_COMPAT`。

  // Chromium 92：Array/String.prototype.at
  // 负索引是它的核心语义（`arr.at(-1)`），不能简化成 `arr[i]`。
  (function () {
    function at(index) {
      var len = this.length;
      var i = Math.trunc(Number(index)) || 0;
      if (i < 0) i += len;
      if (i < 0 || i >= len) return undefined;
      return this[i];
    }
    var targets = [Array.prototype, String.prototype];
    // TypedArray 的原型不直接挂在 %TypedArray%.prototype 上，逐个取。
    if (typeof Uint8Array !== 'undefined') {
      targets.push(
        Object.getPrototypeOf(Uint8Array.prototype),
        Uint8Array.prototype,
        Int8Array.prototype,
        Uint16Array.prototype,
        Int16Array.prototype,
        Uint32Array.prototype,
        Int32Array.prototype,
        Float32Array.prototype,
        Float64Array.prototype
      );
    }
    for (var i = 0; i < targets.length; i++) {
      var proto = targets[i];
      if (proto && typeof proto.at !== 'function') {
        Object.defineProperty(proto, 'at', {
          value: at,
          writable: true,
          enumerable: false,
          configurable: true,
        });
      }
    }
  })();

  // Chromium 97：Array.prototype.findLast / findLastIndex
  // Chromium 110：Array.prototype.toSorted / toReversed
  //
  // ⚠️ 这一族的调用点在**插件的 client bundle** 里，而**不在** `dsh-web-frontend/dist`
  //    —— 上面那段是按 dist 一个个数出来的，而 dist（官方前端壳）恰好一处都没用到它们，
  //    于是整族被漏掉。这族是「降级 bundle 里仍在调用」的又一批，性质与上面完全相同。
  //
  //    真机症状（粉丝反馈，小米 13 / MIUI 14）：
  //      · composer 里点模型选择器 → 红字 `groups.toSorted is not a function`，
  //        点「重试」无效（每次重算 provider 顺序都会再抛一次）；
  //      · 设置页的「模型」标签页**整页空白** —— 同一个坑的另一处（`rows.toSorted`）。
  //
  //    调用方（扫过全部 WebView 侧 bundle）：
  //      toSorted     : dsh-client-ui-model-selection（`orderModelProviders`）、
  //                     -settings-models、-schedule
  //      toReversed   : -sidebar-documentpreview 的 client.pdf.js
  //      findLast     : -chat ×5、-conversation ×2、-sidebar-documentpreview
  //      findLastIndex: -trajectory、-sidebar-documentpreview
  //
  // ⚠️ **只覆盖 `Array.prototype`，不覆盖 TypedArray。** 这一族的 TypedArray 版本
  //    标准要求返回**同类型**的新 TypedArray，拿上面 `.at()` 那种「一个函数套所有原型」
  //    的写法顶替会静默改变返回类型。而扫遍全部 WebView 侧 bundle，
  //    TypedArray 上并没有它们的调用点 —— 不能拿 `.at()` 的覆盖范围直接类比。
  (function () {
    var methods = {
      findLast: function (predicate, thisArg) {
        if (typeof predicate !== 'function') {
          throw new TypeError('predicate must be a function');
        }
        for (var i = this.length - 1; i >= 0; i--) {
          if (predicate.call(thisArg, this[i], i, this)) return this[i];
        }
        return undefined;
      },
      findLastIndex: function (predicate, thisArg) {
        if (typeof predicate !== 'function') {
          throw new TypeError('predicate must be a function');
        }
        for (var i = this.length - 1; i >= 0; i--) {
          if (predicate.call(thisArg, this[i], i, this)) return i;
        }
        return -1;
      },
      // 与 `sort()` 的唯一差别是**返回副本、不动原数组**（调用方依赖这一点：
      // `state.groups` 是 store 里的快照，就地排序会污染它）。
      // compareFn 的类型校验交给原生 `sort`，语义（含 undefined ⇒ 字典序）完全一致。
      toSorted: function (compareFn) {
        var copy = [];
        for (var i = 0; i < this.length; i++) copy.push(this[i]);
        return copy.sort(compareFn);
      },
      toReversed: function () {
        var copy = [];
        for (var i = this.length - 1; i >= 0; i--) copy.push(this[i]);
        return copy;
      },
    };
    for (var name in methods) {
      if (typeof Array.prototype[name] !== 'function') {
        Object.defineProperty(Array.prototype, name, {
          value: methods[name],
          writable: true,
          enumerable: false,
          configurable: true,
        });
      }
    }
  })();

  // Chromium 98：structuredClone
  //
  // ⚠️ 这一族和上面那段是**同一个病根**：旧的清单只扫了 `dsh-web-frontend/dist`
  //    （官方 shell），而调用点其实在**插件的 client bundle** 里，于是整族被漏掉。
  //    现在降级与盘点都覆盖到了插件（见 `tools/build-webview-compat.ps1` 的 v2 说明）。
  //
  //    三个包在用，且都在**用户主路径**上：
  //      · dsh-client-ui-settings          —— `mutate()` 克隆 ops 后再写配置
  //      · dsh-client-ui-settings-models   —— `structuredClone(subtree)` 读模型配置
  //        （缺了它，「模型」设置页整页空白）
  //      · -sidebar-documentpreview/client.pdf.js —— pdf.js 内部 4 处
  //
  //    实现在这里刻意用「优先原生 → 再退到带类型的深拷贝」：原生存在时（新旧内核
  //    都有）直接用，只有真缺才补 —— 与文件里其它段落一样保持幂等。
  //
  // ⚠️ **兜底实现只覆盖 JSON 能表达的数据**（普通对象/数组/字符串/数字/布尔/null），
  //    另外单独处理 Date / Map / Set / RegExp / ArrayBuffer / TypedArray。
  //    **注意：pdf.js 那 4 处调用带了 `{ transfer }` 第二参，而这里的兜底实现
  //    忽略 transfer**（不做 ArrayBuffer 所有权转移，改为复制）——语义上更安全
  //    （源 buffer 仍可用），代价是多一次内存拷贝。
  //    pdf.js 的 worker 消息克隆拿到的是副本，不影响正确性。
  if (typeof structuredClone !== 'function') {
    (function () {
      function cloneValue(value, seen) {
        if (value === null || typeof value !== 'object') return value;
        // 环引用保护：结构化克隆支持环，朴素的递归会栈溢出
        var hit = seen.get(value);
        if (hit) return hit;

        var proto = Object.getPrototypeOf(value);
        var out;

        if (value instanceof Date) return new Date(value.getTime());
        if (value instanceof RegExp) return new RegExp(value.source, value.flags);
        if (value instanceof ArrayBuffer) return value.slice(0);
        if (ArrayBuffer.isView(value)) {
          // TypedArray / DataView：复制底层 buffer 并保持视图窗口
          var buf = value.buffer.slice(value.byteOffset, value.byteOffset + value.byteLength);
          return value instanceof DataView
            ? new DataView(buf)
            : new value.constructor(buf);
        }
        if (value instanceof Map) {
          out = new Map();
          seen.set(value, out);
          value.forEach(function (v, k) {
            out.set(cloneValue(k, seen), cloneValue(v, seen));
          });
          return out;
        }
        if (value instanceof Set) {
          out = new Set();
          seen.set(value, out);
          value.forEach(function (v) {
            out.add(cloneValue(v, seen));
          });
          return out;
        }
        if (Array.isArray(value)) {
          out = [];
          seen.set(value, out);
          for (var i = 0; i < value.length; i++) out[i] = cloneValue(value[i], seen);
          return out;
        }
        // 普通对象（含 null 原型）。只复制可枚举的自有属性 —— 与结构化克隆一致
        // （它不搬原型链，结果一律是普通对象）。
        out = proto === null ? Object.create(null) : {};
        seen.set(value, out);
        var keys = Object.keys(value);
        for (var j = 0; j < keys.length; j++) {
          out[keys[j]] = cloneValue(value[keys[j]], seen);
        }
        return out;
      }
      var shim = function (value /* , options */) {
        return cloneValue(value, new WeakMap());
      };
      try {
        Object.defineProperty(self, 'structuredClone', {
          value: shim,
          writable: true,
          enumerable: false,
          configurable: true,
        });
      } catch (e) {
        self.structuredClone = shim;
      }
    })();
  }

  // Chromium 87：Intl.Segmenter
  //
  // ⚠️ **这个比上面那个更急**：`dsh-client-ui-chat` 是在**模块顶层**构造它的
  //    （`const LIVE_TOOL_DETAIL_SEGMENTER = new Intl.Segmenter(...)`），
  //    缺了会在**加载插件那一刻**就抛，整个 chat 插件的 client bundle 注册失败。
  //    `client.pdf.js` 里另有两处（`granularity: "grapheme" / "word"`）。
  //
  // ⚠️ 兜底实现的**切分粒度不如原生**，这是有意的取舍：
  //    · grapheme：用 `\p{Extended_Pictographic}` + 组合记号近似，能挡住最常见的
  //      「emoji / 变音符号被从中间截断」；但不等价于完整的 UAX #29。
  //    · word：按 `Intl.Segmenter` 的词边界近似（拉丁词 + CJK 逐字）。
  //    调用场景是**给 UI 显示细节做截断**（chat 的 tool detail 限 160 字符）和
  //    pdf 里的词边界判断，截断点略偏不会造成错误结果，只会略有出入。
  //    真要有原生就用原生的（新内核走不到这里）。
  //
  //    `containing()` 是 pdf.js 用到的关键方法（L24756/24760），必须实现。
  if (typeof Intl !== 'undefined' && typeof Intl.Segmenter !== 'function') {
    (function () {
      // ⚠️ **正则必须用 `new RegExp` 在运行时构造，绝不能写成字面量。**
      //    正则字面量在**解析阶段**就被编译：万一某个老内核不认
      //    `\p{Extended_Pictographic}`（Unicode 属性转义里的这个值比 `/u` 本身更晚
      //    落地），抛的是 SyntaxError —— 那会让**整个 polyfill 文件**解析失败，
      //    连带上面所有补丁一起失效。这是本项目最不能接受的一种失败。
      //    退一步只让 grapheme 粒度退化成「按码元切」，比整体炸掉好得多。
      //
      // 近似 grapheme 切分：组合记号、变体选择符、零宽连接符、区域指示符都吸附到前一个
      var GRAPHEME_RE = null;
      try {
        GRAPHEME_RE = new RegExp(
          '(?:\\p{Extended_Pictographic}(?:\\uFE0F|\\u200D\\p{Extended_Pictographic})*|\\P{M}\\p{M}*|[\\s\\S])',
          'gu'
        );
      } catch (e) {
        GRAPHEME_RE = null;
      }
      var WORD_RE = null;
      try {
        WORD_RE = new RegExp("[\\p{L}\\p{N}_'’]+|\\s+|[^\\s\\p{L}\\p{N}_]", 'gu');
      } catch (e) {
        WORD_RE = null;
      }

      // 兜底切分（零依赖）：逐码元推进，遇到代理对合并成一个整体。
      // 粒度只到「码元/码点」，不处理组合记号 —— 好过完全不切。
      function fallbackSplit(source) {
        var out = [];
        for (var i = 0; i < source.length; i++) {
          var code = source.charCodeAt(i);
          if (code >= 0xd800 && code <= 0xdbff && i + 1 < source.length) {
            out.push(source.slice(i, i + 2));
            i++;
          } else {
            out.push(source[i]);
          }
        }
        return out;
      }

      function makeSegments(source, parts) {
        var list = [];
        var index = 0;
        for (var i = 0; i < parts.length; i++) {
          var seg = parts[i];
          if (seg === '') continue;
          list.push({ segment: seg, index: index, input: source, isWordLike: /\S/.test(seg) });
          index += seg.length;
        }
        return list;
      }

      function Segmenter(locales, options) {
        if (!(this instanceof Segmenter)) return new Segmenter(locales, options);
        this._granularity = (options && options.granularity) || 'grapheme';
        this.resolvedOptions = function () {
          return { locale: 'und', granularity: this._granularity };
        };
      }
      Segmenter.prototype.segment = function (input) {
        var source = input === undefined || input === null ? '' : String(input);
        var parts;
        if (this._granularity === 'word') {
          parts = WORD_RE ? source.match(WORD_RE) : source.split(/(\s+)/);
        } else {
          parts = GRAPHEME_RE ? source.match(GRAPHEME_RE) : fallbackSplit(source);
        }
        var list = makeSegments(source, parts || []);

        // 迭代器协议：segments 可 for..of，也要能展开成数组。
        // ⚠️ 每次取 `[Symbol.iterator]()` 都必须返回**新的**迭代器 —— 返回同一个
        //    会在第二次遍历时拿到已耗尽的那个（`[...segments]` 变空数组）。
        //    原生就是这么定的，chat 那边用 `Array.from(segmenter.segment(x), ...)` 取值。
        var segments = {
          containing: function (index) {
            for (var i = 0; i < list.length; i++) {
              if (index >= list[i].index && index < list[i].index + list[i].segment.length) {
                return list[i];
              }
            }
            return undefined;
          },
          [Symbol.iterator]: function () {
            var i = 0;
            return {
              next: function () {
                return i < list.length
                  ? { value: list[i++], done: false }
                  : { value: undefined, done: true };
              },
            };
          },
        };
        return segments;
      };
      try {
        Object.defineProperty(Intl, 'Segmenter', {
          value: Segmenter,
          writable: true,
          enumerable: false,
          configurable: true,
        });
      } catch (e) {
        Intl.Segmenter = Segmenter;
      }
    })();
  }

  // Chromium 93：Object.hasOwn
  // 与 `hasOwnProperty` 的差别是**不查原型链、对 null 原型对象也安全**。
  if (typeof Object.hasOwn !== 'function') {
    Object.hasOwn = function (target, key) {
      if (target == null) throw new TypeError('Cannot convert undefined or null to object');
      return Object.prototype.hasOwnProperty.call(Object(target), key);
    };
  }

  // Chromium 85：String.prototype.replaceAll
  // ⚠️ 第一参数是字符串时必须**全局**替换（这正是它与 `replace` 的唯一差别）；
  //   传正则时不加 g 标志，标准要求直接抛 TypeError。
  // ⚠️ 字符串分支不要用 `split/join` —— 那样替换串里的 `$&`、`$$`、`$1` 就不会
  //   按标准展开。转义成字面量正则后交给原生 `replace`，展开语义由引擎负责。
  if (typeof String.prototype.replaceAll !== 'function') {
    Object.defineProperty(String.prototype, 'replaceAll', {
      value: function (search, replacement) {
        if (search instanceof RegExp) {
          if (!search.global) {
            throw new TypeError('replaceAll must be called with a global RegExp');
          }
          return this.replace(search, replacement);
        }
        var str = String(this);
        var needle = String(search);
        if (needle === '') return str;
        var escaped = needle.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
        return str.replace(new RegExp(escaped, 'g'), replacement);
      },
      writable: true,
      enumerable: false,
      configurable: true,
    });
  }

  // Chromium 85：AggregateError
  // `Promise.any` 的拒绝值就是它，而 bundle 里既构造也 `instanceof` 判断。
  if (typeof AggregateError === 'undefined') {
    self.AggregateError = (function () {
      function AggregateError(errors, message) {
        var err = new Error(message);
        Object.setPrototypeOf(err, AggregateError.prototype);
        err.name = 'AggregateError';
        err.errors = Array.from(errors || []);
        return err;
      }
      AggregateError.prototype = Object.create(Error.prototype);
      AggregateError.prototype.constructor = AggregateError;
      return AggregateError;
    })();
  }

  // Chromium 84：WeakRef
  //
  // ⚠️ 这个降级**只能近似**：真正的弱引用语义（不阻止 GC、`.deref()` 可能返回
  //    undefined）在旧内核上无法用 JS 复刻。这里用强引用顶替，`.deref()` 恒返回原对象。
  //    代价是该对象不会被回收 —— 对本应用（一个本地 GUI 页）可以接受，
  //    总好过 `new WeakRef(...)` 直接抛 ReferenceError 让整个 chunk 挂掉。
  if (typeof WeakRef === 'undefined') {
    self.WeakRef = (function () {
      function WeakRef(target) {
        if (!(this instanceof WeakRef)) throw new TypeError("Constructor WeakRef requires 'new'");
        this._target = target;
      }
      WeakRef.prototype.deref = function () {
        return this._target;
      };
      return WeakRef;
    })();
  }

  // Chromium 134：显式资源管理符号
  if (typeof Symbol.dispose === 'undefined') {
    try {
      Symbol.dispose = Symbol.for('Symbol.dispose');
    } catch (e) {
      /* 忽略：个别实现把 Symbol 设为不可扩展 */
    }
  }
  if (typeof Symbol.asyncDispose === 'undefined') {
    try {
      Symbol.asyncDispose = Symbol.for('Symbol.asyncDispose');
    } catch (e) {
      /* 同上 */
    }
  }

  // ── Worker 失败留痕 ─────────────────────────────────────────────────
  //
  // 客户端把**非图片**附件交给 Worker 上传：主线程建一个 blob 形式的 Worker，
  // Worker 内部用 XHR 把文件发出去（见 `dsh-client-file-upload` 的
  // `workerTransport()` / `fileUploadWorker()`）。图片走的是另一条路
  // （base64 JSON 经 `ctx.remote.fileUploads.upload`），**根本不用 Worker** ——
  // 所以「大多数人正常、少数人失败」在这条链路上完全说得通：发图的都没事，
  // 发压缩包/文档的才踩到。
  //
  // 而这条路失败时**一行日志都不留**：Worker 的 `onerror` 只 reject 一个
  // Promise，上层把它变成一个「上传失败，点击重试」卡片，全程没有 `console.*`。
  // 于是 `web.log` 里没有痕迹，诊断包拿回来也定不了位。
  //
  // 这个代价已经真金白银付过一次：23 份诊断包里**零条**上传失败记录，
  // 而用户反馈的失败是真存在的 —— 不是没发生，是没留痕。
  //
  // ⚠️ 只挂监听器，**不碰 `onerror` 属性**：`addEventListener` 与 `onerror`
  //   属性在事件派发时**并存**、互不顶替，所以这是纯观测，不改变任何既有语义。
  // ⚠️ 同步抛错（blob URL 无效、超并发上限）也要留痕，否则同样无声。
  // ⚠️ 观测本身绝不能影响 Worker：所有补丁分支都吞掉自己的异常。
  if (typeof window !== 'undefined' && typeof window.Worker === 'function') {
    (function () {
      var NativeWorker = window.Worker;
      if (NativeWorker.__dshWorkerObserved === true) return;

      function label(options) {
        return options && options.name ? String(options.name) : '(未命名)';
      }

      // ── 上传 Worker 改走主线程 ────────────────────────────────────────
      //
      // 症状（服务器诊断包实证：nubia NX809J / Android 16 / WebView 143）：
      //   composer 左下角「+」→「文件」→ 选**任意格式**都只回一句「上传失败」。
      //   整包日志只有一行 `Worker 上报传输失败 [dsh-file-upload]:
      //   background upload transport failed`，且**选完文件 130ms 就失败** ——
      //   是 XHR 立刻 `onerror`，不是超时，所以既不是网络慢、也不是服务端拒绝。
      //
      // 为什么「发图片没事、发文件全挂」：两者走的是**两条不同的路**。图片经
      //   `ctx.remote.fileUploads.upload`（主线程 fetch + base64 JSON），
      //   **根本不建 Worker**；只有非图片才交给下面这个 Worker。
      //   真机复刻（CDY-TN90 / WebView 114）跑同一份 `workerTransport()`：
      //   同一个 Blob、同一个 URL、同样 `withCredentials` → **200**。
      //   即失败与上传逻辑无关，是**该内核上 blob Worker 内的 XHR 不可用**。
      //
      // 修法：把「上传」这一个 Worker 换成主线程实现。接口面照 Worker 协议
      //   原样提供（postMessage / onmessage / onerror / terminate /
      //   addEventListener），`workerTransport()` 完全无感；进度用
      //   `xhr.upload.onprogress` 照旧上报，**进度条不丢**。
      //
      // ⚠️ 只接管 Blob body。`upload()` 的 body 也可能是 ReadableStream，那条路
      //   要 `duplex: 'half'` 的 fetch 语义，主线程 XHR 表达不了 —— 原样退回真
      //   Worker，宁可维持现状也不引入新的失败面。
      // ⚠️ 只接管 `name === 'dsh-file-upload'` 的构造，其余 Worker 一字不动。
      var UPLOAD_WORKER_NAME = 'dsh-file-upload';

      function uploadWorkerOnMainThread() {
        // 事件中枢：`workerTransport()` 用**属性**（`onmessage`/`onerror`），
        // 而上面的观测代码用 `addEventListener` —— 两种写法都要能收到，所以
        // 属性存取器直接映射到同一个中枢上。
        var bus = new EventTarget();
        var activeXhr = null;

        function emit(detail) {
          try {
            bus.dispatchEvent(new MessageEvent('message', { data: detail }));
          } catch (e) {
            /* 上报失败不能影响上传本身 */
          }
        }

        function fail(message) {
          console.error('[dsh-webview] 上传改走主线程后仍失败: ' + message);
          emit({ kind: 'error', message: message });
        }

        var worker = {
          terminate: function () {
            if (activeXhr) {
              try {
                activeXhr.abort();
              } catch (e) {
                /* 请求已结束 */
              }
              activeXhr = null;
            }
          },
          postMessage: function (message) {
            var data = message || {};
            if (!(data.body instanceof Blob)) {
              fail('background upload worker received an invalid body');
              return;
            }
            var xhr = new XMLHttpRequest();
            activeXhr = xhr;
            try {
              xhr.open('POST', data.url);
              xhr.withCredentials = true;
              var headers = data.headers || {};
              for (var name in headers) {
                if (Object.prototype.hasOwnProperty.call(headers, name)) {
                  xhr.setRequestHeader(name, headers[name]);
                }
              }
              xhr.upload.onprogress = function (progress) {
                var out = { kind: 'progress', loaded: progress.loaded };
                if (progress.lengthComputable) out.total = progress.total;
                emit(out);
              };
              xhr.onload = function () {
                emit({ kind: 'complete', status: xhr.status, body: xhr.responseText });
              };
              xhr.onerror = function () {
                fail('background upload transport failed');
              };
              xhr.send(data.body);
            } catch (e) {
              fail((e && e.message) || String(e));
            }
          },
        };

        worker.addEventListener = function (type, listener, options) {
          bus.addEventListener(type, listener, options);
        };
        worker.removeEventListener = function (type, listener, options) {
          bus.removeEventListener(type, listener, options);
        };
        ['message', 'error'].forEach(function (type) {
          Object.defineProperty(worker, 'on' + type, {
            get: function () {
              return worker['__on' + type];
            },
            set: function (fn) {
              var previous = worker['__on' + type];
              if (previous) bus.removeEventListener(type, previous);
              worker['__on' + type] = fn;
              if (typeof fn === 'function') bus.addEventListener(type, fn);
            },
          });
        });

        return worker;
      }

      function observed(url, options) {
        if (options && options.name === UPLOAD_WORKER_NAME) {
          // 打一行是**有意的**：修复是否生效只能靠它确认（真机日志里看得见）。
          console.log('[dsh-webview] 上传 Worker 改走主线程: ' + UPLOAD_WORKER_NAME);
          return uploadWorkerOnMainThread();
        }
        var worker;
        try {
          worker = new NativeWorker(url, options);
        } catch (e) {
          console.error(
            '[dsh-webview] 创建 Worker 失败 [' + label(options) + ']: ' +
              ((e && e.message) || String(e))
          );
          throw e;
        }
        try {
          worker.addEventListener('error', function (event) {
            console.error(
              '[dsh-webview] Worker 运行失败 [' + label(options) + ']: ' +
                ((event && event.message) ||
                  (event && event.error && event.error.message) ||
                  '脚本加载或执行失败')
            );
          });
          worker.addEventListener('messageerror', function () {
            console.error(
              '[dsh-webview] Worker 消息无法结构化克隆 [' + label(options) + ']'
            );
          });
          // ⚠️ 只挂 error 是**不够**的：上传 Worker 在内部用 XHR 发文件，XHR 失败时
          //    它**不抛异常**，而是 `postMessage({ kind: 'error', message })`，主线程
          //    拿到后只是 reject 一个 Promise —— 既不触发 `error` 事件，也不打日志。
          //    HTTP 非 200 同理（`kind: 'complete'` + `status`，由调用方判 200）。
          //    所以这里必须同时观测 message，否则「网络/服务端拒绝」这一类失败
          //    仍然完全无声，而那恰恰是最可能的一类。
          worker.addEventListener('message', function (event) {
            try {
              var d = event && event.data;
              if (d === null || typeof d !== 'object') return;
              if (d.kind === 'error') {
                console.error(
                  '[dsh-webview] Worker 上报传输失败 [' + label(options) + ']: ' +
                    (d.message || '(无 message)')
                );
              } else if (d.kind === 'complete' && d.status !== 200) {
                console.error(
                  '[dsh-webview] Worker 传输返回 HTTP ' + String(d.status) +
                    ' [' + label(options) + ']: ' +
                    String(d.body === undefined ? '' : d.body).slice(0, 200)
                );
              }
            } catch (e) {
              /* 观测失败不能影响 Worker 本身 */
            }
          });
        } catch (e) {
          /* 观测失败不能影响 Worker 本身 */
        }
        return worker;
      }

      // 保住 `instanceof Worker` 与静态成员：新构造器的原型指回原生原型，
      // 而 `new` 一个返回对象的构造器得到的就是那个 Worker 实例。
      observed.prototype = NativeWorker.prototype;
      for (var key in NativeWorker) {
        if (Object.prototype.hasOwnProperty.call(NativeWorker, key)) {
          try {
            observed[key] = NativeWorker[key];
          } catch (e) {
            /* 只读静态成员跳过 */
          }
        }
      }
      observed.__dshWorkerObserved = true;
      try {
        window.Worker = observed;
      } catch (e) {
        /* 不可写时保持原样 */
      }
    })();
  }
})();

/* ══════════════════════════════════════════════════════════════════════
 * 兜底错误码 → 显示真实原因
 *
 * ## 为什么需要
 *
 * DSH 引擎有一批**兜底错误码**：真正的失败被包成 `session/agent-busy`
 * 这类笼统的码，真因藏在 `details.reason` 里。用户在 App 上只能看到
 *
 * ```
 * prompt rejected (session/agent-busy)
 * ```
 *
 * 而实际原因可能是
 *
 * ```
 * Could not load the "sharp" module using the android-arm64 runtime
 * ```
 * 或
 * ```
 * EACCES: permission denied, open '/data/user/0'
 * ```
 *
 * 这两者的修法完全不同，但用户看到的提示一模一样 —— 于是「发图失败」
 * 这个反馈无法定位，只能靠反复下载调试包试。用户的原话：
 * 「你知不知道我每次调试都要下载那个安装包 很浪费钱的」、
 * 「必须把错误原因显示在 App 界面里」。
 *
 * ## 做法
 *
 * 不碰引擎产物（那会被版本戳覆盖），只在**页面世界**给 `console.error`
 * 与 `fetch` 的响应做旁路观测：
 *
 *   · 一旦发现形如 `prompt rejected (...)` 的文本，且能同时取到
 *     `details.reason` / `reason`，就把两者拼起来重新交给 console；
 *   · 另外挂一个 `window.__DSH_LAST_ERROR__`，原生侧（`ui-probe`）可以
 *     直接读出来弹 Toast，用户在手机上不用连电脑就能看到真因。
 *
 * ⚠️ 只**读**不**改**：绝不拦截、绝不吞掉原调用，页面行为与原来完全一致。
 * ⚠️ 兼容老内核：本文件不转译，所以全用 `var` + `function`，不用箭头函数、
 *    可选链、模板串以外的新语法。
 * ══════════════════════════════════════════════════════════════════════ */
(function () {
  if (window.__DSH_ERROR_DETAIL__) return;
  window.__DSH_ERROR_DETAIL__ = true;

  /* 最近一次「兜底错误码 + 真实原因」，原生侧读它。 */
  window.__DSH_LAST_ERROR__ = null;

  var MAX_LEN = 600;

  /* 从任意值里尽量挖出「真实原因」。引擎的 details 结构有好几种形态，
   * 这里按出现频率依次尝试，挖不到就返回空串。 */
  function digReason(v) {
    if (!v) return "";
    if (typeof v === "string") return v;
    if (typeof v !== "object") return "";
    var keys = ["reason", "details", "detail", "cause", "message", "error", "data"];
    for (var i = 0; i < keys.length; i++) {
      var k = keys[i];
      if (v[k]) {
        var got = digReason(v[k]);
        if (got) return got;
      }
    }
    return "";
  }

  /* 把「码 + 原因」合成一句人能看懂的话。 */
  function compose(code, reason) {
    if (!code) return reason || "";
    if (!reason || reason === code) return code;
    return code + "\n原因: " + reason;
  }

  function clip(s) {
    s = String(s == null ? "" : s);
    return s.length > MAX_LEN ? s.slice(0, MAX_LEN) + "…" : s;
  }

  /* 记录一条错误：既存到全局供原生读，也返回合成后的文本。 */
  function record(code, reason) {
    var text = clip(compose(code, reason));
    if (!text) return "";
    try {
      window.__DSH_LAST_ERROR__ = {
        code: clip(code || ""),
        reason: clip(reason || ""),
        text: text,
        at: Date.now()
      };
    } catch (e) { /* 赋值失败不影响主流程 */ }
    return text;
  }

  /* ── 1. 观测 console.error / console.warn ──────────────────────────
   *
   * 引擎在浏览器里打错误日志时会带上 details。这里**只旁听**：先调原始
   * 实现（保持行为不变），再顺手挖一次原因。 */
  function wrapConsole(name) {
    var orig = console[name];
    if (typeof orig !== "function" || orig.__dshWrapped) return;
    var wrapped = function () {
      try {
        var parts = [];
        for (var i = 0; i < arguments.length; i++) parts.push(arguments[i]);
        var joined = parts.map(function (p) {
          return typeof p === "string" ? p : safeString(p);
        }).join(" ");
        /* 只在确实像「兜底错误码」时才记录，避免把普通日志当错误。 */
        if (/prompt rejected|agent-busy|rejected \(/i.test(joined)) {
          var reason = "";
          for (var j = 0; j < parts.length; j++) {
            var r = digReason(parts[j]);
            if (r && r !== joined) { reason = r; break; }
          }
          record(joined, reason);
        }
      } catch (e) { /* 观测失败绝不影响原始调用 */ }
      return orig.apply(console, arguments);
    };
    wrapped.__dshWrapped = true;
    try { console[name] = wrapped; } catch (e) { /* 只读时跳过 */ }
  }

  function safeString(v) {
    if (v == null) return "";
    if (typeof v === "string") return v;
    try { return JSON.stringify(v); } catch (e) { return String(v); }
  }

  wrapConsole("error");
  wrapConsole("warn");

  /* ── 2. 观测未捕获异常 ─────────────────────────────────────────────
   *
   * 附件发送失败有时是 Promise 里抛的，不会走 console。这里补一道。 */
  try {
    window.addEventListener("unhandledrejection", function (ev) {
      try {
        var r = ev && ev.reason;
        var text = digReason(r);
        if (text && /agent-busy|prompt rejected|sharp|EACCES/i.test(text)) {
          record(text, "");
        }
      } catch (e) { /* 忽略 */ }
    });
  } catch (e) { /* 老内核可能不支持，忽略 */ }

  /* ── 3. 对外查询接口 ───────────────────────────────────────────────
   *
   * 原生侧（ui-probe 的 `lastError` 动作）读它。返回空对象而不是 null，
   * 免得调用方还要判空。 */
  window.__DSH_TAKE_ERROR__ = function () {
    var e = window.__DSH_LAST_ERROR__;
    window.__DSH_LAST_ERROR__ = null;
    return e || {};
  };
})();
