package com.pribadi.mangatranslator

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.http.SslError
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.pribadi.mangatranslator.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var isTranslating = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupWebView()
        setupTopBar()
        setupFloatingActionButtons()
        setupBackHandler()

        // Load initial default URL
        loadUrlFromInput()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val webSettings: WebSettings = binding.webView.settings
        webSettings.javaScriptEnabled = true
        webSettings.domStorageEnabled = true
        webSettings.databaseEnabled = true
        webSettings.loadWithOverviewMode = true
        webSettings.useWideViewPort = true
        webSettings.builtInZoomControls = true
        webSettings.displayZoomControls = false
        webSettings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        webSettings.userAgentString =
            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(binding.webView, true)

        binding.webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString()
                if (url != null) {
                    view?.loadUrl(url)
                }
                return true
            }

            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                handler?.proceed()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (!binding.etUrl.hasFocus() && !url.isNullOrEmpty()) {
                    binding.etUrl.setText(url)
                }
            }
        }
        binding.webView.webChromeClient = WebChromeClient()

        // Clear overlay when page is scrolled more than 10px
        binding.webView.setOnScrollChangeListener { _, scrollX, scrollY, oldScrollX, oldScrollY ->
            if (Math.abs(scrollX - oldScrollX) > 10 || Math.abs(scrollY - oldScrollY) > 10) {
                binding.overlayView.clear()
            }
        }
    }

    private fun setupTopBar() {
        val languages = arrayOf("Jepang", "Korea", "Inggris")
        val adapter = ArrayAdapter(this, R.layout.spinner_item, languages)
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
        binding.spinnerLang.adapter = adapter

        binding.btnGo.setOnClickListener {
            loadUrlFromInput()
        }

        binding.etUrl.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                loadUrlFromInput()
                true
            } else {
                false
            }
        }
    }

    private fun setupFloatingActionButtons() {
        // Toggle top bar visibility for fullscreen reading
        binding.fabToggleBar.setOnClickListener {
            binding.topBar.visibility = if (binding.topBar.visibility == View.VISIBLE) {
                View.GONE
            } else {
                View.VISIBLE
            }
        }

        // Clear translation overlay
        binding.fabClear.setOnClickListener {
            binding.overlayView.clear()
        }

        // Main translation trigger
        binding.fabTranslate.setOnClickListener {
            if (!isTranslating) {
                processScreenTranslation()
            } else {
                Toast.makeText(this, "Sedang menerjemahkan, mohon tunggu...", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupBackHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.webView.canGoBack()) {
                    binding.overlayView.clear()
                    binding.webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun loadUrlFromInput() {
        hideKeyboard()
        val rawUrl = binding.etUrl.text.toString().trim()
        if (rawUrl.isEmpty()) {
            Toast.makeText(this, getString(R.string.error_empty_url), Toast.LENGTH_SHORT).show()
            return
        }

        val targetUrl = if (!rawUrl.startsWith("http://") && !rawUrl.startsWith("https://")) {
            "https://$rawUrl"
        } else {
            rawUrl
        }

        binding.overlayView.clear()
        binding.webView.loadUrl(targetUrl)
    }

    private fun captureWebView(webView: WebView): Bitmap? {
        if (webView.width <= 0 || webView.height <= 0) return null
        return try {
            val bitmap = Bitmap.createBitmap(webView.width, webView.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            webView.draw(canvas)
            bitmap
        } catch (e: Exception) {
            null
        }
    }

    private fun processScreenTranslation() {
        isTranslating = true
        showLoading(getString(R.string.status_capturing))

        val bitmap = captureWebView(binding.webView)
        if (bitmap == null) {
            hideLoading()
            isTranslating = false
            Toast.makeText(this, getString(R.string.status_failed), Toast.LENGTH_SHORT).show()
            return
        }

        val (recognizer: TextRecognizer, sourceLangCode: String) = when (binding.spinnerLang.selectedItemPosition) {
            0 -> Pair(
                TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build()),
                TranslateLanguage.JAPANESE
            )
            1 -> Pair(
                TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build()),
                TranslateLanguage.KOREAN
            )
            else -> Pair(
                TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS),
                TranslateLanguage.ENGLISH
            )
        }

        showLoading(getString(R.string.status_ocr))
        val inputImage = InputImage.fromBitmap(bitmap, 0)

        recognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                // Filter text blocks: size must be >= 15px and contain valid characters
                val validBlocks = visionText.textBlocks.filter { block ->
                    val box = block.boundingBox ?: return@filter false
                    if (box.width() < 15 || box.height() < 15) return@filter false
                    val cleanText = block.text.replace("\n", " ").trim()
                    cleanText.any { it.isLetter() }
                }

                if (validBlocks.isEmpty()) {
                    hideLoading()
                    recognizer.close()
                    isTranslating = false
                    Toast.makeText(this, getString(R.string.status_no_text), Toast.LENGTH_SHORT).show()
                    return@addOnSuccessListener
                }

                showLoading(getString(R.string.status_downloading_model))

                val translatorOptions = TranslatorOptions.Builder()
                    .setSourceLanguage(sourceLangCode)
                    .setTargetLanguage(TranslateLanguage.INDONESIAN)
                    .build()

                val translator = Translation.getClient(translatorOptions)
                val conditions = DownloadConditions.Builder().build()

                translator.downloadModelIfNeeded(conditions)
                    .addOnSuccessListener {
                        showLoading(getString(R.string.status_translating))

                        val tasks = validBlocks.map { block ->
                            val cleanText = block.text.replace("\n", " ").trim()
                            translator.translate(cleanText)
                        }

                        Tasks.whenAllComplete(tasks)
                            .addOnCompleteListener {
                                val results = mutableListOf<TranslatedBlock>()
                                for (i in validBlocks.indices) {
                                    val task = tasks[i]
                                    if (task.isSuccessful) {
                                        val translated = task.result
                                        val box = validBlocks[i].boundingBox
                                        if (!translated.isNullOrBlank() && box != null) {
                                            results.add(TranslatedBlock(box, translated))
                                        }
                                    }
                                }

                                binding.overlayView.setTranslatedBlocks(results)
                                hideLoading()
                                recognizer.close()
                                translator.close()
                                isTranslating = false
                            }
                    }
                    .addOnFailureListener { e ->
                        hideLoading()
                        recognizer.close()
                        translator.close()
                        isTranslating = false
                        Toast.makeText(
                            this,
                            "Gagal mengunduh model: ${e.localizedMessage}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
            }
            .addOnFailureListener { e ->
                hideLoading()
                recognizer.close()
                isTranslating = false
                Toast.makeText(
                    this,
                    "Gagal memindai teks: ${e.localizedMessage}",
                    Toast.LENGTH_SHORT
                ).show()
            }
    }

    private fun showLoading(message: String) {
        binding.tvStatus.text = message
        binding.loadingLayout.visibility = View.VISIBLE
    }

    private fun hideLoading() {
        binding.loadingLayout.visibility = View.GONE
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        val view = currentFocus ?: binding.root
        imm?.hideSoftInputFromWindow(view.windowToken, 0)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        binding.overlayView.clear()
    }

    override fun onDestroy() {
        binding.webView.stopLoading()
        binding.webView.destroy()
        super.onDestroy()
    }
}
