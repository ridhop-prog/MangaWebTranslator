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
import android.graphics.Canvas
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
import android.util.LruCache
import android.view.Gravity
import android.view.HapticFeedbackConstants
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
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.abs
import kotlin.math.hypot

class FloatingService : Service() {

    companion object {
        const val CHANNEL_ID = "ScreenMangaTranslatorChannel"
        const val NOTIFICATION_ID = 1001
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_LANG_INDEX = "extra_lang_index"
        const val EXTRA_BG_STYLE = "extra_bg_style"
        const val ACTION_STOP = "action_stop_floating_service"
        private const val AUTO_POLL_INTERVAL_MS = 220L
        private const val SCROLL_SETTLE_MS = 230L
        private const val LONG_PRESS_CLEAR_MS = 500L
        var isRunning = false
            private set
    }

    private data class MergedOcrBlock(
        val boundingBox: Rect,
        val bubbleBox: Rect,
        val cleanText: String
    )

    private lateinit var windowManager: WindowManager
    private val handler = Handler(Looper.getMainLooper())

    private var mediaProjection: MediaProjection? = null
    private var currentLangIndex = 0 // 0: Japanese, 1: Korean, 2: English
    private var bgStyle = OverlayView.STYLE_TRANSPARENT
    @Volatile
    private var isTranslating = false

    // Fast LRU cache for translated dialogue lines so scrolled bubbles re-render instantly
    private val translationCache = LruCache<String, String>(250)

    // Real-time visual scroll tracker
    private val scrollTracker = ScrollTracker {
        handler.post {
            if (!isRunning || isTranslating) return@post
            lastScreenSamples = null
            lastOcrShiftY = Float.NaN
            hideOverlay()
            if (isAutoMode) {
                handler.removeCallbacks(autoTranslateRunnable)
                handler.postDelayed(autoTranslateRunnable, 140L)
            }
        }
    }

    // Auto-Translate / Continuous Scroll Engine
    @Volatile
    private var isAutoMode = false
    private var lastScreenSamples: IntArray? = null
    private var lastOcrShiftY: Float = Float.NaN
    private val autoTranslateRunnable = object : Runnable {
        override fun run() {
            if (!isAutoMode || !isRunning) return
            performAutoTranslateCheck()
            handler.postDelayed(this, AUTO_POLL_INTERVAL_MS)
        }
    }

    // Persistent Screen Capture Engine
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var rawBufferBitmap: Bitmap? = null
    private var latestBitmap: Bitmap? = null
    private var latestBitmapCanvas: Canvas? = null
    private var hasLatestBitmapFrame = false
    private var latestBitmapShiftY = 0f
    private val rawSrcRect = Rect()
    private val rawDstRect = Rect()
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

    @Volatile
    private var isOverlayAttached = false
    @Volatile
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
        bgStyle = intent?.getIntExtra(
            EXTRA_BG_STYLE,
            getSharedPreferences("ScreenMangaTranslatorPrefs", Context.MODE_PRIVATE)
                .getInt("pref_key_bg_style", OverlayView.STYLE_TRANSPARENT)
        ) ?: OverlayView.STYLE_TRANSPARENT
        overlayView?.bgStyle = bgStyle

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

        // Touch, Drag & Long-Press Handling for Floating Bubble
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var longPressTriggered = false

        val longPressRunnable = Runnable {
            longPressTriggered = true
            btnMain?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            hideOverlay()
            lastOcrShiftY = Float.NaN
            lastScreenSamples = null
            Toast.makeText(this, "Tampilan terjemahan dibersihkan", Toast.LENGTH_SHORT).show()
        }

        btnMain?.setOnTouchListener { _, event ->
            val params = bubbleParams ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    longPressTriggered = false
                    handler.removeCallbacks(longPressRunnable)
                    handler.postDelayed(longPressRunnable, LONG_PRESS_CLEAR_MS)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val diffX = event.rawX - initialTouchX
                    val diffY = event.rawY - initialTouchY
                    if (hypot(diffX.toDouble(), diffY.toDouble()) >= 15) {
                        handler.removeCallbacks(longPressRunnable)
                    }
                    params.x = initialX + diffX.toInt()
                    params.y = initialY + diffY.toInt()
                    windowManager.updateViewLayout(floatingBubbleView, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPressRunnable)
                    if (!longPressTriggered) {
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
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPressRunnable)
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
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 120
        }

        // Setup Fullscreen Touch-Pass-Through OverlayView
        overlayView = OverlayView(this).apply {
            val prefs = getSharedPreferences("ScreenMangaTranslatorPrefs", Context.MODE_PRIVATE)
            bgStyle = prefs.getInt("pref_key_bg_style", OverlayView.STYLE_TRANSPARENT)
        }
        overlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
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
        isAutoMode = !isAutoMode
        updateAutoModeUI()

        if (isAutoMode) {
            lastScreenSamples = null
            lastOcrShiftY = Float.NaN
            hideOverlay()
            scrollTracker.reset(0f)
            overlayView?.resetScrollState(0f)
            handler.removeCallbacks(autoTranslateRunnable)
            handler.post(autoTranslateRunnable)
            Toast.makeText(
                this,
                "Mode Auto AKTIF: Terjemahan otomatis mengikuti scroll komik",
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
        if (!scrollTracker.isScrollingSettled(SCROLL_SETTLE_MS)) return

        var checkBmp: Bitmap? = null
        var currentShiftY = 0f
        synchronized(bitmapLock) {
            val bmp = latestBitmap
            if (bmp != null && !bmp.isRecycled && hasLatestBitmapFrame) {
                checkBmp = bmp.copy(Bitmap.Config.ARGB_8888, false)
                currentShiftY = latestBitmapShiftY
            }
        }
        val bmp = checkBmp ?: return

        val maskedRects = collectMaskedRectsForTracker()
        val scrolledByTracker = !lastOcrShiftY.isNaN() &&
            abs(currentShiftY - lastOcrShiftY) > captureHeight * 0.045f
        val unmaskedScreenChanged = hasUnmaskedScreenChanged(bmp, lastScreenSamples, maskedRects)
        bmp.recycle()

        if (!lastOcrShiftY.isNaN() && !scrolledByTracker && !unmaskedScreenChanged) {
            return
        }

        // Screen has scrolled or changed:
        // 1. Immediately clear old scrolled bubbles so they NEVER stack or linger
        // 2. Capture a 100% clean frame (without OverlayView drawn on top) after 65ms
        val ivIcon = floatingBubbleView?.findViewById<ImageView>(R.id.ivIcon)
        val pbLoading = floatingBubbleView?.findViewById<ProgressBar>(R.id.pbLoading)

        isTranslating = true
        ivIcon?.visibility = View.GONE
        pbLoading?.visibility = View.VISIBLE

        hideOverlay()
        scrollTracker.reset(0f)

        captureScreen(delayMs = 65L) { cleanBmp, cleanShiftY ->
            if (!isAutoMode || !isRunning || cleanBmp == null) {
                cleanBmp?.recycle()
                finishTranslation(ivIcon, pbLoading)
                return@captureScreen
            }

            lastScreenSamples = extractScreenSamples(cleanBmp)
            lastOcrShiftY = cleanShiftY

            processBitmapTranslation(cleanBmp, cleanShiftY, ivIcon, pbLoading, isAuto = true)
        }
    }

    /**
     * Compares current screen pixels outside [maskedRects] with [referenceSamples] from the last
     * clean scan so scroll/page changes are always detected even if [OverlayView] is visible.
     */
    private fun hasUnmaskedScreenChanged(
        current: Bitmap,
        referenceSamples: IntArray?,
        maskedRects: List<Rect>
    ): Boolean {
        if (referenceSamples == null) return true
        val width = current.width
        val height = current.height
        if (width < 50 || height < 50) return true

        var diffCount = 0
        var validSampleCount = 0
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
                val refPixel = referenceSamples[sampleIdx]
                sampleIdx++

                var insideMask = false
                for (i in maskedRects.indices) {
                    if (maskedRects[i].contains(x, y)) {
                        insideMask = true
                        break
                    }
                }
                if (insideMask) continue

                validSampleCount++
                val curPixel = current.getPixel(x, y)
                if (!pixelsAreSimilar(curPixel, refPixel)) {
                    diffCount++
                }
            }
        }

        if (validSampleCount < 10) return true
        return (diffCount.toFloat() / validSampleCount.toFloat()) > 0.075f
    }

    private fun pixelsAreSimilar(p1: Int, p2: Int): Boolean {
        if (p1 == p2) return true
        val r1 = (p1 shr 16) and 0xFF
        val g1 = (p1 shr 8) and 0xFF
        val b1 = p1 and 0xFF
        val r2 = (p2 shr 16) and 0xFF
        val g2 = (p2 shr 8) and 0xFF
        val b2 = p2 and 0xFF
        return abs(r1 - r2) + abs(g1 - g2) + abs(b1 - b2) <= 18
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
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        overlayParams?.flags = flags

        if (!isOverlayAttached && overlayView != null) {
            windowManager.addView(overlayView, overlayParams)
            isOverlayAttached = true
        }

        // Always replace blocks cleanly (never merge/accumulate old blocks across scans)
        overlayView?.resetScrollState(scrollTracker.currentShiftY)
        overlayView?.setTranslatedBlocks(blocks)
    }

    private fun hideOverlay() {
        overlayView?.clear()
        if (isOverlayAttached && overlayView != null) {
            windowManager.removeView(overlayView)
            isOverlayAttached = false
        }
    }

    private fun collectMaskedRectsForTracker(): List<Rect> {
        val overlay = overlayView
        val overlayRects = if (isOverlayAttached && overlay != null && overlay.hasBlocks()) {
            overlay.getMaskedScreenRects(scrollTracker.currentShiftY, extraPadding = 12)
        } else {
            emptyList()
        }

        val bubbleRect = getBubbleScreenRect(padding = 12)
        val statusRect = getStatusScreenRect(padding = 10)
        if (bubbleRect == null && statusRect == null) return overlayRects

        val combined = ArrayList<Rect>(overlayRects.size + 2)
        combined.addAll(overlayRects)
        if (bubbleRect != null) combined.add(bubbleRect)
        if (statusRect != null) combined.add(statusRect)
        return combined
    }

    private fun getBubbleScreenRect(padding: Int): Rect? {
        val view = floatingBubbleView ?: return null
        val params = bubbleParams ?: return null
        if (view.visibility != View.VISIBLE) return null
        val w = view.width
        val h = view.height
        if (w <= 0 || h <= 0) return null
        return Rect(
            params.x - padding,
            params.y - padding,
            params.x + w + padding,
            params.y + h + padding
        )
    }

    private fun getStatusScreenRect(padding: Int): Rect? {
        if (!isStatusAttached) return null
        val view = floatingStatusView ?: return null
        val params = statusParams ?: return null
        val w = view.width
        val h = view.height
        if (w <= 0 || h <= 0) return null
        val sx = (captureWidth - w) / 2
        val sy = params.y
        return Rect(
            sx - padding,
            sy - padding,
            sx + w + padding,
            sy + h + padding
        )
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

                    val hasActiveOverlay = isOverlayAttached && (overlayView?.hasBlocks() == true)
                    if (hasActiveOverlay || isAutoMode || isTranslating) {
                        val maskedRects = collectMaskedRectsForTracker()
                        scrollTracker.processFrame(
                            buffer = buffer,
                            width = captureWidth,
                            height = captureHeight,
                            pixelStride = pixelStride,
                            rowStride = rowStride,
                            maskedRects = maskedRects
                        )
                        if (hasActiveOverlay) {
                            overlayView?.updateScrollState(
                                scrollTracker.currentShiftY,
                                scrollTracker.velocityPxPerMs
                            )
                        }
                    }

                    // Skip copying full-screen bitmap on high-velocity scroll frames once we have a frame
                    val isFastScrolling = abs(scrollTracker.velocityPxPerMs) > 0.6f
                    if (!isFastScrolling || !hasLatestBitmapFrame) {
                        val rowPadding = rowStride - pixelStride * captureWidth
                        val rowWidth = captureWidth + rowPadding / pixelStride
                        buffer.rewind()

                        synchronized(bitmapLock) {
                            var dest = latestBitmap
                            if (dest == null || dest.isRecycled ||
                                dest.width != captureWidth || dest.height != captureHeight
                            ) {
                                dest?.recycle()
                                dest = Bitmap.createBitmap(
                                    captureWidth,
                                    captureHeight,
                                    Bitmap.Config.ARGB_8888
                                )
                                latestBitmap = dest
                                latestBitmapCanvas = Canvas(dest)
                            }

                            if (rowPadding == 0) {
                                dest.copyPixelsFromBuffer(buffer)
                            } else {
                                var raw = rawBufferBitmap
                                if (raw == null || raw.isRecycled ||
                                    raw.width != rowWidth || raw.height != captureHeight
                                ) {
                                    raw?.recycle()
                                    raw = Bitmap.createBitmap(
                                        rowWidth,
                                        captureHeight,
                                        Bitmap.Config.ARGB_8888
                                    )
                                    rawBufferBitmap = raw
                                }
                                raw.copyPixelsFromBuffer(buffer)
                                rawSrcRect.set(0, 0, captureWidth, captureHeight)
                                rawDstRect.set(0, 0, captureWidth, captureHeight)
                                latestBitmapCanvas?.drawBitmap(raw, rawSrcRect, rawDstRect, null)
                            }
                            latestBitmapShiftY = scrollTracker.currentShiftY
                            hasLatestBitmapFrame = true
                        }
                    }
                }
            } catch (_: Exception) {
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
                hasLatestBitmapFrame = false
                latestBitmapCanvas = null
                latestBitmap?.recycle()
                latestBitmap = null
                rawBufferBitmap?.recycle()
                rawBufferBitmap = null
            }
            scrollTracker.reset(0f)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startScreenTranslation(ivIcon: ImageView?, pbLoading: ProgressBar?) {
        isTranslating = true
        ivIcon?.visibility = View.GONE
        pbLoading?.visibility = View.VISIBLE

        hideOverlay()
        scrollTracker.reset(0f)
        showStatus(getString(R.string.status_capturing))

        // Temporarily hide bubble so SurfaceFlinger sends a fresh frame without the bubble
        floatingBubbleView?.visibility = View.INVISIBLE

        captureScreen(delayMs = 90L) { bitmap, scanShiftY ->
            floatingBubbleView?.visibility = View.VISIBLE

            if (bitmap == null) {
                finishTranslation(ivIcon, pbLoading)
                Toast.makeText(this, getString(R.string.status_failed), Toast.LENGTH_SHORT).show()
                return@captureScreen
            }

            lastScreenSamples = extractScreenSamples(bitmap)
            lastOcrShiftY = scanShiftY

            processBitmapTranslation(bitmap, scanShiftY, ivIcon, pbLoading, isAuto = false)
        }
    }

    private fun processBitmapTranslation(
        bitmap: Bitmap,
        scanShiftY: Float,
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
                val bubbleRect = getBubbleScreenRect(padding = 20) ?: Rect()

                val validBlocks = visionText.textBlocks.filter { block ->
                    val box = block.boundingBox ?: return@filter false
                    if (box.width() < 14 || box.height() < 14) return@filter false

                    // 1. Exclude Android system status bar (top ~5.5%)
                    if (box.top < captureHeight * 0.055f) return@filter false

                    // 2. Exclude Android system navigation & browser address bar (bottom ~6.5%)
                    if (box.bottom > captureHeight * 0.935f) return@filter false

                    // 3. Exclude Floating bubble UI itself
                    if (!bubbleRect.isEmpty && Rect.intersects(box, bubbleRect)) return@filter false

                    val cleanText = block.text.replace("\n", " ").trim()
                    val letterCount = cleanText.count { it.isLetter() }
                    if (letterCount < 2) return@filter false

                    // 4. Exclude Web URLs & domain names
                    if (isWebUrlOrDomain(cleanText)) return@filter false

                    // 5. Exclude Web search dates & timestamps
                    if (isDateOrTimestamp(cleanText)) return@filter false

                    // 6. Exclude extreme full-width article / search description paragraphs (>85% screen width)
                    if (box.width() > captureWidth * 0.85f && cleanText.length > 50) return@filter false

                    // 7. Exclude Search Engine UI chips near top (upper 20% of screen)
                    if (box.top < captureHeight * 0.20f && isSearchNavigationChip(cleanText)) return@filter false

                    true
                }

                if (validBlocks.isEmpty()) {
                    bitmap.recycle()
                    finishTranslation(ivIcon, pbLoading)
                    recognizer.close()
                    hideOverlay()
                    if (!isAuto) {
                        Toast.makeText(this, getString(R.string.status_no_text), Toast.LENGTH_SHORT).show()
                    }
                    return@addOnSuccessListener
                }

                // Detect comic speech bubble bounds for each valid text block
                val rawBubbleBoxes = validBlocks.map { block ->
                    block.boundingBox?.let { box ->
                        SpeechBubbleDetector.detectBubbleBounds(bitmap, box)
                    }
                }
                bitmap.recycle()

                // Merge OCR blocks that belong to the same speech bubble so they never stack on top of each other
                val mergedBlocks = mergeSameBubbleBlocks(validBlocks, rawBubbleBoxes)

                // Smart Language Auto-Detection:
                // Scan detected text characters to detect English vs Japanese vs Korean
                val allText = mergedBlocks.joinToString(" ") { it.cleanText }
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

                        val tasks = mergedBlocks.map { item ->
                            val cacheKey = "$sourceLangCode:${item.cleanText.lowercase()}"
                            val cached = translationCache.get(cacheKey)
                            if (cached != null) {
                                Tasks.forResult(cached)
                            } else {
                                translator.translate(item.cleanText).addOnSuccessListener { translated ->
                                    if (!translated.isNullOrBlank()) {
                                        translationCache.put(cacheKey, translated)
                                    }
                                }
                            }
                        }

                        Tasks.whenAllComplete(tasks)
                            .addOnCompleteListener {
                                val results = mutableListOf<TranslatedBlock>()
                                for (i in mergedBlocks.indices) {
                                    val task = tasks[i]
                                    if (task.isSuccessful) {
                                        val translated = task.result
                                        val item = mergedBlocks[i]
                                        if (!translated.isNullOrBlank()) {
                                            results.add(
                                                TranslatedBlock(
                                                    boundingBox = item.boundingBox,
                                                    translatedText = translated,
                                                    bubbleBox = item.bubbleBox,
                                                    captureShiftY = scanShiftY,
                                                    originalText = item.cleanText
                                                )
                                            )
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
                                            "Terjemahan siap (${results.size} balon teks). Tahan tombol melayang untuk menutup.",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                } else {
                                    hideOverlay()
                                    if (!isAuto) {
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
                bitmap.recycle()
                finishTranslation(ivIcon, pbLoading)
                recognizer.close()
                if (!isAuto) {
                    Toast.makeText(this, "Gagal memindai: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                }
            }
    }

    /**
     * Groups and merges OCR [Text.TextBlock]s that reside inside the same comic speech bubble
     * so multi-part dialogue in a single bubble is translated as one sentence and never stacks.
     */
    private fun mergeSameBubbleBlocks(
        blocks: List<Text.TextBlock>,
        bubbleBoxes: List<Rect?>
    ): List<MergedOcrBlock> {
        val items = ArrayList<MergedOcrBlock>(blocks.size)
        for (i in blocks.indices) {
            val box = blocks[i].boundingBox ?: continue
            val bubble = bubbleBoxes.getOrNull(i) ?: Rect(box)
            val clean = blocks[i].text.replace("\n", " ").trim()
            if (clean.isNotEmpty()) {
                items.add(MergedOcrBlock(Rect(box), Rect(bubble), clean))
            }
        }
        if (items.size <= 1) return items

        val n = items.size
        val parent = IntArray(n) { it }
        fun find(x: Int): Int {
            var r = x
            while (parent[r] != r) r = parent[r]
            var curr = x
            while (curr != r) {
                val nxt = parent[curr]
                parent[curr] = r
                curr = nxt
            }
            return r
        }
        fun union(a: Int, b: Int) {
            val ra = find(a)
            val rb = find(b)
            if (ra != rb) parent[rb] = ra
        }

        for (i in 0 until n) {
            for (j in i + 1 until n) {
                if (shouldMergeOcrBlocks(items[i], items[j])) {
                    union(i, j)
                }
            }
        }

        val groups = LinkedHashMap<Int, MutableList<MergedOcrBlock>>()
        for (i in 0 until n) {
            val root = find(i)
            groups.getOrPut(root) { ArrayList() }.add(items[i])
        }

        val mergedList = ArrayList<MergedOcrBlock>(groups.size)
        for (group in groups.values) {
            if (group.size == 1) {
                mergedList.add(group[0])
                continue
            }

            // Determine reading order: vertical Japanese columns (right-to-left) vs horizontal lines (top-to-bottom)
            val isVerticalLayout = group.count { it.boundingBox.height() > it.boundingBox.width() * 1.35 } > group.size / 2
            val sorted = if (isVerticalLayout) {
                group.sortedWith(
                    compareByDescending<MergedOcrBlock> { it.boundingBox.centerX() }
                        .thenBy { it.boundingBox.centerY() }
                )
            } else {
                group.sortedWith(
                    compareBy<MergedOcrBlock> { it.boundingBox.centerY() }
                        .thenBy { it.boundingBox.centerX() }
                )
            }

            val unionTextBox = Rect(sorted[0].boundingBox)
            val unionBubbleBox = Rect(sorted[0].bubbleBox)
            val sb = StringBuilder(sorted[0].cleanText)

            for (k in 1 until sorted.size) {
                unionTextBox.union(sorted[k].boundingBox)
                unionBubbleBox.union(sorted[k].bubbleBox)
                sb.append(' ').append(sorted[k].cleanText)
            }

            mergedList.add(
                MergedOcrBlock(
                    boundingBox = unionTextBox,
                    bubbleBox = unionBubbleBox,
                    cleanText = sb.toString()
                )
            )
        }

        return mergedList
    }

    private fun shouldMergeOcrBlocks(a: MergedOcrBlock, b: MergedOcrBlock): Boolean {
        val boxA = a.boundingBox
        val boxB = b.boundingBox
        if (Rect.intersects(boxA, boxB)) return true

        val bubbleOverlap = rectContainmentRatio(a.bubbleBox, b.bubbleBox)
        val horizOverlap = minOf(boxA.right, boxB.right) - maxOf(boxA.left, boxB.left)
        val minWidth = minOf(boxA.width(), boxB.width()).coerceAtLeast(1)
        val vertGap = maxOf(boxA.top, boxB.top) - minOf(boxA.bottom, boxB.bottom)
        val maxHeight = maxOf(boxA.height(), boxB.height()).coerceAtLeast(1)

        // Stacked horizontal lines in the same speech bubble
        if (horizOverlap > minWidth * 0.35f && vertGap < maxHeight * 0.65f && bubbleOverlap > 0.35f) {
            return true
        }

        // Adjacent vertical columns in the same speech bubble
        val vertOverlap = minOf(boxA.bottom, boxB.bottom) - maxOf(boxA.top, boxB.top)
        val minHeight = minOf(boxA.height(), boxB.height()).coerceAtLeast(1)
        val horizGap = maxOf(boxA.left, boxB.left) - minOf(boxA.right, boxB.right)
        val maxWidth = maxOf(boxA.width(), boxB.width()).coerceAtLeast(1)
        if (vertOverlap > minHeight * 0.35f && horizGap < maxWidth * 0.85f && bubbleOverlap > 0.35f) {
            return true
        }

        // Same detected enclosing speech bubble with nearby text centers
        if (bubbleOverlap > 0.65f && vertGap < 70 && horizGap < 70) {
            return true
        }

        return false
    }

    private fun rectContainmentRatio(a: Rect, b: Rect): Float {
        val interLeft = maxOf(a.left, b.left)
        val interTop = maxOf(a.top, b.top)
        val interRight = minOf(a.right, b.right)
        val interBottom = minOf(a.bottom, b.bottom)
        if (interRight <= interLeft || interBottom <= interTop) return 0f

        val interArea = (interRight - interLeft).toFloat() * (interBottom - interTop).toFloat()
        val areaA = a.width().toFloat() * a.height().toFloat()
        val areaB = b.width().toFloat() * b.height().toFloat()
        val minArea = minOf(areaA, areaB).coerceAtLeast(1f)
        return interArea / minArea
    }

    private fun finishTranslation(ivIcon: ImageView?, pbLoading: ProgressBar?) {
        isTranslating = false
        ivIcon?.visibility = View.VISIBLE
        pbLoading?.visibility = View.GONE
        hideStatus()
    }

    @Suppress("DEPRECATION")
    private fun captureScreen(delayMs: Long = 90L, callback: (Bitmap?, Float) -> Unit) {
        val mp = mediaProjection
        if (mp == null) {
            callback(null, 0f)
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
            var frameShiftY = 0f
            synchronized(bitmapLock) {
                val bmp = latestBitmap
                if (bmp != null && !bmp.isRecycled && hasLatestBitmapFrame) {
                    resultBitmap = bmp.copy(Bitmap.Config.ARGB_8888, false)
                    frameShiftY = latestBitmapShiftY
                }
            }

            if (resultBitmap != null) {
                callback(resultBitmap, frameShiftY)
            } else if (attempts < 6) {
                attempts++
                handler.postDelayed({ checkFrame() }, 50)
            } else {
                callback(null, 0f)
            }
        }

        // Wait for SurfaceFlinger to send the fresh frame without overlay/floating bubble
        handler.postDelayed({ checkFrame() }, delayMs)
    }

    private fun isWebUrlOrDomain(text: String): Boolean {
        val lower = text.lowercase()
        return lower.contains("http://") ||
            lower.contains("https://") ||
            lower.contains(".com") ||
            lower.contains(".net") ||
            lower.contains(".org") ||
            lower.contains(".co.") ||
            lower.contains("www.") ||
            lower.contains(".html") ||
            lower.contains(".php") ||
            lower.contains("webtoons") ||
            lower.contains("scan")
    }

    private val dateTimestampRegex = Regex(
        """\b\d{1,2}\s+(Jan|Feb|Mar|Apr|Mei|May|Jun|Jul|Agu|Aug|Sep|Okt|Oct|Nov|Des|Dec)\b|\b\d{1,2}:\d{2}\b""",
        RegexOption.IGNORE_CASE
    )

    private fun isDateOrTimestamp(text: String): Boolean {
        return dateTimestampRegex.containsMatchIn(text)
    }

    private fun isSearchNavigationChip(text: String): Boolean {
        val lower = text.lowercase().trim()
        val chips = setOf(
            "semua", "gambar", "video", "belanja", "berita", "news",
            "maps", "shopping", "terjemahkan", "google", "mode", "pail",
            "catur", "capcut", "video singkat", "alat", "telusuri", "search"
        )
        return lower in chips
    }

    override fun onDestroy() {
        isRunning = false
        isAutoMode = false
        handler.removeCallbacksAndMessages(null)
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
