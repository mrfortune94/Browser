package com.browser.app.devtools

import android.webkit.JavascriptInterface
import android.webkit.WebView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class DevToolsBridge(
    private val pageWebView: WebView,
    private val devToolsWebView: WebView,
    private val scope: CoroutineScope
) {

    /** Escapes a string for safe embedding inside a single-quoted JS string literal. */
    private fun escapeJsString(s: String): String = s
        .replace("\\", "\\\\")
        .replace("'", "\\'")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
        .replace("\u0000", "\\u0000")

    @JavascriptInterface
    fun executeInPage(javascript: String) {
        scope.launch(Dispatchers.Main) {
            pageWebView.evaluateJavascript(javascript) { result ->
                val escaped = escapeJsString(result ?: "null")
                devToolsWebView.evaluateJavascript("window.receiveConsoleResult('$escaped')", null)
            }
        }
    }

    @JavascriptInterface
    fun getDOM() {
        scope.launch(Dispatchers.Main) {
            pageWebView.evaluateJavascript("""
                (function() {
                    function nodeToJson(node, depth) {
                        if (depth > 5 || !node) return null;
                        var obj = { tag: node.tagName || '#text', attrs: {}, children: [] };
                        if (node.attributes) {
                            for (var i = 0; i < node.attributes.length; i++) {
                                obj.attrs[node.attributes[i].name] = node.attributes[i].value.substring(0, 100);
                            }
                        }
                        if (node.nodeType === 3) { obj.text = node.textContent.trim().substring(0, 100); }
                        if (node.childNodes && depth < 4) {
                            for (var j = 0; j < Math.min(node.childNodes.length, 20); j++) {
                                var child = nodeToJson(node.childNodes[j], depth + 1);
                                if (child) obj.children.push(child);
                            }
                        }
                        return obj;
                    }
                    return JSON.stringify(nodeToJson(document.documentElement, 0));
                })();
            """.trimIndent()) { result ->
                if (result != null) {
                    devToolsWebView.post {
                        devToolsWebView.evaluateJavascript("window.receiveDOM($result)", null)
                    }
                }
            }
        }
    }

    @JavascriptInterface
    fun getStorage() {
        scope.launch(Dispatchers.Main) {
            pageWebView.evaluateJavascript("""
                (function() {
                    var ls = {}, ss = {};
                    for (var i = 0; i < localStorage.length; i++) {
                        var k = localStorage.key(i);
                        ls[k] = localStorage.getItem(k);
                    }
                    for (var i = 0; i < sessionStorage.length; i++) {
                        var k = sessionStorage.key(i);
                        ss[k] = sessionStorage.getItem(k);
                    }
                    return JSON.stringify({ localStorage: ls, sessionStorage: ss });
                })();
            """.trimIndent()) { result ->
                if (result != null) {
                    devToolsWebView.post {
                        devToolsWebView.evaluateJavascript("window.receiveStorage($result)", null)
                    }
                }
            }
        }
    }

    @JavascriptInterface
    fun getSources() {
        scope.launch(Dispatchers.Main) {
            pageWebView.evaluateJavascript("""
                (function() {
                    var scripts = [], styles = [];
                    document.querySelectorAll('script[src]').forEach(function(s) { scripts.push(s.src); });
                    document.querySelectorAll('link[rel=stylesheet]').forEach(function(l) { styles.push(l.href); });
                    document.querySelectorAll('style').forEach(function(s, i) { styles.push('inline-' + i + ':' + s.textContent.substring(0, 200)); });
                    return JSON.stringify({ scripts: scripts, styles: styles });
                })();
            """.trimIndent()) { result ->
                if (result != null) {
                    devToolsWebView.post {
                        devToolsWebView.evaluateJavascript("window.receiveSources($result)", null)
                    }
                }
            }
        }
    }

    @JavascriptInterface
    fun editLocalStorage(key: String, value: String) {
        scope.launch(Dispatchers.Main) {
            val escapedKey = escapeJsString(key)
            val escapedVal = escapeJsString(value)
            pageWebView.evaluateJavascript("localStorage.setItem('$escapedKey', '$escapedVal'); 'done'", null)
        }
    }

    @JavascriptInterface
    fun deleteLocalStorage(key: String) {
        scope.launch(Dispatchers.Main) {
            val escapedKey = escapeJsString(key)
            pageWebView.evaluateJavascript("localStorage.removeItem('$escapedKey'); 'done'", null)
        }
    }

    @JavascriptInterface
    fun inspectElement(selector: String) {
        scope.launch(Dispatchers.Main) {
            // Use JSONObject.quote() to safely encode the selector as a JS string literal,
            // avoiding all injection vectors regardless of backslash/quote combinations.
            val jsonSelector = org.json.JSONObject.quote(selector)
            pageWebView.evaluateJavascript("""
                (function() {
                    var el = document.querySelector($jsonSelector);
                    if (!el) return JSON.stringify({error: 'Element not found'});
                    var computed = window.getComputedStyle(el);
                    var styles = {};
                    for (var i = 0; i < Math.min(computed.length, 50); i++) {
                        styles[computed[i]] = computed.getPropertyValue(computed[i]);
                    }
                    var rect = el.getBoundingClientRect();
                    return JSON.stringify({
                        tagName: el.tagName,
                        id: el.id,
                        className: el.className,
                        innerHTML: el.innerHTML.substring(0, 500),
                        styles: styles,
                        rect: { top: rect.top, left: rect.left, width: rect.width, height: rect.height }
                    });
                })();
            """.trimIndent()) { result ->
                if (result != null) {
                    devToolsWebView.post {
                        devToolsWebView.evaluateJavascript("window.receiveElementInfo($result)", null)
                    }
                }
            }
        }
    }

    @JavascriptInterface
    fun getPerformance() {
        scope.launch(Dispatchers.Main) {
            pageWebView.evaluateJavascript("""
                (function() {
                    var perf = performance.timing || {};
                    return JSON.stringify({
                        domLoad: (perf.domContentLoadedEventEnd - perf.navigationStart) || 0,
                        fullLoad: (perf.loadEventEnd - perf.navigationStart) || 0,
                        ttfb: (perf.responseStart - perf.navigationStart) || 0,
                        resources: performance.getEntriesByType('resource').slice(0, 50).map(function(r) {
                            return { name: r.name.split('/').pop().substring(0, 50), duration: Math.round(r.duration), size: r.transferSize || 0, type: r.initiatorType };
                        })
                    });
                })();
            """.trimIndent()) { result ->
                if (result != null) {
                    devToolsWebView.post {
                        devToolsWebView.evaluateJavascript("window.receivePerformance($result)", null)
                    }
                }
            }
        }
    }

    fun injectConsoleCaptureScript(webView: WebView) {
        val script = """
            (function() {
                if (window.__consoleInjected) return;
                window.__consoleInjected = true;
                var _log = console.log, _warn = console.warn, _error = console.error, _info = console.info;
                function fmt(args) {
                    return Array.prototype.slice.call(args).map(function(a) {
                        try { return typeof a === 'object' ? JSON.stringify(a) : String(a); } catch(e) { return String(a); }
                    }).join(' ');
                }
                console.log = function() { _log.apply(console, arguments); if(window.DevToolsBridge) { try { DevToolsBridge.onConsoleLog('log', fmt(arguments)); } catch(e){} } };
                console.warn = function() { _warn.apply(console, arguments); if(window.DevToolsBridge) { try { DevToolsBridge.onConsoleLog('warn', fmt(arguments)); } catch(e){} } };
                console.error = function() { _error.apply(console, arguments); if(window.DevToolsBridge) { try { DevToolsBridge.onConsoleLog('error', fmt(arguments)); } catch(e){} } };
                console.info = function() { _info.apply(console, arguments); if(window.DevToolsBridge) { try { DevToolsBridge.onConsoleLog('info', fmt(arguments)); } catch(e){} } };
                window.onerror = function(msg, src, line, col, err) {
                    if(window.DevToolsBridge) { try { DevToolsBridge.onConsoleLog('error', msg + ' (' + src + ':' + line + ')'); } catch(e){} }
                };
            })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }

    @JavascriptInterface
    fun onConsoleLog(level: String, message: String) {
        devToolsWebView.post {
            val escaped = escapeJsString(message.take(500))
            val safeLevel = escapeJsString(level)
            devToolsWebView.evaluateJavascript("window.appendConsoleLog('$safeLevel', '$escaped')", null)
        }
    }

    // ---- API Intercept / Modify / Replay ----

    /** Called from page JS when a fetch() or XHR is opened/sent. */
    @JavascriptInterface
    fun onApiRequest(json: String) {
        devToolsWebView.post {
            devToolsWebView.evaluateJavascript("if(window.addApiRequest) window.addApiRequest($json)", null)
        }
    }

    /** Called from page JS when a fetch() or XHR receives a response. */
    @JavascriptInterface
    fun onApiResponse(json: String) {
        devToolsWebView.post {
            devToolsWebView.evaluateJavascript("if(window.addApiResponse) window.addApiResponse($json)", null)
        }
    }

    /**
     * Called from DevTools UI to replay a request (possibly with modifications).
     * Executes the fetch in the page context so it carries the page's cookies/auth.
     */
    @JavascriptInterface
    fun replayApiRequest(json: String) {
        scope.launch(Dispatchers.Main) {
            try {
                val data = JSONObject(json)
                val method = data.optString("method", "GET").uppercase()
                val url = data.optString("url", "")
                val headersObj = data.optJSONObject("headers") ?: JSONObject()
                val body = data.optString("body", "")

                if (url.isBlank()) {
                    val errMsg = escapeJsString("Error: URL must not be empty")
                    devToolsWebView.post {
                        devToolsWebView.evaluateJavascript(
                            "if(window.receiveReplayResult) window.receiveReplayResult(JSON.stringify({ok:false,error:'$errMsg'}))", null)
                    }
                    return@launch
                }

                // Build header entries as JSON object literal for injection
                val headersLiteral = StringBuilder("{")
                val keys = headersObj.keys()
                var first = true
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (!first) headersLiteral.append(",")
                    headersLiteral.append(org.json.JSONObject.quote(key))
                        .append(":")
                        .append(org.json.JSONObject.quote(headersObj.getString(key)))
                    first = false
                }
                headersLiteral.append("}")

                val bodyArg = if (body.isNullOrBlank() || method == "GET" || method == "HEAD") "undefined"
                              else org.json.JSONObject.quote(body)

                // Inject a self-invoking async function that calls back via DevToolsBridge
                val script = """
                    (function() {
                        fetch(${org.json.JSONObject.quote(url)}, {
                            method: ${org.json.JSONObject.quote(method)},
                            headers: $headersLiteral,
                            body: $bodyArg,
                            credentials: 'include'
                        }).then(function(r) {
                            return r.text().then(function(t) {
                                var rh = {};
                                try { r.headers.forEach(function(v,k){ rh[k]=v; }); } catch(e) {}
                                if (window.DevToolsBridge) {
                                    DevToolsBridge.onApiReplayResult(JSON.stringify({
                                        ok: true, status: r.status, headers: rh,
                                        body: t.substring(0, 10000)
                                    }));
                                }
                            });
                        }).catch(function(e) {
                            if (window.DevToolsBridge) {
                                DevToolsBridge.onApiReplayResult(JSON.stringify({
                                    ok: false, error: String(e)
                                }));
                            }
                        });
                    })();
                """.trimIndent()

                pageWebView.evaluateJavascript(script, null)
            } catch (e: Exception) {
                val errMsg = escapeJsString("Error: ${e.message}")
                devToolsWebView.post {
                    devToolsWebView.evaluateJavascript(
                        "if(window.receiveReplayResult) window.receiveReplayResult(JSON.stringify({ok:false,error:'$errMsg'}))", null)
                }
            }
        }
    }

    /** Called from page JS when the replayed fetch completes. */
    @JavascriptInterface
    fun onApiReplayResult(json: String) {
        devToolsWebView.post {
            devToolsWebView.evaluateJavascript("if(window.receiveReplayResult) window.receiveReplayResult($json)", null)
        }
    }

    /**
     * Injects XHR, fetch, and sendBeacon interceptors into the page so every API/webhook call is
     * forwarded to the DevTools API panel via [onApiRequest] / [onApiResponse].
     * Also detects when requests originate from hidden contexts (hidden iframe, background tab)
     * and sets isHidden=true so the DevTools can label them accordingly.
     */
    fun injectApiInterceptScript(webView: WebView) {
        val script = """
            (function() {
                if (window.__apiInterceptInjected) return;
                window.__apiInterceptInjected = true;

                var _fetch = window.fetch;
                var _XHROpen = XMLHttpRequest.prototype.open;
                var _XHRSend = XMLHttpRequest.prototype.send;
                var _XHRSetHeader = XMLHttpRequest.prototype.setRequestHeader;
                var _sendBeacon = navigator.sendBeacon ? navigator.sendBeacon.bind(navigator) : null;
                var __reqCounter = 0;

                /** Returns true when this window/iframe is invisible to the user. */
                function detectHidden() {
                    try {
                        if (document.hidden === true) return true;
                        if (window.frameElement) {
                            var fe = window.frameElement;
                            if (fe.hidden === true) return true;
                            var cs = window.getComputedStyle(fe);
                            if (cs.display === 'none' || cs.visibility === 'hidden' || parseFloat(cs.opacity) === 0) return true;
                            if (fe.offsetParent === null && fe.style.position !== 'fixed') return true;
                        }
                    } catch(e) {}
                    return false;
                }

                // ---- Intercept fetch() ----
                window.fetch = function(input, init) {
                    var id = ++__reqCounter;
                    var url = typeof input === 'string' ? input : ((input && input.url) ? input.url : String(input));
                    var method = (init && init.method) ||
                                 (input && typeof input === 'object' && input.method) || 'GET';
                    var headers = {};
                    if (init && init.headers) {
                        try {
                            if (typeof Headers !== 'undefined' && init.headers instanceof Headers) {
                                init.headers.forEach(function(v, k) { headers[k] = v; });
                            } else {
                                Object.assign(headers, init.headers);
                            }
                        } catch(e) {}
                    }
                    var body = null;
                    if (init && init.body != null) {
                        try { body = typeof init.body === 'string' ? init.body : JSON.stringify(init.body); }
                        catch(e) { body = String(init.body); }
                    }
                    var startTime = Date.now();
                    var isHidden = detectHidden();
                    if (window.DevToolsBridge) {
                        try {
                            DevToolsBridge.onApiRequest(JSON.stringify({
                                id: id, type: 'fetch', method: method.toUpperCase(),
                                url: url, headers: headers, body: body, time: startTime,
                                isHidden: isHidden
                            }));
                        } catch(e) {}
                    }
                    return _fetch.apply(this, arguments)
                        .then(function(response) {
                            var duration = Date.now() - startTime;
                            var cloned = response.clone();
                            cloned.text().then(function(text) {
                                var respHeaders = {};
                                try { response.headers.forEach(function(v, k) { respHeaders[k] = v; }); } catch(e) {}
                                if (window.DevToolsBridge) {
                                    try {
                                        DevToolsBridge.onApiResponse(JSON.stringify({
                                            id: id, status: response.status,
                                            statusText: response.statusText,
                                            headers: respHeaders,
                                            body: text.substring(0, 8000),
                                            duration: duration
                                        }));
                                    } catch(e) {}
                                }
                            }).catch(function(){});
                            return response;
                        })
                        .catch(function(err) {
                            if (window.DevToolsBridge) {
                                try {
                                    DevToolsBridge.onApiResponse(JSON.stringify({
                                        id: id, status: 0, statusText: String(err),
                                        headers: {}, body: '', duration: Date.now() - startTime
                                    }));
                                } catch(e) {}
                            }
                            throw err;
                        });
                };

                // ---- Intercept XMLHttpRequest ----
                XMLHttpRequest.prototype.open = function(method, url) {
                    this.__fbt_id = ++__reqCounter;
                    this.__fbt_method = method;
                    this.__fbt_url = url;
                    this.__fbt_headers = {};
                    this.__fbt_hidden = detectHidden();
                    return _XHROpen.apply(this, arguments);
                };

                XMLHttpRequest.prototype.setRequestHeader = function(name, value) {
                    if (this.__fbt_headers) this.__fbt_headers[name] = value;
                    return _XHRSetHeader.apply(this, arguments);
                };

                XMLHttpRequest.prototype.send = function(body) {
                    var self = this;
                    var id = this.__fbt_id;
                    var startTime = Date.now();
                    if (window.DevToolsBridge && id) {
                        try {
                            var bodyStr = null;
                            if (body != null) {
                                try { bodyStr = typeof body === 'string' ? body : JSON.stringify(body); }
                                catch(e) { bodyStr = String(body); }
                            }
                            DevToolsBridge.onApiRequest(JSON.stringify({
                                id: id, type: 'xhr',
                                method: (self.__fbt_method || 'GET').toUpperCase(),
                                url: self.__fbt_url || '',
                                headers: self.__fbt_headers || {},
                                body: bodyStr, time: startTime,
                                isHidden: self.__fbt_hidden === true
                            }));
                        } catch(e) {}
                    }
                    this.addEventListener('loadend', function() {
                        if (window.DevToolsBridge && id) {
                            try {
                                DevToolsBridge.onApiResponse(JSON.stringify({
                                    id: id, status: self.status,
                                    statusText: self.statusText,
                                    headers: {}, body: (self.responseText || '').substring(0, 8000),
                                    duration: Date.now() - startTime
                                }));
                            } catch(e) {}
                        }
                    });
                    return _XHRSend.apply(this, arguments);
                };

                // ---- Intercept navigator.sendBeacon (webhooks / analytics beacons) ----
                if (_sendBeacon) {
                    navigator.sendBeacon = function(url, data) {
                        var id = ++__reqCounter;
                        var isHidden = detectHidden();
                        var bodyStr = null;
                        if (data != null) {
                            try { bodyStr = typeof data === 'string' ? data : JSON.stringify(data); }
                            catch(e) { bodyStr = String(data); }
                        }
                        if (window.DevToolsBridge) {
                            try {
                                DevToolsBridge.onApiRequest(JSON.stringify({
                                    id: id, type: 'webhook', method: 'BEACON',
                                    url: url, headers: {}, body: bodyStr,
                                    time: Date.now(), isHidden: isHidden
                                }));
                                // Beacons fire-and-forget; mark as sent immediately
                                DevToolsBridge.onApiResponse(JSON.stringify({
                                    id: id, status: 204, statusText: 'No Content (Beacon)',
                                    headers: {}, body: '', duration: 0
                                }));
                            } catch(e) {}
                        }
                        return _sendBeacon(url, data);
                    };
                }
            })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }

    /**
     * Injects a CORS proxy script so all cross-origin fetch/XHR calls are routed
     * through [proxyUrl] (e.g. "https://corsproxy.io/?").  Call with enabled=false
     * to deactivate the proxy without a page reload (the flag is flipped to false).
     */
    fun injectCorsProxyScript(webView: WebView, enabled: Boolean, proxyUrl: String = "https://corsproxy.io/?") {
        if (enabled) {
            val safeProxy = org.json.JSONObject.quote(proxyUrl)
            val script = """
                (function() {
                    if (!window.__corsProxyInjected) {
                        window.__corsProxyInjected = true;
                        var PROXY = $safeProxy;
                        var pageOrigin = window.location.origin;
                        function proxyUrl(url) {
                            if (!window.__corsProxyActive) return url;
                            if (typeof url === 'string' && url.startsWith('http') && !url.startsWith(pageOrigin)) {
                                return PROXY + encodeURIComponent(url);
                            }
                            return url;
                        }
                        var _f = window.fetch;
                        window.fetch = function(input, init) {
                            if (typeof input === 'string') input = proxyUrl(input);
                            else if (input && typeof input === 'object' && input.url) {
                                input = new Request(proxyUrl(input.url), input);
                            }
                            return _f.call(this, input, init);
                        };
                        var _o = XMLHttpRequest.prototype.open;
                        XMLHttpRequest.prototype.open = function(method, url) {
                            if (typeof url === 'string') arguments[1] = proxyUrl(url);
                            return _o.apply(this, arguments);
                        };
                    }
                    window.__corsProxyActive = true;
                })();
            """.trimIndent()
            webView.evaluateJavascript(script, null)
        } else {
            webView.evaluateJavascript("window.__corsProxyActive = false;", null)
        }
    }
}
