package com.browser.app.privacy

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import com.browser.app.BrowserConstants

class PrivacyManager(private val context: Context) {

    var isPrivateMode = false
        private set

    fun enablePrivateMode(webView: WebView) {
        isPrivateMode = true
        applyPrivacySettings(webView)
    }

    fun disablePrivateMode(webView: WebView) {
        isPrivateMode = false
        applyNormalSettings(webView)
    }

    fun applyPrivacySettings(webView: WebView) {
        val settings = webView.settings
        settings.domStorageEnabled = false
        settings.databaseEnabled = false
        settings.cacheMode = WebSettings.LOAD_NO_CACHE
        @Suppress("DEPRECATION")
        settings.saveFormData = false
        CookieManager.getInstance().setAcceptCookie(false)
        CookieManager.getInstance().removeAllCookies(null)
        injectPrivacyScript(webView)
    }

    fun applyNormalSettings(webView: WebView) {
        val settings = webView.settings
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        CookieManager.getInstance().setAcceptCookie(true)
    }

    fun clearAllData() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
    }

    private fun injectPrivacyScript(webView: WebView) {
        val script = """
            (function() {
                if (window.RTCPeerConnection) {
                    var origRTC = window.RTCPeerConnection;
                    window.RTCPeerConnection = function(config) {
                        if (config && config.iceServers) { config.iceServers = []; }
                        return new origRTC(config);
                    };
                }
                var origToDataURL = HTMLCanvasElement.prototype.toDataURL;
                HTMLCanvasElement.prototype.toDataURL = function(type) {
                    var canvas = document.createElement('canvas');
                    canvas.width = this.width;
                    canvas.height = this.height;
                    var ctx = canvas.getContext('2d');
                    if (ctx) {
                        ctx.drawImage(this, 0, 0);
                        var imageData = ctx.getImageData(0, 0, canvas.width, canvas.height);
                        for (var i = 0; i < imageData.data.length; i += 4) {
                            imageData.data[i] ^= Math.floor(Math.random() * 3);
                        }
                        ctx.putImageData(imageData, 0, 0);
                    }
                    return origToDataURL.apply(canvas, arguments);
                };
                Object.defineProperty(navigator, 'hardwareConcurrency', { get: function() { return 4; }});
                Object.defineProperty(navigator, 'deviceMemory', { get: function() { return 4; }});
            })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }

    fun getSpoofedUserAgent(): String = BrowserConstants.USER_AGENT_MOBILE
}
