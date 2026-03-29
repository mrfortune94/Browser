package com.browser.app.download

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.webkit.WebView
import com.browser.app.BrowserConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class VideoDownloadManager(private val context: Context) {

    suspend fun downloadVideo(videoUrl: String, filename: String = "video.mp4") {
        if (videoUrl.startsWith("blob:")) {
            return
        }
        withContext(Dispatchers.IO) {
            try {
                downloadWithDownloadManager(videoUrl, filename)
            } catch (e: Exception) {
                e.printStackTrace()
                try {
                    downloadDirectly(videoUrl, filename)
                } catch (ex: Exception) {
                    ex.printStackTrace()
                }
            }
        }
    }

    private fun downloadWithDownloadManager(videoUrl: String, filename: String) {
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val uri = Uri.parse(videoUrl)
        val request = DownloadManager.Request(uri).apply {
            setTitle(filename)
            setDescription("Downloading video...")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename)
            addRequestHeader("User-Agent", BrowserConstants.USER_AGENT_MOBILE)
            setAllowedOverMetered(true)
            setAllowedOverRoaming(true)
        }
        downloadManager.enqueue(request)
    }

    private fun downloadDirectly(videoUrl: String, filename: String) {
        val url = URL(videoUrl)
        val connection = url.openConnection() as HttpURLConnection
        connection.connect()
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val outputFile = File(downloadsDir, filename)
        FileOutputStream(outputFile).use { output ->
            connection.inputStream.use { input ->
                input.copyTo(output)
            }
        }
    }

    fun extractBlobVideoUrl(webView: WebView, callback: (String?) -> Unit) {
        val script = """
            (function() {
                var videos = document.querySelectorAll('video');
                for (var i = 0; i < videos.length; i++) {
                    var src = videos[i].src || videos[i].currentSrc;
                    if (src && src.length > 0) { return src; }
                }
                return null;
            })();
        """.trimIndent()
        webView.evaluateJavascript(script) { result ->
            if (result != null && result != "null") {
                callback(result.trim('"'))
            } else {
                callback(null)
            }
        }
    }
}
