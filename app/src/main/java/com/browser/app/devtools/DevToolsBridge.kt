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
            // Properly escape backslashes first, then quotes to prevent JS injection
            val escapedSel = selector.replace("\\", "\\\\").replace("'", "\\'")
            pageWebView.evaluateJavascript("""
                (function() {
                    var el = document.querySelector('$escapedSel');
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
}
