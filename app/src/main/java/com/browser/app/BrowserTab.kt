package com.browser.app

import android.webkit.WebView

data class BrowserTab(
    val id: String = java.util.UUID.randomUUID().toString(),
    var title: String = "New Tab",
    var url: String = "",
    var webView: WebView? = null,
    var isPrivate: Boolean = false,
    var blockedRequestCount: Int = 0,
    var networkRequests: MutableList<NetworkRequest> = mutableListOf()
)

data class NetworkRequest(
    val url: String,
    val method: String = "GET",
    val requestHeaders: Map<String, String> = emptyMap(),
    var responseCode: Int = 0,
    var responseHeaders: Map<String, String> = emptyMap(),
    var contentType: String = "",
    var size: Long = 0,
    var timeMs: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    var isBlocked: Boolean = false
)
