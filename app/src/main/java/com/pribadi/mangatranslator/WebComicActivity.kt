package com.pribadi.mangatranslator

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.pribadi.mangatranslator.databinding.ActivityWebComicBinding
import org.json.JSONArray
import java.io.ByteArrayInputStream
import java.io.FileInputStream
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * Built-in Web Comic Reader with Automatic Full-Chapter Panel Translation & Cross-Panel Batching:
 * 1. Scans all comic panel images in the chapter DOM (unlocking lazy-loaded images automatically).
 * 2. Downloads & OCRs panels in parallel, prioritizing the user's current viewport first.
 * 3. Groups multiple OCR'd panels together into Cross-Panel Batches (preserving story context
 *    and avoiding API rate-limits for both AI Comic mode and No-Key Multi-Engine mode).
 * 4. Replaces each panel image in-place via https://mangatranslator.local/translated/<file>.
 */
class WebComicActivity : AppCompatActivity() {

    companion object {
        private const val VIRTUAL_HOST = "mangatranslator.local"
        private const val DEFAULT_HOME_URL = "https://www.google.com/search?q=baca+manga+raw+chapter"

        private const val MAX_OCR_WORKERS = 3
        private const val BATCH_COALESCE_MS = 220L
        private const val MAX_VISION_PANELS_PER_BATCH = 3
        private const val MAX_TEXT_PANELS_PER_BATCH = 5
        private const val MAX_BUBBLES_PER_BATCH = 32

        private val AD_BLOCK_KEYWORDS = listOf(
            "doubleclick.net",
            "googlesyndication.com",
            "popads.net",
            "popcash.net",
            "exoclick.com",
            "juicyads.com",
            "trafficjunky.net",
            "adsterra",
            "propellerads",
            "onclickads",
            "hilltopads"
        )
    }

    private data class PanelTask(
        val domId: String,
        val imageUrl: String,
        var topY: Float,
        @Volatile var status: Int = STATUS_PENDING, // 0=pending, 1=running, 2=done, 3=skipped
        @Volatile var translatedFile: String? = null
    ) {
        companion object {
            const val STATUS_PENDING = 0
            const val STATUS_RUNNING = 1
            const val STATUS_DONE = 2
            const val STATUS_SKIPPED = 3
        }
    }

    private data class QueuedOcrPanel(
        val task: PanelTask,
        val generation: Int,
        val ready: PanelImageTranslator.PrepareResult.Ready,
        val bgStyle: Int,
        val textScale: Float
    )

    private lateinit var binding: ActivityWebComicBinding
    private lateinit var prefs: SharedPreferences
    private lateinit var comicTranslator: ComicTranslator
    private lateinit var panelTranslator: PanelImageTranslator

    private val mainHandler = Handler(Looper.getMainLooper())
    private val panelWorkerPool = Executors.newFixedThreadPool(MAX_OCR_WORKERS)
    private val batchTranslatorExecutor = Executors.newSingleThreadExecutor()
    private val chapterGeneration = AtomicInteger(0)

    private val panelMap = ConcurrentHashMap<String, PanelTask>()
    private val queueLock = Object()
    private var activeOcrWorkers = 0

    private val batchLock = Object()
    private val readyOcrQueue = ArrayList<QueuedOcrPanel>()
    @Volatile
    private var isBatchLoopRunning = false

    @Volatile
    private var currentScrollY: Float = 0f
    @Volatile
    private var currentViewportH: Float = 1600f
    @Volatile
    private var isAutoChapterEnabled = true
    @Volatile
    private var showingOriginal = false

    private var currentLangIndex = 0
    private var bgStyle = OverlayView.STYLE_SEAMLESS_WHITE
    private var textScale = 1.0f
    private var comicStyle = TranslatorPrefs.STYLE_NATURAL
    private var currentPageUrl = ""
    private var webUserAgent = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWebComicBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = TranslatorPrefs.prefs(this)
        loadPreferences()

        comicTranslator = ComicTranslator(this).apply {
            preWarm(currentLangIndex)
        }
        panelTranslator = PanelImageTranslator(this, comicTranslator)

        setupToolbarAndControls()
        setupWebView()
        setupBackNavigation()

        val startUrl = prefs.getString(TranslatorPrefs.KEY_LAST_URL, DEFAULT_HOME_URL) ?: DEFAULT_HOME_URL
        loadInputOrUrl(startUrl)
    }

    override fun onResume() {
        super.onResume()
        loadPreferences()
        updateLangUI()
        updateStyleUI()
        updateBgUI()
        updateProgressBanner()
    }

    private fun loadPreferences() {
        currentLangIndex = TranslatorPrefs.getLangIndex(this)
        bgStyle = TranslatorPrefs.getBgStyle(this)
        textScale = TranslatorPrefs.getTextScale(this)
        comicStyle = TranslatorPrefs.getComicStyle(this)
    }

    private fun setupToolbarAndControls() {
        updateLangUI()
        updateStyleUI()
        updateBgUI()
        updateAutoChapterUI()

        binding.btnCloseReader.setOnClickListener {
            finish()
        }

        binding.btnGoUrl.setOnClickListener {
            val input = binding.etUrlBar.text?.toString()?.trim().orEmpty()
            if (input.isNotEmpty()) {
                hideKeyboard()
                loadInputOrUrl(input)
            }
        }

        binding.etUrlBar.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO ||
                actionId == EditorInfo.IME_ACTION_SEARCH ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                val input = binding.etUrlBar.text?.toString()?.trim().orEmpty()
                if (input.isNotEmpty()) {
                    hideKeyboard()
                    loadInputOrUrl(input)
                }
                true
            } else {
                false
            }
        }

        binding.btnWebBack.setOnClickListener {
            if (binding.webViewComic.canGoBack()) {
                binding.webViewComic.goBack()
            }
        }

        binding.btnWebForward.setOnClickListener {
            if (binding.webViewComic.canGoForward()) {
                binding.webViewComic.goForward()
            }
        }

        binding.btnReaderLang.setOnClickListener {
            currentLangIndex = (currentLangIndex + 1) % 4
            TranslatorPrefs.setLangIndex(this, currentLangIndex)
            updateLangUI()
            comicTranslator.preWarm(currentLangIndex)
            val langName = when (currentLangIndex) {
                0 -> "Jepang (JA)"
                1 -> "Korea (KO)"
                2 -> "Inggris (EN)"
                else -> "Mandarin (ZH)"
            }
            Toast.makeText(this, "Bahasa komik: $langName", Toast.LENGTH_SHORT).show()
            retranslateCurrentChapter()
        }

        binding.btnReaderStyle.setOnClickListener {
            comicStyle = if (comicStyle == TranslatorPrefs.STYLE_NATURAL) {
                TranslatorPrefs.STYLE_CASUAL
            } else {
                TranslatorPrefs.STYLE_NATURAL
            }
            TranslatorPrefs.setComicStyle(this, comicStyle)
            updateStyleUI()
            Toast.makeText(
                this,
                "Gaya bahasa: ${TranslatorPrefs.getStyleFullLabel(comicStyle)}",
                Toast.LENGTH_SHORT
            ).show()
            retranslateCurrentChapter()
        }

        binding.btnReaderBg.setOnClickListener {
            bgStyle = if (bgStyle == OverlayView.STYLE_SEAMLESS_WHITE) {
                OverlayView.STYLE_TRANSPARENT
            } else {
                OverlayView.STYLE_SEAMLESS_WHITE
            }
            TranslatorPrefs.setBgStyle(this, bgStyle)
            updateBgUI()
            retranslateCurrentChapter()
        }

        binding.btnAutoChapter.setOnClickListener {
            isAutoChapterEnabled = !isAutoChapterEnabled
            updateAutoChapterUI()
            if (isAutoChapterEnabled) {
                Toast.makeText(
                    this,
                    "Auto Chapter AKTIF: Seluruh panel halaman diterjemahkan otomatis",
                    Toast.LENGTH_SHORT
                ).show()
                triggerDomPanelScan()
            } else {
                resetPendingChapterWork()
                binding.llChapterProgress.visibility = View.GONE
                Toast.makeText(this, "Auto Chapter NONAKTIF", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnScanChapterNow.setOnClickListener {
            showingOriginal = false
            binding.tvToggleOrig.text = "Lihat Asli"
            Toast.makeText(
                this,
                "Memindai ulang (${comicTranslator.getConfiguredEngineLabel()})...",
                Toast.LENGTH_SHORT
            ).show()
            triggerDomPanelScan(forceRescan = true)
        }

        binding.tvToggleOrig.setOnClickListener {
            showingOriginal = !showingOriginal
            binding.tvToggleOrig.text = if (showingOriginal) "Lihat ID" else "Lihat Asli"
            val flag = if (showingOriginal) "true" else "false"
            binding.webViewComic.evaluateJavascript(
                "if(window.__mtToggleOriginal) window.__mtToggleOriginal($flag);",
                null
            )
        }
    }

    private fun updateLangUI() {
        binding.btnReaderLang.text = when (currentLangIndex) {
            0 -> "JA"
            1 -> "KO"
            2 -> "EN"
            else -> "ZH"
        }
    }

    private fun updateStyleUI() {
        if (comicStyle == TranslatorPrefs.STYLE_CASUAL) {
            binding.btnReaderStyle.text = "GAUL"
            binding.btnReaderStyle.setTextColor(0xFFFFD54F.toInt())
        } else {
            binding.btnReaderStyle.text = "NAT"
            binding.btnReaderStyle.setTextColor(0xFF80CBC4.toInt())
        }
    }

    private fun updateBgUI() {
        if (bgStyle == OverlayView.STYLE_SEAMLESS_WHITE) {
            binding.btnReaderBg.setBackgroundResource(R.drawable.bg_style_toggle_on)
            binding.btnReaderBg.text = "PUTIH"
        } else {
            binding.btnReaderBg.setBackgroundResource(R.drawable.bg_auto_toggle_off)
            binding.btnReaderBg.text = "TRANS"
        }
    }

    private fun updateAutoChapterUI() {
        if (isAutoChapterEnabled) {
            binding.btnAutoChapter.setBackgroundResource(R.drawable.bg_auto_toggle_on)
            binding.btnAutoChapter.text = "AUTO: ON"
        } else {
            binding.btnAutoChapter.setBackgroundResource(R.drawable.bg_auto_toggle_off)
            binding.btnAutoChapter.text = "AUTO: OFF"
        }
    }

    private fun resetPendingChapterWork() {
        chapterGeneration.incrementAndGet()
        synchronized(batchLock) {
            for (queued in readyOcrQueue) {
                if (!queued.ready.bitmap.isRecycled) {
                    queued.ready.bitmap.recycle()
                }
            }
            readyOcrQueue.clear()
            batchLock.notifyAll()
        }
    }

    private fun retranslateCurrentChapter() {
        showingOriginal = false
        binding.tvToggleOrig.text = "Lihat Asli"
        resetPendingChapterWork()
        panelMap.clear()
        binding.webViewComic.evaluateJavascript(
            "if(window.__mtResetPanels) window.__mtResetPanels();",
            null
        )
        triggerDomPanelScan(forceRescan = true)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(binding.webViewComic, true)

        binding.webViewComic.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadsImagesAutomatically = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
            webUserAgent = userAgentString
        }

        binding.webViewComic.addJavascriptInterface(JsPanelBridge(), "MangaBridge")

        binding.webViewComic.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress in 1..99) {
                    binding.pbWebLoad.visibility = View.VISIBLE
                    binding.pbWebLoad.progress = newProgress
                } else {
                    binding.pbWebLoad.visibility = View.GONE
                }
            }

            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?
            ): Boolean = false
        }

        binding.webViewComic.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                if (!url.isNullOrBlank()) {
                    currentPageUrl = url
                    binding.etUrlBar.setText(url)
                    if (url.startsWith("http")) {
                        prefs.edit().putString(TranslatorPrefs.KEY_LAST_URL, url).apply()
                    }
                }
                resetPendingChapterWork()
                panelMap.clear()
                showingOriginal = false
                binding.tvToggleOrig.text = "Lihat Asli"
                binding.llChapterProgress.visibility = View.GONE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (!url.isNullOrBlank()) {
                    currentPageUrl = url
                }
                injectPanelScannerJs()
                if (isAutoChapterEnabled) {
                    mainHandler.postDelayed({ triggerDomPanelScan() }, 450L)
                    mainHandler.postDelayed({ triggerDomPanelScan() }, 1800L)
                }
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val uri = request?.url ?: return null
                val host = uri.host?.lowercase().orEmpty()

                if (host == VIRTUAL_HOST && uri.path?.startsWith("/translated/") == true) {
                    val fileName = uri.lastPathSegment.orEmpty()
                    val file = panelTranslator.getCachedFile(fileName)
                    if (file != null) {
                        val headers = mapOf(
                            "Access-Control-Allow-Origin" to "*",
                            "Cache-Control" to "public, max-age=3600"
                        )
                        return WebResourceResponse(
                            "image/jpeg",
                            "binary",
                            200,
                            "OK",
                            headers,
                            FileInputStream(file)
                        )
                    }
                }

                val urlStr = uri.toString().lowercase()
                if (AD_BLOCK_KEYWORDS.any { urlStr.contains(it) }) {
                    return WebResourceResponse(
                        "text/plain",
                        "utf-8",
                        ByteArrayInputStream(ByteArray(0))
                    )
                }

                return super.shouldInterceptRequest(view, request)
            }
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.webViewComic.canGoBack()) {
                    binding.webViewComic.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun loadInputOrUrl(rawInput: String) {
        val trimmed = rawInput.trim()
        val finalUrl = when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            trimmed.contains(".") && !trimmed.contains(" ") -> "https://$trimmed"
            else -> "https://www.google.com/search?q=" + URLEncoder.encode(trimmed, "UTF-8")
        }
        binding.etUrlBar.setText(finalUrl)
        binding.webViewComic.loadUrl(finalUrl)
    }

    private fun triggerDomPanelScan(forceRescan: Boolean = false) {
        val forceArg = if (forceRescan) "true" else "false"
        injectPanelScannerJs()
        binding.webViewComic.evaluateJavascript(
            "if(window.__mtScanPanels) window.__mtScanPanels($forceArg);",
            null
        )
    }

    private fun injectPanelScannerJs() {
        val js = """
            (function() {
                if (window.__mtInitialized) return;
                window.__mtInitialized = true;
                window.__mtCounter = 0;

                const LAZY_ATTRS = ['data-src', 'data-lazy-src', 'data-original', 'data-url', 'data-echo', 'data-cfsrc', 'real-src'];
                const SKIP_PATTERNS = /logo|avatar|icon|favicon|emoji|banner|ads|doubleclick|1x1|pixel|\.svg/i;

                function resolveBestUrl(img) {
                    if (img.dataset.mtOrigSrc) return img.dataset.mtOrigSrc;
                    for (let i = 0; i < LAZY_ATTRS.length; i++) {
                        let v = img.getAttribute(LAZY_ATTRS[i]);
                        if (v && v.trim().length > 8 && !v.startsWith('data:image/gif') && !v.startsWith('data:image/svg')) {
                            let u = v.trim();
                            if (u.startsWith('//')) u = window.location.protocol + u;
                            else if (u.startsWith('/')) u = window.location.origin + u;
                            return u;
                        }
                    }
                    let src = img.currentSrc || img.src || '';
                    if (src.startsWith('https://mangatranslator.local/')) return img.dataset.mtOrigSrc || '';
                    return src;
                }

                window.__mtScanPanels = function(force) {
                    const imgs = document.querySelectorAll('img');
                    const found = [];
                    for (let i = 0; i < imgs.length; i++) {
                        const img = imgs[i];
                        if (force) {
                            delete img.dataset.mtQueued;
                        }
                        if (img.loading === 'lazy') img.loading = 'eager';
                        const url = resolveBestUrl(img);
                        if (!url || url.length < 8 || SKIP_PATTERNS.test(url)) continue;
                        if (url.startsWith('data:image/gif') || url.startsWith('data:image/svg')) continue;

                        const rect = img.getBoundingClientRect();
                        const w = Math.max(rect.width || 0, img.naturalWidth || 0, parseInt(img.getAttribute('width') || '0'));
                        const h = Math.max(rect.height || 0, img.naturalHeight || 0, parseInt(img.getAttribute('height') || '0'));

                        if (img.complete && img.naturalWidth > 0 && (img.naturalWidth < 180 || img.naturalHeight < 140)) {
                            continue;
                        }
                        if (w > 0 && w < 150 && h > 0 && h < 120) continue;

                        if ((!img.src || img.src.startsWith('data:')) && url.startsWith('http')) {
                            img.src = url;
                        }

                        if (!img.dataset.mtId) {
                            img.dataset.mtId = 'mt_panel_' + (++window.__mtCounter);
                        }
                        if (!img.dataset.mtOrigSrc) {
                            img.dataset.mtOrigSrc = url;
                        }

                        const absTop = (rect.top || 0) + (window.scrollY || window.pageYOffset || 0);
                        found.push({
                            id: img.dataset.mtId,
                            url: url,
                            top: Math.round(absTop)
                        });
                    }
                    if (window.MangaBridge) {
                        window.MangaBridge.onPanelsDiscovered(
                            JSON.stringify(found),
                            Math.round(window.scrollY || 0),
                            Math.round(window.innerHeight || 1600),
                            !!force
                        );
                    }
                };

                window.__mtApplyTranslated = function(panelId, transUrl) {
                    const img = document.querySelector('img[data-mt-id="' + panelId + '"]');
                    if (!img) return;
                    if (!img.dataset.mtOrigSrc) {
                        img.dataset.mtOrigSrc = img.currentSrc || img.src;
                    }
                    img.dataset.mtTransSrc = transUrl;
                    img.removeAttribute('srcset');
                    img.removeAttribute('data-srcset');
                    if (img.parentElement && img.parentElement.tagName === 'PICTURE') {
                        const sources = img.parentElement.querySelectorAll('source');
                        for (let s = 0; s < sources.length; s++) {
                            sources[s].removeAttribute('srcset');
                        }
                    }
                    img.src = transUrl;
                };

                window.__mtToggleOriginal = function(showOrig) {
                    const imgs = document.querySelectorAll('img[data-mt-trans-src]');
                    for (let i = 0; i < imgs.length; i++) {
                        const img = imgs[i];
                        img.src = showOrig ? (img.dataset.mtOrigSrc || img.src) : img.dataset.mtTransSrc;
                    }
                };

                window.__mtResetPanels = function() {
                    const imgs = document.querySelectorAll('img[data-mt-orig-src]');
                    for (let i = 0; i < imgs.length; i++) {
                        const img = imgs[i];
                        img.src = img.dataset.mtOrigSrc;
                        delete img.dataset.mtTransSrc;
                        delete img.dataset.mtQueued;
                    }
                };

                let scrollTimer = null;
                window.addEventListener('scroll', function() {
                    if (scrollTimer) clearTimeout(scrollTimer);
                    scrollTimer = setTimeout(function() {
                        if (window.MangaBridge) {
                            window.MangaBridge.onViewportScrolled(
                                Math.round(window.scrollY || 0),
                                Math.round(window.innerHeight || 1600)
                            );
                        }
                    }, 180);
                }, { passive: true });

                let mutTimer = null;
                const obs = new MutationObserver(function() {
                    if (mutTimer) clearTimeout(mutTimer);
                    mutTimer = setTimeout(function() {
                        window.__mtScanPanels(false);
                    }, 900);
                });
                if (document.body) {
                    obs.observe(document.body, { childList: true, subtree: true });
                }
            })();
        """.trimIndent()

        binding.webViewComic.evaluateJavascript(js, null)
    }

    inner class JsPanelBridge {
        @JavascriptInterface
        fun onPanelsDiscovered(jsonStr: String, scrollY: Int, viewportH: Int, forced: Boolean) {
            if (!isAutoChapterEnabled && !forced) return
            currentScrollY = scrollY.toFloat()
            currentViewportH = viewportH.coerceAtLeast(400).toFloat()

            try {
                val arr = JSONArray(jsonStr)
                var newlyAdded = 0
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val id = obj.optString("id", "")
                    val url = obj.optString("url", "")
                    val top = obj.optDouble("top", 0.0).toFloat()
                    if (id.isEmpty() || url.isEmpty()) continue

                    val existing = panelMap[id]
                    if (existing == null) {
                        panelMap[id] = PanelTask(id, url, top)
                        newlyAdded++
                    } else {
                        existing.topY = top
                        if (forced && existing.status == PanelTask.STATUS_SKIPPED) {
                            existing.status = PanelTask.STATUS_PENDING
                            newlyAdded++
                        }
                    }
                }

                if (newlyAdded > 0 || forced) {
                    mainHandler.post { updateProgressBanner() }
                    pumpWorkers(chapterGeneration.get())
                }
            } catch (_: Exception) {
            }
        }

        @JavascriptInterface
        fun onViewportScrolled(scrollY: Int, viewportH: Int) {
            currentScrollY = scrollY.toFloat()
            currentViewportH = viewportH.coerceAtLeast(400).toFloat()
        }
    }

    /**
     * Stage 1: Smart Priority Queue Download + OCR Workers.
     * Downloads & OCRs viewport panels first, then hands Ready panels to the Cross-Panel Batcher.
     */
    private fun pumpWorkers(gen: Int) {
        synchronized(queueLock) {
            while (activeOcrWorkers < MAX_OCR_WORKERS) {
                val nextTask = pickNextPriorityPanel() ?: break
                nextTask.status = PanelTask.STATUS_RUNNING
                activeOcrWorkers++

                val pageUrlSnapshot = currentPageUrl
                val cookieSnapshot = try {
                    CookieManager.getInstance().getCookie(nextTask.imageUrl)
                        ?: CookieManager.getInstance().getCookie(pageUrlSnapshot)
                } catch (_: Exception) {
                    null
                }
                val uaSnapshot = webUserAgent
                val langSnapshot = currentLangIndex
                val bgSnapshot = bgStyle
                val scaleSnapshot = textScale

                panelWorkerPool.execute {
                    try {
                        val prep = panelTranslator.preparePanelForTranslation(
                            imageUrl = nextTask.imageUrl,
                            pageUrl = pageUrlSnapshot,
                            cookie = cookieSnapshot,
                            userAgent = uaSnapshot,
                            langIndex = langSnapshot,
                            bgStyle = bgSnapshot,
                            textScale = scaleSnapshot,
                            isCancelled = { gen != chapterGeneration.get() }
                        )

                        if (gen != chapterGeneration.get()) {
                            if (prep is PanelImageTranslator.PrepareResult.Ready && !prep.bitmap.isRecycled) {
                                prep.bitmap.recycle()
                            }
                        } else {
                            when (prep) {
                                is PanelImageTranslator.PrepareResult.Cached -> {
                                    applyCompletedPanel(nextTask, prep.fileName, gen)
                                }
                                is PanelImageTranslator.PrepareResult.Empty,
                                is PanelImageTranslator.PrepareResult.Failed -> {
                                    nextTask.status = PanelTask.STATUS_SKIPPED
                                    mainHandler.post { updateProgressBanner() }
                                }
                                is PanelImageTranslator.PrepareResult.Ready -> {
                                    enqueueForCrossPanelBatch(
                                        QueuedOcrPanel(nextTask, gen, prep, bgSnapshot, scaleSnapshot)
                                    )
                                }
                            }
                        }
                    } finally {
                        synchronized(queueLock) {
                            activeOcrWorkers = (activeOcrWorkers - 1).coerceAtLeast(0)
                        }
                        synchronized(batchLock) {
                            batchLock.notifyAll()
                        }
                        if (gen == chapterGeneration.get()) {
                            pumpWorkers(gen)
                        }
                    }
                }
            }
        }
    }

    private fun enqueueForCrossPanelBatch(item: QueuedOcrPanel) {
        synchronized(batchLock) {
            if (item.generation != chapterGeneration.get()) {
                if (!item.ready.bitmap.isRecycled) item.ready.bitmap.recycle()
                return
            }
            readyOcrQueue.add(item)
            if (!isBatchLoopRunning) {
                isBatchLoopRunning = true
                batchTranslatorExecutor.execute { runCrossPanelBatchLoop() }
            }
            batchLock.notifyAll()
        }
    }

    /**
     * Stage 2: Cross-Panel Batch Dispatcher.
     * Coalesces multiple OCR'd panels into 1 combined translation call (AI or Multi-Engine)
     * so character pronouns & story context remain consistent while cutting API calls by 3x-5x.
     */
    private fun runCrossPanelBatchLoop() {
        while (true) {
            val batchToProcess: List<QueuedOcrPanel>
            synchronized(batchLock) {
                if (readyOcrQueue.isEmpty()) {
                    isBatchLoopRunning = false
                    return
                }

                val maxPanels = if (comicTranslator.isGeminiVisionReady()) {
                    MAX_VISION_PANELS_PER_BATCH
                } else {
                    MAX_TEXT_PANELS_PER_BATCH
                }

                // Coalesce briefly so parallel OCR workers can fill the batch together
                val deadline = System.currentTimeMillis() + BATCH_COALESCE_MS
                while (readyOcrQueue.size < maxPanels && hasMoreOcrWorkInProgress()) {
                    val waitMs = deadline - System.currentTimeMillis()
                    if (waitMs <= 0L) break
                    try {
                        batchLock.wait(waitMs)
                    } catch (_: InterruptedException) {
                        break
                    }
                }

                val curGen = chapterGeneration.get()
                val valid = ArrayList<QueuedOcrPanel>()
                val iter = readyOcrQueue.iterator()
                while (iter.hasNext()) {
                    val q = iter.next()
                    if (q.generation != curGen) {
                        if (!q.ready.bitmap.isRecycled) q.ready.bitmap.recycle()
                        iter.remove()
                    } else {
                        valid.add(q)
                    }
                }

                if (valid.isEmpty()) {
                    isBatchLoopRunning = false
                    return
                }

                // Sort by vertical reading position so AI sees Panel 1 -> Panel 2 -> Panel 3 in order
                val sorted = valid.sortedBy { it.task.topY }
                val picked = ArrayList<QueuedOcrPanel>(maxPanels)
                var bubbleSum = 0
                for (q in sorted) {
                    val bCount = q.ready.requests.size
                    if (picked.isNotEmpty() &&
                        (picked.size >= maxPanels || bubbleSum + bCount > MAX_BUBBLES_PER_BATCH)
                    ) {
                        break
                    }
                    picked.add(q)
                    bubbleSum += bCount
                }

                readyOcrQueue.removeAll(picked.toSet())
                batchToProcess = picked
            }

            processCrossPanelBatch(batchToProcess)
        }
    }

    private fun hasMoreOcrWorkInProgress(): Boolean {
        val pendingExists = panelMap.values.any { it.status == PanelTask.STATUS_PENDING }
        if (pendingExists) return true
        synchronized(queueLock) {
            return activeOcrWorkers > 0
        }
    }

    private fun processCrossPanelBatch(batch: List<QueuedOcrPanel>) {
        if (batch.isEmpty()) return
        val gen = batch[0].generation

        if (gen != chapterGeneration.get()) {
            for (q in batch) {
                if (!q.ready.bitmap.isRecycled) q.ready.bitmap.recycle()
            }
            return
        }

        val inputs = batch.map { q ->
            ComicTranslator.PanelBatchInput(
                bubbles = q.ready.requests,
                panelJpegBytes = q.ready.visionJpegBytes
            )
        }

        val translatedMatrix = comicTranslator.translateMultiPanelBatchSync(inputs)

        for (i in batch.indices) {
            val item = batch[i]
            val panelTranslations = translatedMatrix.getOrNull(i).orEmpty()
            val fileName = panelTranslator.bakeAndSavePanel(
                ready = item.ready,
                translatedTexts = panelTranslations,
                bgStyle = item.bgStyle,
                textScale = item.textScale,
                isCancelled = { gen != chapterGeneration.get() }
            )

            if (gen == chapterGeneration.get()) {
                if (!fileName.isNullOrEmpty()) {
                    applyCompletedPanel(item.task, fileName, gen)
                } else {
                    item.task.status = PanelTask.STATUS_SKIPPED
                    mainHandler.post { updateProgressBanner() }
                }
            }
        }
    }

    private fun applyCompletedPanel(task: PanelTask, fileName: String, gen: Int) {
        task.translatedFile = fileName
        task.status = PanelTask.STATUS_DONE
        val servedUrl = "https://$VIRTUAL_HOST/translated/$fileName"
        mainHandler.post {
            if (!showingOriginal && gen == chapterGeneration.get()) {
                binding.webViewComic.evaluateJavascript(
                    "if(window.__mtApplyTranslated) window.__mtApplyTranslated('${task.domId}', '$servedUrl');",
                    null
                )
            }
            updateProgressBanner()
        }
    }

    private fun pickNextPriorityPanel(): PanelTask? {
        val viewTop = currentScrollY
        val viewBottom = viewTop + currentViewportH

        var best: PanelTask? = null
        var bestScore = Float.MAX_VALUE

        for (task in panelMap.values) {
            if (task.status != PanelTask.STATUS_PENDING) continue

            val score = when {
                task.topY in (viewTop - 400f)..(viewBottom + 900f) ->
                    (task.topY - (viewTop - 400f)).coerceAtLeast(0f)
                task.topY > viewBottom + 900f ->
                    10_000f + (task.topY - viewBottom)
                else ->
                    1_000_000f + abs(viewTop - task.topY)
            }
            if (score < bestScore) {
                bestScore = score
                best = task
            }
        }
        return best
    }

    private fun updateProgressBanner() {
        val all = panelMap.values.toList()
        if (all.isEmpty()) {
            binding.llChapterProgress.visibility = View.GONE
            return
        }

        val total = all.size
        val finished = all.count {
            it.status == PanelTask.STATUS_DONE || it.status == PanelTask.STATUS_SKIPPED
        }
        val translatedCount = all.count { it.status == PanelTask.STATUS_DONE }
        val engineLabel = if (finished > 0) comicTranslator.lastUsedEngineLabel else comicTranslator.getConfiguredEngineLabel()
        val styleLabel = TranslatorPrefs.getStyleShortLabel(comicStyle)

        binding.llChapterProgress.visibility = View.VISIBLE
        if (finished < total) {
            binding.pbPanelSpinner.visibility = View.VISIBLE
            binding.tvChapterProgress.text =
                "[$engineLabel • $styleLabel] Menerjemahkan $finished / $total panel..."
        } else {
            binding.pbPanelSpinner.visibility = View.GONE
            binding.tvChapterProgress.text =
                "Selesai [$engineLabel • $styleLabel]! $translatedCount panel diterjemahkan ✓"
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(binding.etUrlBar.windowToken, 0)
    }

    override fun onDestroy() {
        resetPendingChapterWork()
        mainHandler.removeCallbacksAndMessages(null)
        panelWorkerPool.shutdownNow()
        batchTranslatorExecutor.shutdownNow()
        comicTranslator.shutdown()
        binding.webViewComic.stopLoading()
        binding.webViewComic.destroy()
        super.onDestroy()
    }
}
