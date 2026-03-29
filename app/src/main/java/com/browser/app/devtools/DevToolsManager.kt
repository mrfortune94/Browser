package com.browser.app.devtools

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.PopupWindow
import com.browser.app.BrowserTab
import kotlinx.coroutines.CoroutineScope
import org.json.JSONObject

class DevToolsManager(
    private val context: Context,
    private val scope: CoroutineScope
) {

    private var devToolsWindow: PopupWindow? = null
    private var devToolsWebView: WebView? = null
    private var bridge: DevToolsBridge? = null

    fun show(tab: BrowserTab, anchorView: View) {
        if (devToolsWindow?.isShowing == true) {
            devToolsWindow?.dismiss()
        }

        val dtWebView = WebView(context).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccessFromFileURLs = true
                allowUniversalAccessFromFileURLs = true
                setSupportZoom(false)
                cacheMode = WebSettings.LOAD_NO_CACHE
            }
            setBackgroundColor(Color.parseColor("#1e1e1e"))
        }
        devToolsWebView = dtWebView

        tab.webView?.let { pageWebView ->
            val b = DevToolsBridge(pageWebView, dtWebView, scope)
            bridge = b
            dtWebView.addJavascriptInterface(b, "DevToolsBridge")
            b.injectConsoleCaptureScript(pageWebView)
            b.injectApiInterceptScript(pageWebView)
        }

        dtWebView.loadUrl("file:///android_asset/devtools/devtools.html")

        val displayMetrics = context.resources.displayMetrics
        val height = (displayMetrics.heightPixels * 0.6).toInt()

        val window = PopupWindow(dtWebView, ViewGroup.LayoutParams.MATCH_PARENT, height, true).apply {
            isOutsideTouchable = false
            isFocusable = true
            elevation = 16f
        }
        devToolsWindow = window
        window.showAtLocation(anchorView, Gravity.BOTTOM, 0, 0)
    }

    fun hide() {
        devToolsWindow?.dismiss()
        devToolsWindow = null
        devToolsWebView?.destroy()
        devToolsWebView = null
    }

    fun isShowing() = devToolsWindow?.isShowing == true

    fun addNetworkRequest(tab: BrowserTab, url: String, method: String, responseCode: Int, contentType: String, size: Long, timeMs: Long) {
        val json = JSONObject().apply {
            put("url", url.take(200))
            put("method", method)
            put("status", responseCode)
            put("type", contentType.split(";")[0].trim())
            put("size", size)
            put("time", timeMs)
        }.toString()
        devToolsWebView?.post {
            devToolsWebView?.evaluateJavascript("if(window.addNetworkRequest) window.addNetworkRequest($json)", null)
        }
    }

    fun onPageFinished(tab: BrowserTab) {
        if (isShowing()) {
            tab.webView?.let { pageWebView ->
                bridge?.injectConsoleCaptureScript(pageWebView)
                bridge?.injectApiInterceptScript(pageWebView)
            }
        }
    }

    /**
     * Enables or disables the CORS proxy on the active page's WebView.
     * When enabled all cross-origin fetch/XHR calls are routed through
     * [proxyUrl] so WAF / CORS restrictions are bypassed.
     * Note: the proxy operator can see request content — only use with a trusted proxy.
     */
    fun toggleCorsProxy(pageWebView: android.webkit.WebView, enabled: Boolean, proxyUrl: String = "https://corsproxy.io/?") {
        bridge?.injectCorsProxyScript(pageWebView, enabled, proxyUrl)
            ?: DevToolsBridge(pageWebView, pageWebView, scope).injectCorsProxyScript(pageWebView, enabled, proxyUrl)
    }
}
