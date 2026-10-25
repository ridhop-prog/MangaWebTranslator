package com.pribadi.mangatranslator

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
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
import kotlin.math.hypot

class FloatingService : Service() {

    companion object {
        const val CHANNEL_ID = "ScreenMangaTranslatorChannel"
        const val NOTIFICATION_ID = 1001
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_LANG_INDEX = "extra_lang_index"
        const val ACTION_STOP = "action_stop_floating_service"
        var isRunning = false
            private set
    }

    private lateinit var windowManager: WindowManager
    private val handler = Handler(Looper.getMainLooper())

    private var mediaProjection: MediaProjection? = null
    private var currentLangIndex = 0 // 0: Japanese, 1: Korean, 2: English
    private var isTranslating = false

    // Auto-Translate / Continuous Scroll Engine
    private var isAutoMode = false
    private var lastScreenSamples: IntArray? = null
    private val autoTranslateRunnable = object : Runnable {
        override fun run() {
            if (!isAutoMode || !isRunning) return
            performAutoTranslateCheck()
            handler.postDelayed(this, 2000)
        }
    }

    // Persistent Screen Capture Engine
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var latestBitmap: Bitmap? = null
    private val bitmapLock = Any()
    private var captureWidth = 0
    private var captureHeight = 0
    private var captureDpi = 0

    // Views
    private var floatingBubbleView: View? = null
    private var floatingStatusView: View? = null
    private var overlayView: OverlayView? = null

    private var bubbleParams: WindowManager.LayoutParams? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var statusParams: WindowManager.LayoutParams? = null

    private var isOverlayAttached = false
    private var isStatusAttached = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        currentLangIndex = intent?.getIntExtra(EXTRA_LANG_INDEX, 0) ?: 0
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA) as? Intent
        }

        // 1. Mandatory order on Android 14: Start foreground service FIRST
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // 2. Initialize MediaProjection AFTER startForeground has been called
        if (resultCode != 0 && resultData != null && mediaProjection == null) {
            val projectionManager =
                getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)

            // Register callback required for Android 14 (API 34)
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    mediaProjection = null
                    stopScreenCapture()
                }
            }, handler)

            initScreenCapture()
        }

        // 3. Initialize and show floating bubble
        if (floatingBubbleView == null) {
            setupViews()
        }

        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_title),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_desc)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val pendingOpen = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, FloatingService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingStop = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_desc))
            .setSmallIcon(R.drawable.ic_translate)
            .setContentIntent(pendingOpen)
            .addAction(R.drawable.ic_clear, getString(R.string.btn_stop_service), pendingStop)
            .setOngoing(true)
            .build()
    }

    @SuppressLint("InflateParams", "ClickableViewAccessibility")
    private fun setupViews() {
        val layoutInflater = LayoutInflater.from(this)
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        // Setup Floating Bubble
        floatingBubbleView = layoutInflater.inflate(R.layout.floating_bubble, null)
        bubbleParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 50
            y = 350
        }

        val btnMain = floatingBubbleView?.findViewById<FrameLayout>(R.id.btnFloatingMain)
        val btnToggleAuto = floatingBubbleView?.findViewById<FrameLayout>(R.id.btnToggleAuto)
        val tvLangBadge = floatingBubbleView?.findViewById<TextView>(R.id.tvLangBadge)
        val btnClose = floatingBubbleView?.findViewById<ImageView>(R.id.btnCloseFloating)
        val pbLoading = floatingBubbleView?.findViewById<ProgressBar>(R.id.pbLoading)
        val ivIcon = floatingBubbleView?.findViewById<ImageView>(R.id.ivIcon)

        fun updateBadge() {
            tvLangBadge?.text = when (currentLangIndex) {
                0 -> "JA"
                1 -> "KO"
                else -> "EN"
            }
        }
        updateBadge()
        updateAutoModeUI()

        // Toggle Auto-Translate mode on pill click
        btnToggleAuto?.setOnClickListener {
            toggleAutoMode()
        }

        // Language cycle on badge tap
        tvLangBadge?.setOnClickListener {
            currentLangIndex = (currentLangIndex + 1) % 3
            updateBadge()
            val langName = when (currentLangIndex) {
                0 -> "Jepang"
                1 -> "Korea"
                else -> "Inggris"
            }
            Toast.makeText(this, "Bahasa sumber: $langName", Toast.LENGTH_SHORT).show()
        }

        // Close service button
        btnClose?.setOnClickListener {
            stopSelf()
        }

        // Touch & Drag Handling for Floating Bubble
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        btnMain?.setOnTouchListener { _, event ->
            val params = bubbleParams ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(floatingBubbleView, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val diffX = event.rawX - initialTouchX
                    val diffY = event.rawY - initialTouchY
                    // If drag distance is very small, treat as click
                    if (hypot(diffX.toDouble(), diffY.toDouble()) < 15) {
                        if (!isTranslating) {
                            startScreenTranslation(ivIcon, pbLoading)
                        } else {
                            Toast.makeText(this, "Sedang menerjemahkan...", Toast.LENGTH_SHORT).show()
                        }
                    }
                    true
                }
                else -> false
            }
        }

        windowManager.addView(floatingBubbleView, bubbleParams)

        // Setup Floating Status Pill
        floatingStatusView = layoutInflater.inflate(R.layout.floating_status, null)
        statusParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 120
        }

        // Setup Fullscreen OverlayView
        overlayView = OverlayView(this).apply {
            onDismiss = {
                hideOverlay()
            }
        }
        overlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
    }

    private fun showStatus(text: String) {
        val tvStatus = floatingStatusView?.findViewById<TextView>(R.id.tvFloatingStatus)
        tvStatus?.text = text

        if (!isStatusAttached && floatingStatusView != null) {
            windowManager.addView(floatingStatusView, statusParams)
            isStatusAttached = true
        }
    }

    private fun hideStatus() {
        if (isStatusAttached && floatingStatusView != null) {
            windowManager.removeView(floatingStatusView)
            isStatusAttached = false
        }
    }

    private fun toggleAutoMode() {
        val ivIcon = floatingBubbleView?.findViewById<ImageView>(R.id.ivIcon)
        val pbLoading = floatingBubbleView?.findViewById<ProgressBar>(R.id.pbLoading)

        isAutoMode = !isAutoMode
        updateAutoModeUI()

        if (isAutoMode) {
            lastScreenSamples = null
            handler.removeCallbacks(autoTranslateRunnable)
            handler.post(autoTranslateRunnable)
            Toast.makeText(
                this,
                "Mode Auto AKTIF: Komik diterjemahkan otomatis saat membaca",
                Toast.LENGTH_SHORT
            ).show()
        } else {
            handler.removeCallbacks(autoTranslateRunnable)
            hideOverlay()
            Toast.makeText(this, "Mode Auto NONAKTIF", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateAutoModeUI() {
        val btnMain = floatingBubbleView?.findViewById<FrameLayout>(R.id.btnFloatingMain)
        val btnAuto = floatingBubbleView?.findViewById<FrameLayout>(R.id.btnToggleAuto)
        val tvAutoText = floatingBubbleView?.findViewById<TextView>(R.id.tvAutoText)
        val viewAutoDot = floatingBubbleView?.findViewById<View>(R.id.viewAutoDot)

        if (isAutoMode) {
            btnMain?.setBackgroundResource(R.drawable.bg_floating_bubble_auto)
            btnAuto?.setBackgroundResource(R.drawable.bg_auto_toggle_on)
            viewAutoDot?.setBackgroundResource(R.drawable.bg_auto_dot_on)
            tvAutoText?.text = "AUTO ON"
        } else {
            btnMain?.setBackgroundResource(R.drawable.bg_floating_bubble)
            btnAuto?.setBackgroundResource(R.drawable.bg_auto_toggle_off)
            viewAutoDot?.setBackgroundResource(R.drawable.bg_auto_dot_off)
            tvAutoText?.text = "AUTO"
        }
    }

    private fun performAutoTranslateCheck() {
        if (isTranslating || mediaProjection == null || virtualDisplay == null) return

        var currentBmp: Bitmap? = null
        synchronized(bitmapLock) {
            val bmp = latestBitmap
            if (bmp != null && !bmp.isRecycled) {
                currentBmp = bmp.copy(Bitmap.Config.ARGB_8888, false)
            }
        }

        if (currentBmp == null) return

        // Compare with previous screen samples to avoid re-translating static screen
        if (!hasScreenChangedSignificantly(currentBmp, lastScreenSamples)) {
            return
        }

        lastScreenSamples = extractScreenSamples(currentBmp)

        val ivIcon = floatingBubbleView?.findViewById<ImageView>(R.id.ivIcon)
        val pbLoading = floatingBubbleView?.findViewById<ProgressBar>(R.id.pbLoading)

        isTranslating = true
        ivIcon?.visibility = View.GONE
        pbLoading?.visibility = View.VISIBLE

        processBitmapTranslation(currentBmp, ivIcon, pbLoading, isAuto = true)
    }

    private fun hasScreenChangedSignificantly(current: Bitmap, referenceSamples: IntArray?): Boolean {
        if (referenceSamples == null) return true
        val width = current.width
        val height = current.height
        if (width < 50 || height < 50) return true

        var diffCount = 0
        var sampleIdx = 0

        val startX = (width * 0.15).toInt()
        val endX = (width * 0.85).toInt()
        val startY = (height * 0.15).toInt()
        val endY = (height * 0.85).toInt()
        val stepX = maxOf(1, (endX - startX) / 10)
        val stepY = maxOf(1, (endY - startY) / 10)

        for (y in startY until endY step stepY) {
            for (x in startX until endX step stepX) {
                if (sampleIdx >= referenceSamples.size) break
                val curPixel = current.getPixel(x, y)
                val refPixel = referenceSamples[sampleIdx]
                if (curPixel != refPixel) {
                    diffCount++
                }
                sampleIdx++
            }
        }

        // If more than 8% of sampled pixels changed, the screen has moved
        return diffCount > 8
    }

    private fun extractScreenSamples(bitmap: Bitmap): IntArray {
        val samples = IntArray(100)
        val width = bitmap.width
        val height = bitmap.height
        if (width < 50 || height < 50) return samples

        val startX = (width * 0.15).toInt()
        val endX = (width * 0.85).toInt()
        val startY = (height * 0.15).toInt()
        val endY = (height * 0.85).toInt()
        val stepX = maxOf(1, (endX - startX) / 10)
        val stepY = maxOf(1, (endY - startY) / 10)
        var idx = 0

        for (y in startY until endY step stepY) {
            for (x in startX until endX step stepX) {
                if (idx >= samples.size) break
                samples[idx++] = bitmap.getPixel(x, y)
            }
        }
        return samples
    }

    private fun showOverlay(blocks: List<TranslatedBlock>) {
        val flags = if (isAutoMode) {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }

        overlayParams?.flags = flags

        if (isOverlayAttached && overlayView != null) {
            windowManager.updateViewLayout(overlayView, overlayParams)
        } else if (!isOverlayAttached && overlayView != null) {
            windowManager.addView(overlayView, overlayParams)
            isOverlayAttached = true
        }
        overlayView?.setTranslatedBlocks(blocks)
    }

    private fun hideOverlay() {
        overlayView?.clear()
        if (isOverlayAttached && overlayView != null) {
            windowManager.removeView(overlayView)
            isOverlayAttached = false
        }
    }

    @Suppress("DEPRECATION")
    private fun initScreenCapture() {
        val mp = mediaProjection ?: return
        if (virtualDisplay != null) return

        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        captureWidth = metrics.widthPixels
        captureHeight = metrics.heightPixels
        captureDpi = metrics.densityDpi

        val thread = HandlerThread("ScreenCaptureThread").apply { start() }
        captureThread = thread
        val bgHandler = Handler(thread.looper)
        captureHandler = bgHandler

        val reader = ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 3)
        imageReader = reader

        reader.setOnImageAvailableListener({ r ->
            var image: Image? = null
            try {
                image = r.acquireLatestImage()
                if (image != null) {
                    val planes = image.planes
                    val buffer = planes[0].buffer
                    val pixelStride = planes[0].pixelStride
                    val rowStride = planes[0].rowStride
                    val rowPadding = rowStride - pixelStride * captureWidth

                    val bmp = Bitmap.createBitmap(
                        captureWidth + rowPadding / pixelStride,
                        captureHeight,
                        Bitmap.Config.ARGB_8888
                    )
                    bmp.copyPixelsFromBuffer(buffer)

                    val finalBmp = if (rowPadding == 0) {
                        bmp
                    } else {
                        val cropped = Bitmap.createBitmap(bmp, 0, 0, captureWidth, captureHeight)
                        bmp.recycle()
                        cropped
                    }

                    synchronized(bitmapLock) {
                        latestBitmap?.recycle()
                        latestBitmap = finalBmp
                    }
                }
            } catch (e: Exception) {
                // ignore image read exceptions
            } finally {
                image?.close()
            }
        }, bgHandler)

        virtualDisplay = mp.createVirtualDisplay(
            "ScreenMangaCapture",
            captureWidth,
            captureHeight,
            captureDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            bgHandler
        )
    }

    private fun stopScreenCapture() {
        try {
            virtualDisplay?.release()
            virtualDisplay = null
            imageReader?.close()
            imageReader = null
            captureThread?.quitSafely()
            captureThread = null
            synchronized(bitmapLock) {
                latestBitmap?.recycle()
                latestBitmap = null
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startScreenTranslation(ivIcon: ImageView?, pbLoading: ProgressBar?) {
        isTranslating = true
        ivIcon?.visibility = View.GONE
        pbLoading?.visibility = View.VISIBLE

        hideOverlay()
        showStatus(getString(R.string.status_capturing))

        // Temporarily hide bubble so SurfaceFlinger sends a fresh frame without the bubble
        floatingBubbleView?.visibility = View.INVISIBLE

        captureScreen { bitmap ->
            floatingBubbleView?.visibility = View.VISIBLE

            if (bitmap == null) {
                finishTranslation(ivIcon, pbLoading)
                Toast.makeText(this, getString(R.string.status_failed), Toast.LENGTH_SHORT).show()
                return@captureScreen
            }

            processBitmapTranslation(bitmap, ivIcon, pbLoading, isAuto = false)
        }
    }

    private fun processBitmapTranslation(
        bitmap: Bitmap,
        ivIcon: ImageView?,
        pbLoading: ProgressBar?,
        isAuto: Boolean = false
    ) {
        if (!isAuto) {
            showStatus(getString(R.string.status_ocr))
        }

        val (recognizer: TextRecognizer, defaultSourceLang: String) = when (currentLangIndex) {
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

        val inputImage = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                val bubbleRect = Rect()
                val bParams = bubbleParams
                if (floatingBubbleView != null && bParams != null) {
                    val bX = bParams.x
                    val bY = bParams.y
                    val bW = floatingBubbleView?.width ?: 0
                    val bH = floatingBubbleView?.height ?: 0
                    bubbleRect.set(bX - 20, bY - 20, bX + bW + 20, bY + bH + 20)
                }

                val validBlocks = visionText.textBlocks.filter { block ->
                    val box = block.boundingBox ?: return@filter false
                    if (box.width() < 12 || box.height() < 12) return@filter false
                    if (Rect.intersects(box, bubbleRect)) return@filter false
                    val cleanText = block.text.replace("\n", " ").trim()
                    cleanText.any { it.isLetter() }
                }

                if (validBlocks.isEmpty()) {
                    finishTranslation(ivIcon, pbLoading)
                    recognizer.close()
                    if (isAuto) {
                        hideOverlay()
                    } else {
                        Toast.makeText(this, getString(R.string.status_no_text), Toast.LENGTH_SHORT).show()
                    }
                    return@addOnSuccessListener
                }

                // Smart Language Auto-Detection:
                // Scan detected text characters to detect English vs Japanese vs Korean
                val allText = validBlocks.joinToString(" ") { it.text }
                val hasJapanese = allText.any { c ->
                    (c in '\u3040'..'\u309F') || // Hiragana
                    (c in '\u30A0'..'\u30FF') || // Katakana
                    (c in '\u4E00'..'\u9FAF')    // Kanji
                }
                val hasKorean = allText.any { c ->
                    (c in '\uAC00'..'\uD7AF') || // Hangul Syllables
                    (c in '\u1100'..'\u11FF') || // Hangul Jamo
                    (c in '\u3130'..'\u318F')    // Hangul Compatibility Jamo
                }
                val hasLatin = allText.any { c -> (c in 'a'..'z') || (c in 'A'..'Z') }

                val sourceLangCode = when {
                    hasJapanese -> TranslateLanguage.JAPANESE
                    hasKorean -> TranslateLanguage.KOREAN
                    hasLatin -> TranslateLanguage.ENGLISH
                    else -> defaultSourceLang
                }

                // If language was auto-detected differently from current badge, update badge
                val detectedIndex = when (sourceLangCode) {
                    TranslateLanguage.JAPANESE -> 0
                    TranslateLanguage.KOREAN -> 1
                    else -> 2
                }
                if (detectedIndex != currentLangIndex) {
                    currentLangIndex = detectedIndex
                    val tvLangBadge = floatingBubbleView?.findViewById<TextView>(R.id.tvLangBadge)
                    tvLangBadge?.text = when (currentLangIndex) {
                        0 -> "JA"
                        1 -> "KO"
                        else -> "EN"
                    }
                }

                if (!isAuto) {
                    showStatus(getString(R.string.status_downloading_model))
                }

                val translatorOptions = TranslatorOptions.Builder()
                    .setSourceLanguage(sourceLangCode)
                    .setTargetLanguage(TranslateLanguage.INDONESIAN)
                    .build()

                val translator = Translation.getClient(translatorOptions)
                val conditions = DownloadConditions.Builder().build()

                translator.downloadModelIfNeeded(conditions)
                    .addOnSuccessListener {
                        if (!isAuto) {
                            showStatus(getString(R.string.status_translating))
                        }

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
                                        val translated = task.result as? String
                                        val box = validBlocks[i].boundingBox
                                        if (!translated.isNullOrBlank() && box != null) {
                                            results.add(TranslatedBlock(box, translated))
                                        }
                                    }
                                }

                                finishTranslation(ivIcon, pbLoading)
                                recognizer.close()
                                translator.close()

                                if (results.isNotEmpty()) {
                                    showOverlay(results)
                                    if (!isAuto) {
                                        Toast.makeText(
                                            this,
                                            "Terjemahan siap (${results.size} balon teks). Ketuk layar untuk menutup.",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                } else {
                                    if (isAuto) {
                                        hideOverlay()
                                    } else {
                                        Toast.makeText(this, getString(R.string.status_no_text), Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                    }
                    .addOnFailureListener { e ->
                        finishTranslation(ivIcon, pbLoading)
                        recognizer.close()
                        translator.close()
                        if (!isAuto) {
                            Toast.makeText(this, "Gagal mengunduh kamus: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                        }
                    }
            }
            .addOnFailureListener { e ->
                finishTranslation(ivIcon, pbLoading)
                recognizer.close()
                if (!isAuto) {
                    Toast.makeText(this, "Gagal memindai: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                }
            }
    }

    private fun finishTranslation(ivIcon: ImageView?, pbLoading: ProgressBar?) {
        isTranslating = false
        ivIcon?.visibility = View.VISIBLE
        pbLoading?.visibility = View.GONE
        hideStatus()
    }

    @Suppress("DEPRECATION")
    private fun captureScreen(callback: (Bitmap?) -> Unit) {
        val mp = mediaProjection
        if (mp == null) {
            callback(null)
            return
        }

        if (virtualDisplay == null) {
            initScreenCapture()
        }

        // Check if screen orientation changed
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        if (metrics.widthPixels != captureWidth || metrics.heightPixels != captureHeight) {
            stopScreenCapture()
            initScreenCapture()
        }

        var attempts = 0
        fun checkFrame() {
            var resultBitmap: Bitmap? = null
            synchronized(bitmapLock) {
                val bmp = latestBitmap
                if (bmp != null && !bmp.isRecycled) {
                    resultBitmap = bmp.copy(Bitmap.Config.ARGB_8888, false)
                }
            }

            if (resultBitmap != null) {
                callback(resultBitmap)
            } else if (attempts < 6) {
                attempts++
                handler.postDelayed({ checkFrame() }, 60)
            } else {
                callback(null)
            }
        }

        // Wait 90ms for SurfaceFlinger to send the fresh frame without the floating bubble
        handler.postDelayed({ checkFrame() }, 90)
    }

    override fun onDestroy() {
        isRunning = false
        isAutoMode = false
        handler.removeCallbacks(autoTranslateRunnable)
        hideOverlay()
        hideStatus()

        if (floatingBubbleView != null) {
            windowManager.removeView(floatingBubbleView)
            floatingBubbleView = null
        }

        stopScreenCapture()

        mediaProjection?.stop()
        mediaProjection = null

        super.onDestroy()
    }
}
