package com.browser.app.extensions

import android.content.Context
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

data class BrowserExtension(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val contentScripts: List<ContentScript>,
    val permissions: List<String>,
    var isEnabled: Boolean = true
)

data class ContentScript(
    val matches: List<String>,
    val jsFiles: List<String>,
    val cssFiles: List<String>,
    val runAt: String = "document_idle"
)

class ExtensionManager(private val context: Context) {

    private val extensions = mutableListOf<BrowserExtension>()
    private val extensionCode = mutableMapOf<String, Map<String, String>>()

    suspend fun loadExtensions() = withContext(Dispatchers.IO) {
        val extensionsDir = File(context.getExternalFilesDir(null), "Extensions")
        if (!extensionsDir.exists()) extensionsDir.mkdirs()

        extensionsDir.listFiles()?.forEach { file ->
            when {
                file.name.endsWith(".crx") || file.name.endsWith(".xpi") || file.name.endsWith(".zip") -> {
                    loadExtensionFromZip(file)
                }
                file.isDirectory -> loadExtensionFromDirectory(file)
            }
        }
    }

    private fun loadExtensionFromZip(file: File) {
        try {
            val zip = ZipFile(file)
            val manifestEntry = zip.getEntry("manifest.json") ?: return
            val manifestJson = zip.getInputStream(manifestEntry).bufferedReader().readText()
            val manifest = JSONObject(manifestJson)

            val files = mutableMapOf<String, String>()
            zip.entries().asSequence().forEach { entry ->
                if (!entry.isDirectory) {
                    try {
                        files[entry.name] = zip.getInputStream(entry).bufferedReader().readText()
                    } catch (e: Exception) { /* skip binary files */ }
                }
            }

            val extension = parseManifest(manifest, files)
            extensions.add(extension)
            extensionCode[extension.id] = files
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun loadExtensionFromDirectory(dir: File) {
        try {
            val manifestFile = File(dir, "manifest.json")
            if (!manifestFile.exists()) return
            val manifest = JSONObject(manifestFile.readText())
            val files = mutableMapOf<String, String>()
            dir.walkTopDown().forEach { file ->
                if (file.isFile) {
                    try {
                        files[file.relativeTo(dir).path] = file.readText()
                    } catch (e: Exception) { /* skip binary files */ }
                }
            }
            val extension = parseManifest(manifest, files)
            extensions.add(extension)
            extensionCode[extension.id] = files
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun parseManifest(manifest: JSONObject, files: Map<String, String>): BrowserExtension {
        val id = manifest.optString("name", "unknown").replace(" ", "_").lowercase()
        val contentScripts = mutableListOf<ContentScript>()

        if (manifest.has("content_scripts")) {
            val csArray = manifest.getJSONArray("content_scripts")
            for (i in 0 until csArray.length()) {
                val cs = csArray.getJSONObject(i)
                val matches = mutableListOf<String>()
                val jsFiles = mutableListOf<String>()
                val cssFiles = mutableListOf<String>()

                if (cs.has("matches")) {
                    val matchArray = cs.getJSONArray("matches")
                    for (j in 0 until matchArray.length()) matches.add(matchArray.getString(j))
                }
                if (cs.has("js")) {
                    val jsArray = cs.getJSONArray("js")
                    for (j in 0 until jsArray.length()) jsFiles.add(jsArray.getString(j))
                }
                if (cs.has("css")) {
                    val cssArray = cs.getJSONArray("css")
                    for (j in 0 until cssArray.length()) cssFiles.add(cssArray.getString(j))
                }
                contentScripts.add(ContentScript(matches, jsFiles, cssFiles, cs.optString("run_at", "document_idle")))
            }
        }

        val permissions = mutableListOf<String>()
        if (manifest.has("permissions")) {
            val permArray = manifest.getJSONArray("permissions")
            for (i in 0 until permArray.length()) permissions.add(permArray.getString(i))
        }

        return BrowserExtension(
            id = id,
            name = manifest.optString("name", "Unknown Extension"),
            version = manifest.optString("version", "1.0"),
            description = manifest.optString("description", ""),
            contentScripts = contentScripts,
            permissions = permissions
        )
    }

    fun injectExtensions(webView: WebView, pageUrl: String) {
        extensions.filter { it.isEnabled }.forEach { ext ->
            ext.contentScripts.forEach { cs ->
                if (matchesUrl(cs.matches, pageUrl)) {
                    val files = extensionCode[ext.id] ?: return@forEach

                    cs.cssFiles.forEach { cssFile ->
                        files[cssFile]?.let { css ->
                            // Use a data URI approach: encode CSS as a base64 data URI to avoid
                            // any JS string injection – textContent is set directly, never eval'd
                            val encoded = android.util.Base64.encodeToString(css.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
                            val script = """
                                (function() {
                                    var style = document.createElement('style');
                                    style.textContent = atob('$encoded');
                                    document.head.appendChild(style);
                                })();
                            """.trimIndent()
                            webView.evaluateJavascript(script, null)
                        }
                    }

                    cs.jsFiles.forEach { jsFile ->
                        files[jsFile]?.let { js ->
                            injectExtensionApis(webView, ext.id)
                            webView.evaluateJavascript(js, null)
                        }
                    }
                }
            }
        }
    }

    private fun injectExtensionApis(webView: WebView, extensionId: String) {
        val apiScript = """
            (function() {
                if (window.chrome && window.chrome.runtime && window.chrome.runtime.id === '$extensionId') return;
                window.chrome = {
                    runtime: {
                        id: '$extensionId',
                        sendMessage: function(msg, callback) {
                            if (window.ExtensionBridge) {
                                ExtensionBridge.sendMessage('$extensionId', JSON.stringify(msg));
                            }
                        },
                        onMessage: { addListener: function(fn) {} }
                    },
                    storage: {
                        local: {
                            get: function(keys, callback) {
                                var stored = localStorage.getItem('ext_$extensionId');
                                var data = stored ? JSON.parse(stored) : {};
                                if (Array.isArray(keys)) {
                                    var result = {};
                                    keys.forEach(function(k) { result[k] = data[k]; });
                                    callback(result);
                                } else { callback(data); }
                            },
                            set: function(items, callback) {
                                var stored = localStorage.getItem('ext_$extensionId');
                                var data = stored ? JSON.parse(stored) : {};
                                Object.assign(data, items);
                                localStorage.setItem('ext_$extensionId', JSON.stringify(data));
                                if (callback) callback();
                            }
                        }
                    },
                    tabs: {
                        query: function(filter, callback) { callback([]); },
                        create: function(props) {}
                    }
                };
                window.browser = window.chrome;
            })();
        """.trimIndent()
        webView.evaluateJavascript(apiScript, null)
    }

    private fun matchesUrl(patterns: List<String>, url: String): Boolean {
        return patterns.any { pattern ->
            when {
                pattern == "<all_urls>" -> true
                pattern.contains("*") -> {
                    val regex = pattern.replace(".", "\\.").replace("*", ".*")
                    url.matches(Regex(regex))
                }
                else -> url.startsWith(pattern)
            }
        }
    }

    fun getExtensions() = extensions.toList()
    fun toggleExtension(id: String, enabled: Boolean) {
        extensions.find { it.id == id }?.isEnabled = enabled
    }
}
