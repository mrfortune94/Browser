package com.browser.app

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.browser.app.blocking.AdBlocker
import com.browser.app.devtools.DevToolsManager
import com.browser.app.download.VideoDownloadManager
import com.browser.app.extensions.ExtensionManager
import com.browser.app.privacy.PrivacyManager
import com.browser.app.BrowserConstants
import kotlinx.coroutines.launch
import java.util.UUID

class BrowserActivity : AppCompatActivity() {

    private val tabs = mutableListOf<BrowserTab>()
    private var currentTabIndex = 0
    private lateinit var adBlocker: AdBlocker
    private lateinit var privacyManager: PrivacyManager
    private lateinit var videoDownloadManager: VideoDownloadManager
    private lateinit var extensionManager: ExtensionManager
    private lateinit var devToolsManager: DevToolsManager

    private lateinit var toolbar: Toolbar
    private lateinit var urlBar: EditText
    private lateinit var btnBack: ImageButton
    private lateinit var btnForward: ImageButton
    private lateinit var btnRefresh: ImageButton
    private lateinit var webViewContainer: FrameLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var tabStrip: LinearLayout
    private lateinit var tabScrollView: HorizontalScrollView

    private val longPressHandler = Handler(Looper.getMainLooper())
    private var longPressRunnable: Runnable? = null
    private var isPrivateMode = false

    companion object {
        private const val PERMISSION_REQUEST_STORAGE = 100
        private const val MAX_DISPLAY_URL_LENGTH = 80
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupUI()

        adBlocker = AdBlocker(this)
        privacyManager = PrivacyManager(this)
        videoDownloadManager = VideoDownloadManager(this)
        extensionManager = ExtensionManager(this)
        devToolsManager = DevToolsManager(this, lifecycleScope)

        lifecycleScope.launch {
            adBlocker.initialize()
            extensionManager.loadExtensions()
        }

        val intentUrl = intent?.data?.toString() ?: BrowserConstants.HOME_URL
        createNewTab(intentUrl)
        requestStoragePermissions()
    }

    private fun setupUI() {
        val rootLayout = ConstraintLayout(this).apply {
            id = View.generateViewId()
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        setContentView(rootLayout)

        toolbar = Toolbar(this).apply {
            id = View.generateViewId()
            setBackgroundColor(ContextCompat.getColor(this@BrowserActivity, R.color.colorPrimary))
        }
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        val navRow = LinearLayout(this).apply {
            id = View.generateViewId()
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(ContextCompat.getColor(this@BrowserActivity, R.color.colorPrimary))
            setPadding(8, 8, 8, 8)
        }

        btnBack = ImageButton(this).apply {
            setImageDrawable(ContextCompat.getDrawable(this@BrowserActivity, R.drawable.ic_back))
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            contentDescription = "Back"
            setOnClickListener { getCurrentWebView()?.goBack() }
        }

        btnForward = ImageButton(this).apply {
            setImageDrawable(ContextCompat.getDrawable(this@BrowserActivity, R.drawable.ic_forward))
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            contentDescription = "Forward"
            setOnClickListener { getCurrentWebView()?.goForward() }
        }

        btnRefresh = ImageButton(this).apply {
            setImageDrawable(ContextCompat.getDrawable(this@BrowserActivity, R.drawable.ic_refresh))
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            contentDescription = "Refresh"
            setOnClickListener { getCurrentWebView()?.reload() }
        }

        urlBar = EditText(this).apply {
            id = View.generateViewId()
            hint = "Search or enter address"
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_GO
            setBackgroundResource(android.R.drawable.edit_text)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO) {
                    loadUrl(text.toString())
                    true
                } else false
            }
        }

        val homeBtn = ImageButton(this).apply {
            setImageDrawable(ContextCompat.getDrawable(this@BrowserActivity, R.drawable.ic_home))
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            contentDescription = "Home"
            setOnClickListener { getCurrentWebView()?.loadUrl(BrowserConstants.HOME_URL) }
        }

        navRow.addView(btnBack, LinearLayout.LayoutParams(80, 80))
        navRow.addView(btnForward, LinearLayout.LayoutParams(80, 80))
        navRow.addView(btnRefresh, LinearLayout.LayoutParams(80, 80))
        navRow.addView(urlBar, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        navRow.addView(homeBtn, LinearLayout.LayoutParams(80, 80))

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            id = View.generateViewId()
            max = 100
            visibility = View.GONE
        }

        tabScrollView = HorizontalScrollView(this).apply {
            id = View.generateViewId()
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(ContextCompat.getColor(this@BrowserActivity, R.color.colorPrimaryDark))
        }
        tabStrip = LinearLayout(this).apply {
            id = View.generateViewId()
            orientation = LinearLayout.HORIZONTAL
        }
        tabScrollView.addView(tabStrip)

        webViewContainer = FrameLayout(this).apply {
            id = View.generateViewId()
        }

        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        mainLayout.addView(toolbar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        mainLayout.addView(navRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        mainLayout.addView(progressBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 8))
        mainLayout.addView(tabScrollView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 100))
        mainLayout.addView(webViewContainer, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        rootLayout.addView(mainLayout, ConstraintLayout.LayoutParams(ConstraintLayout.LayoutParams.MATCH_PARENT, ConstraintLayout.LayoutParams.MATCH_PARENT))
    }

    private fun createNewTab(url: String, isPrivate: Boolean = isPrivateMode): BrowserTab {
        val tab = BrowserTab(
            id = UUID.randomUUID().toString(),
            title = "New Tab",
            url = url,
            isPrivate = isPrivate
        )
        val webView = createWebView(tab)
        tab.webView = webView
        tabs.add(tab)
        currentTabIndex = tabs.size - 1

        webViewContainer.removeAllViews()
        webViewContainer.addView(webView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        updateTabStrip()

        if (isPrivate) privacyManager.applyPrivacySettings(webView)
        webView.loadUrl(if (url.isBlank()) BrowserConstants.HOME_URL else url)
        return tab
    }

    private fun createWebView(tab: BrowserTab): WebView {
        val webView = WebView(this)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            loadWithOverviewMode = true
            useWideViewPort = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            userAgentString = BrowserConstants.USER_AGENT_MOBILE
            allowFileAccess = true
            javaScriptCanOpenWindowsAutomatically = true
            mediaPlaybackRequiresUserGesture = false
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (adBlocker.shouldBlock(request)) {
                    tab.blockedRequestCount++
                    tab.networkRequests.add(NetworkRequest(url = request.url.toString(), method = request.method ?: "GET", isBlocked = true))
                    devToolsManager.addNetworkRequest(tab, request.url.toString(), request.method ?: "GET", 0, "blocked", 0, 0)
                    return adBlocker.getBlockedResponse()
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                runOnUiThread {
                    urlBar.setText(url)
                    tab.url = url
                    progressBar.visibility = View.VISIBLE
                    progressBar.progress = 10
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                runOnUiThread {
                    progressBar.visibility = View.GONE
                    tab.url = url
                    tab.title = view.title ?: url
                    urlBar.setText(url)
                    updateTabStrip()
                    btnBack.isEnabled = view.canGoBack()
                    btnForward.isEnabled = view.canGoForward()
                }
                extensionManager.injectExtensions(view, url)
                devToolsManager.onPageFinished(tab)
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                return !url.startsWith("http://") && !url.startsWith("https://")
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                runOnUiThread {
                    progressBar.progress = newProgress
                    if (newProgress == 100) progressBar.visibility = View.GONE
                }
            }
            override fun onReceivedTitle(view: WebView, title: String) {
                tab.title = title
                runOnUiThread { updateTabStrip() }
            }
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                return super.onConsoleMessage(consoleMessage)
            }
        }

        setupVideoLongPress(webView, tab)
        return webView
    }

    private fun setupVideoLongPress(webView: WebView, tab: BrowserTab) {
        webView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    val x = event.x.toInt()
                    val y = event.y.toInt()
                    longPressRunnable?.let { longPressHandler.removeCallbacks(it) }
                    longPressRunnable = Runnable {
                        webView.evaluateJavascript("""
                            (function() {
                                var el = document.elementFromPoint($x, $y);
                                if (!el) return '';
                                if (el.tagName === 'VIDEO') return el.src || el.currentSrc || '';
                                var parent = el.closest ? el.closest('video') : null;
                                if (parent) return parent.src || parent.currentSrc || '';
                                var vids = document.querySelectorAll('video');
                                for (var i = 0; i < vids.length; i++) {
                                    var r = vids[i].getBoundingClientRect();
                                    if ($x >= r.left && $x <= r.right && $y >= r.top && $y <= r.bottom) {
                                        return vids[i].src || vids[i].currentSrc || '';
                                    }
                                }
                                return '';
                            })()
                        """.trimIndent()) { result ->
                            val videoUrl = result?.trim('"') ?: ""
                            if (videoUrl.isNotEmpty() && videoUrl != "null") {
                                showVideoDownloadDialog(videoUrl)
                            }
                        }
                    }
                    longPressHandler.postDelayed(longPressRunnable!!, BrowserConstants.LONG_PRESS_DURATION_MS)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_MOVE -> {
                    longPressRunnable?.let { longPressHandler.removeCallbacks(it) }
                }
            }
            false
        }
    }

    private fun showVideoDownloadDialog(videoUrl: String) {
        runOnUiThread {
            val dialogView = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(48, 32, 48, 32)
            }

            val messageText = TextView(this).apply {
                text = "Download this video?"
                textSize = 16f
            }
            val urlText = TextView(this).apply {
                text = if (videoUrl.length > MAX_DISPLAY_URL_LENGTH) videoUrl.take(MAX_DISPLAY_URL_LENGTH) + "..." else videoUrl
                textSize = 11f
                setTextColor(ContextCompat.getColor(this@BrowserActivity, android.R.color.darker_gray))
                setPadding(0, 8, 0, 8)
            }
            val filenameInput = EditText(this).apply {
                hint = "Filename (e.g. video.mp4)"
                setText("video_${System.currentTimeMillis()}.mp4")
            }

            dialogView.addView(messageText)
            dialogView.addView(urlText)
            dialogView.addView(filenameInput)

            AlertDialog.Builder(this)
                .setTitle("Video Download")
                .setView(dialogView)
                .setPositiveButton("Download") { _, _ ->
                    val filename = filenameInput.text.toString().ifBlank { "video.mp4" }
                    lifecycleScope.launch {
                        val result = videoDownloadManager.downloadVideo(videoUrl, filename)
                        result.onSuccess {
                            Toast.makeText(this@BrowserActivity, "Download started: $filename", Toast.LENGTH_SHORT).show()
                        }.onFailure { e ->
                            Toast.makeText(this@BrowserActivity, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun loadUrl(input: String) {
        val url = when {
            input.startsWith("http://") || input.startsWith("https://") -> input
            input.contains(".") && !input.contains(" ") -> "https://$input"
            else -> "https://www.google.com/search?q=${Uri.encode(input)}"
        }
        getCurrentWebView()?.loadUrl(url)
        urlBar.setText(url)
    }

    private fun getCurrentWebView(): WebView? =
        if (tabs.isNotEmpty() && currentTabIndex < tabs.size) tabs[currentTabIndex].webView else null

    private fun getCurrentTab(): BrowserTab? =
        if (tabs.isNotEmpty() && currentTabIndex < tabs.size) tabs[currentTabIndex] else null

    private fun switchToTab(index: Int) {
        if (index < 0 || index >= tabs.size) return
        currentTabIndex = index
        val tab = tabs[index]
        webViewContainer.removeAllViews()
        tab.webView?.let { wv ->
            webViewContainer.addView(wv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            urlBar.setText(tab.url)
        }
        updateTabStrip()
    }

    private fun closeTab(index: Int) {
        if (tabs.size <= 1) {
            tabs[0].webView?.loadUrl(BrowserConstants.HOME_URL)
            return
        }
        tabs[index].webView?.destroy()
        tabs.removeAt(index)
        currentTabIndex = minOf(currentTabIndex, tabs.size - 1)
        switchToTab(currentTabIndex)
    }

    private fun updateTabStrip() {
        tabStrip.removeAllViews()
        tabs.forEachIndexed { index, tab ->
            val tabView = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(16, 8, 8, 8)
                setBackgroundColor(
                    if (index == currentTabIndex)
                        ContextCompat.getColor(this@BrowserActivity, R.color.colorPrimary)
                    else
                        ContextCompat.getColor(this@BrowserActivity, R.color.colorPrimaryDark)
                )
            }
            val titleView = TextView(this).apply {
                text = (if (tab.isPrivate) "\uD83D\uDD12 " else "") + tab.title.take(15)
                setTextColor(android.graphics.Color.WHITE)
                textSize = 12f
                setOnClickListener { switchToTab(index) }
            }
            val closeBtn = TextView(this).apply {
                text = "\u00D7"
                setTextColor(android.graphics.Color.WHITE)
                textSize = 18f
                setPadding(8, 0, 8, 0)
                setOnClickListener { closeTab(index) }
            }
            tabView.addView(titleView)
            tabView.addView(closeBtn)
            tabStrip.addView(tabView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT).also { it.setMargins(4, 0, 4, 0) })
        }
        val addBtn = TextView(this).apply {
            text = " + "
            setTextColor(android.graphics.Color.WHITE)
            textSize = 20f
            setPadding(16, 8, 16, 8)
            setOnClickListener { createNewTab(BrowserConstants.HOME_URL) }
        }
        tabStrip.addView(addBtn)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "New Tab").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, 2, 0, if (isPrivateMode) "Exit Private Mode" else "Private Mode").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, 3, 0, "Extensions").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, 4, 0, "Developer Tools").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        menu.add(0, 5, 0, "Settings").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, 6, 0, "Downloads").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, 7, 0, if (adBlocker.isEnabled()) "Disable Ad Blocking" else "Enable Ad Blocking").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            1 -> { createNewTab(BrowserConstants.HOME_URL); true }
            2 -> { togglePrivateMode(); true }
            3 -> { showExtensionsDialog(); true }
            4 -> { toggleDevTools(); true }
            5 -> { showSettingsDialog(); true }
            6 -> { openDownloads(); true }
            7 -> { toggleAdBlocking(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun togglePrivateMode() {
        isPrivateMode = !isPrivateMode
        getCurrentWebView()?.let { wv ->
            if (isPrivateMode) privacyManager.enablePrivateMode(wv)
            else privacyManager.disablePrivateMode(wv)
        }
        Toast.makeText(this, if (isPrivateMode) "\uD83D\uDD12 Private mode ON" else "Private mode OFF", Toast.LENGTH_SHORT).show()
        invalidateOptionsMenu()
    }

    private fun toggleDevTools() {
        if (devToolsManager.isShowing()) devToolsManager.hide()
        else getCurrentTab()?.let { devToolsManager.show(it, webViewContainer) }
    }

    private fun toggleAdBlocking() {
        adBlocker.setEnabled(!adBlocker.isEnabled())
        Toast.makeText(this, if (adBlocker.isEnabled()) "Ad blocking ON" else "Ad blocking OFF", Toast.LENGTH_SHORT).show()
        invalidateOptionsMenu()
    }

    private fun showExtensionsDialog() {
        val extensions = extensionManager.getExtensions()
        if (extensions.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Extensions")
                .setMessage("No extensions loaded.\n\nPlace .crx, .xpi, or .zip files in:\n${getExternalFilesDir(null)}/Extensions/")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        val names = extensions.map { "${it.name} v${it.version} \u2014 ${if (it.isEnabled) "\u2713 enabled" else "\u2717 disabled"}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Extensions (tap to toggle)")
            .setItems(names) { _, i ->
                val ext = extensions[i]
                extensionManager.toggleExtension(ext.id, !ext.isEnabled)
                Toast.makeText(this, "${ext.name} ${if (!ext.isEnabled) "enabled" else "disabled"}", Toast.LENGTH_SHORT).show()
                showExtensionsDialog()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showSettingsDialog() {
        val options = arrayOf("Clear Cookies", "Clear Cache", "Toggle JavaScript", "Desktop Mode", "About")
        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setItems(options) { _, i ->
                when (i) {
                    0 -> { privacyManager.clearAllData(); Toast.makeText(this, "Cookies cleared", Toast.LENGTH_SHORT).show() }
                    1 -> { getCurrentWebView()?.clearCache(true); Toast.makeText(this, "Cache cleared", Toast.LENGTH_SHORT).show() }
                    2 -> {
                        getCurrentWebView()?.settings?.let { s ->
                            s.javaScriptEnabled = !s.javaScriptEnabled
                            Toast.makeText(this, "JavaScript ${if (s.javaScriptEnabled) "enabled" else "disabled"}", Toast.LENGTH_SHORT).show()
                        }
                    }
                    3 -> toggleDesktopMode()
                    4 -> AlertDialog.Builder(this).setTitle("Browser v1.0").setMessage("Full-featured browser\nBuilt with Kotlin and WebView\n\nFeatures:\n- Ad/Tracker blocking\n- Private browsing\n- Video download\n- Developer tools\n- Extension support").setPositiveButton("OK", null).show()
                }
            }
            .show()
    }

    private fun toggleDesktopMode() {
        getCurrentWebView()?.settings?.let { s ->
            val isMobile = s.userAgentString.contains("Mobile")
            s.userAgentString = if (isMobile) {
                BrowserConstants.USER_AGENT_DESKTOP
            } else {
                BrowserConstants.USER_AGENT_MOBILE
            }
            Toast.makeText(this, if (isMobile) "Desktop mode" else "Mobile mode", Toast.LENGTH_SHORT).show()
            getCurrentWebView()?.reload()
        }
    }

    private fun openDownloads() {
        startActivity(Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS))
    }

    private fun requestStoragePermissions() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                    arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE),
                    PERMISSION_REQUEST_STORAGE)
            }
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        val wv = getCurrentWebView()
        when {
            devToolsManager.isShowing() -> devToolsManager.hide()
            wv?.canGoBack() == true -> wv.goBack()
            tabs.size > 1 -> closeTab(currentTabIndex)
            else -> super.onBackPressed()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        longPressRunnable?.let { longPressHandler.removeCallbacks(it) }
        tabs.forEach { it.webView?.destroy() }
    }
}
