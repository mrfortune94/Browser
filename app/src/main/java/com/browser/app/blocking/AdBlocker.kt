package com.browser.app.blocking

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

class AdBlocker(private val context: Context) {

    private val blockedDomains = mutableSetOf<String>()
    private var adBlockingEnabled = true

    suspend fun initialize() = withContext(Dispatchers.IO) {
        loadBlocklist()
    }

    private fun loadBlocklist() {
        try {
            context.assets.open("blocklist.txt").bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                        blockedDomains.add(trimmed.lowercase())
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun shouldBlock(request: WebResourceRequest): Boolean {
        if (!adBlockingEnabled) return false
        val url = request.url.toString().lowercase()
        val host = request.url.host?.lowercase() ?: return false
        return blockedDomains.any { domain ->
            host == domain || host.endsWith(".$domain") || url.contains(domain)
        }
    }

    fun shouldBlock(url: String): Boolean {
        if (!adBlockingEnabled) return false
        val lowerUrl = url.lowercase()
        return blockedDomains.any { domain -> lowerUrl.contains(domain) }
    }

    fun getBlockedResponse(): WebResourceResponse {
        return WebResourceResponse(
            "text/plain",
            "utf-8",
            ByteArrayInputStream("".toByteArray())
        )
    }

    fun setEnabled(enabled: Boolean) { adBlockingEnabled = enabled }
    fun isEnabled() = adBlockingEnabled
    fun getBlockedDomainCount() = blockedDomains.size
}
