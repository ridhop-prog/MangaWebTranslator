package com.pribadi.mangatranslator

import android.animation.ValueAnimator
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
import android.graphics.Color
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
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

class FloatingService : Service() {

    companion object {
        const val CHANNEL_ID = "ScreenMangaTranslatorChannel"
        const val NOTIFICATION_ID = 1001
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_LANG_INDEX = "extra_lang_index"
        const val EXTRA_BG_STYLE = "extra_bg_style"
        const val EXTRA_TEXT_SCALE = "extra_text_scale"
        const val ACTION_STOP = "action_stop_floating_service"
        const val ACTION_UPDATE_BG_STYLE = "action_update_bg_style"
        const val ACTION_UPDATE_LANG = "action_update_lang"
        const val ACTION_UPDATE_TEXT_SCALE = "action_update_text_scale"
        private const val PREF_NAME = "ScreenMangaTranslatorPrefs"
        private const val PREF_KEY_LANG = "pref_key_lang_index"
        private const val PREF_KEY_BG_STYLE = "pref_key_bg_style"
        private const val PREF_KEY_TEXT_SCALE = "pref_key_text_scale"

        // Fast event-driven auto panel capture timings
        private const val SCROLL_DEBOUNCE_MS = 160L
        private const val WATCHDOG_POLL_MS = 450L
        private const val LONG_PRESS_CLEAR_MS = 500L
        private const val IDLE_DIM_DELAY_MS = 3500L
        private const val IDLE_ALPHA = 0.62f

        // Downscale target for ~2.3x faster ML Kit OCR (coords scaled accurately back to full display)
        private const val OCR_MAX_DIMENSION = 1440

        var isRunning = false
            private set
    }

    private data class MergedOcrBlock(
        val boundingBox: Rect,
        val bubbleBox: Rect,
        val cleanText: String
    )

    private lateinit var windowManager: WindowManager
    private lateinit var comicTranslator: ComicTranslator
    private lateinit var scrollTracker: ScrollTracker
    private val handler = Handler(Looper.getMainLooper())

    private var mediaProjection: MediaProjection? = null
    private var currentLangIndex = 0 // 0: Japanese, 1: Korean, 2: English, 3: Chinese
    private var bgStyle = OverlayView.STYLE_SEAMLESS_WHITE
    private var textScale = 1.0f

    @Volatile
    private var isTranslating = false

    private val scanGeneration = AtomicInteger(0)

    // Event-driven Auto Whole-Panel Capture Engine
    @Volatile
    private var isAutoMode = false

    // Triggered immediately 160ms after scroll motion settles (zero polling jitter)
    private val autoPanelDebounceRunnable = Runnable {
        if (!isAutoMode || !isRunning) return@Runnable
        performAutoPanelCapture()
    }

    // Light backup watchdog for non-scroll page changes (e.g. tapping Next Chapter)
    private val autoWatchdogRunnable = object : Runnable {
        override fun run() {
            if (!isAutoMode || !isRunning) return
            performAutoPanelCapture()
            handler.postDelayed(this, WATCHDOG_POLL_MS)
        }
    }

    private val idleDimRunnable = Runnable {
        if (isRunning && !isTranslating) {
            floatingBubbleView?.animate()?.alpha(IDLE_ALPHA)?.setDuration(300)?.start()
        }
    }

    // Persistent Screen Capture Engine (zero-copy during scroll)
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var rawBufferBitmap: Bitmap? = null
    private var latestBitmap: Bitmap? = null
    private var latestBitmapCanvas: Canvas? = null
    @Volatile
    private var hasLatestBitmapFrame = false
    @Volatile
    private var pendingCleanCapture = false
    private val cleanCaptureSeq = AtomicInteger(0)
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
    private var snapAnimator: ValueAnimator? = null
    private var isSnappedLeft = true

    @Volatile
    private var isOverlayAttached = false
    @Volatile
    private var isStatusAttached = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        comicTranslator = ComicTranslator(this)
        scrollTracker = ScrollTracker {
            handler.post {
                if (!isRunning) return@post
                scanGeneration.incrementAndGet()
                scrollTracker.clearReference()
                hideOverlay()
                if (isAutoMode) {
                    handler.removeCallbacks(autoPanelDebounceRunnable)
                    handler.postDelayed(autoPanelDebounceRunnable, 110L)
                }
            }
        }
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_UPDATE_BG_STYLE -> {
                bgStyle = intent.getIntExtra(EXTRA_BG_STYLE, OverlayView.STYLE_SEAMLESS_WHITE)
                overlayView?.setBackgroundStyle(bgStyle)
                updateBgStyleUI()
                return START_STICKY
            }
            ACTION_UPDATE_LANG -> {
                val newLang = intent.getIntExtra(EXTRA_LANG_INDEX, currentLangIndex)
                applyLanguageChange(newLang, showToast = false)
                return START_STICKY
            }
            ACTION_UPDATE_TEXT_SCALE -> {
                textScale = intent.getFloatExtra(EXTRA_TEXT_SCALE, 1.0f).coerceIn(0.75f, 1.35f)
                overlayView?.setTextScale(textScale)
                return START_STICKY
            }
        }

        val prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        currentLangIndex = intent?.getIntExtra(EXTRA_LANG_INDEX, prefs.getInt(PREF_KEY_LANG, 0))
            ?: prefs.getInt(PREF_KEY_LANG, 0)
        bgStyle = intent?.getIntExtra(
            EXTRA_BG_STYLE,
            prefs.getInt(PREF_KEY_BG_STYLE, OverlayView.STYLE_SEAMLESS_WHITE)
        ) ?: prefs.getInt(PREF_KEY_BG_STYLE, OverlayView.STYLE_SEAMLESS_WHITE)
        textScale = intent?.getFloatExtra(
            EXTRA_TEXT_SCALE,
            prefs.getFloat(PREF_KEY_TEXT_SCALE, 1.0f)
        ) ?: prefs.getFloat(PREF_KEY_TEXT_SCALE, 1.0f)

        overlayView?.setBackgroundStyle(bgStyle)
        overlayView?.setTextScale(textScale)
        updateLangBadgeUI()
        updateBgStyleUI()

        // Pre-warm offline ML Kit model immediately in background for instant panel fallback
        comicTranslator.preWarm(currentLangIndex)

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

        floatingBubbleView = layoutInflater.inflate(R.layout.floating_bubble, null)
        val edgeMargin = (8 * resources.displayMetrics.density).toInt()
        bubbleParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = edgeMargin
            y = (resources.displayMetrics.heightPixels * 0.34f).toInt()
        }

        val btnMain = floatingBubbleView?.findViewById<FrameLayout>(R.id.btnFloatingMain)
        val btnToggleAuto = floatingBubbleView?.findViewById<FrameLayout>(R.id.btnToggleAuto)
        val btnToggleBg = floatingBubbleView?.findViewById<FrameLayout>(R.id.btnToggleBg)
        val tvLangBadge = floatingBubbleView?.findViewById<TextView>(R.id.tvLangBadge)
        val btnClose = floatingBubbleView?.findViewById<ImageView>(R.id.btnCloseFloating)
        val pbLoading = floatingBubbleView?.findViewById<ProgressBar>(R.id.pbLoading)
        val ivIcon = floatingBubbleView?.findViewById<ImageView>(R.id.ivIcon)

        updateLangBadgeUI()
        updateAutoModeUI()
        updateBgStyleUI()
        reorderFloatingPillsForEdge(snapLeft = true)

        btnToggleAuto?.setOnClickListener {
            wakeFloatingBubble()
            toggleAutoMode()
        }

        btnToggleBg?.setOnClickListener {
            wakeFloatingBubble()
            toggleBgStyle()
        }

        tvLangBadge?.setOnClickListener {
            wakeFloatingBubble()
            val nextLang = (currentLangIndex + 1) % 4
            applyLanguageChange(nextLang, showToast = true)
        }

        btnClose?.setOnClickListener {
            stopSelf()
        }

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var longPressTriggered = false

        val longPressRunnable = Runnable {
            longPressTriggered = true
            btnMain?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            scanGeneration.incrementAndGet()
            hideOverlay()
            scrollTracker.clearReference()
            Toast.makeText(this, "Tampilan terjemahan dibersihkan", Toast.LENGTH_SHORT).show()
        }

        btnMain?.setOnTouchListener { _, event ->
            val params = bubbleParams ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    snapAnimator?.cancel()
                    wakeFloatingBubble()
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
                    params.y = (initialY + diffY.toInt()).coerceIn(
                        (resources.displayMetrics.heightPixels * 0.06f).toInt(),
                        (resources.displayMetrics.heightPixels * 0.86f).toInt()
                    )
                    windowManager.updateViewLayout(floatingBubbleView, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPressRunnable)
                    if (!longPressTriggered) {
                        val diffX = event.rawX - initialTouchX
                        val diffY = event.rawY - initialTouchY
                        if (hypot(diffX.toDouble(), diffY.toDouble()) < 15) {
                            if (!isTranslating) {
                                startScreenTranslation(ivIcon, pbLoading)
                            } else {
                                Toast.makeText(this, "Sedang menerjemahkan panel...", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            snapBubbleToNearestEdge()
                        }
                    }
                    scheduleIdleDim()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPressRunnable)
                    snapBubbleToNearestEdge()
                    scheduleIdleDim()
                    true
                }
                else -> false
            }
        }

        windowManager.addView(floatingBubbleView, bubbleParams)
        scheduleIdleDim()

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

        // Full physical screen edge-to-edge overlay matching MediaProjection (0, 0) coordinates!
        overlayView = OverlayView(this).apply {
            bgStyle = this@FloatingService.bgStyle
            setTextScale(this@FloatingService.textScale)
            if (captureWidth > 0 && captureHeight > 0) {
                setScreenDimensions(captureWidth, captureHeight)
            }
        }
        overlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    private fun wakeFloatingBubble() {
        handler.removeCallbacks(idleDimRunnable)
        floatingBubbleView?.animate()?.cancel()
        floatingBubbleView?.alpha = 1.0f
    }

    private fun scheduleIdleDim() {
        handler.removeCallbacks(idleDimRunnable)
        handler.postDelayed(idleDimRunnable, IDLE_DIM_DELAY_MS)
    }

    private fun snapBubbleToNearestEdge() {
        val view = floatingBubbleView ?: return
        val params = bubbleParams ?: return
        val screenW = if (captureWidth > 0) captureWidth else resources.displayMetrics.widthPixels
        val viewW = view.width.coerceAtLeast(1)
        val margin = (6 * resources.displayMetrics.density).toInt()

        val centerX = params.x + (viewW / 2)
        val snapLeft = centerX < (screenW / 2)
        val targetX = if (snapLeft) margin else (screenW - viewW - margin).coerceAtLeast(margin)

        if (isSnappedLeft != snapLeft) {
            isSnappedLeft = snapLeft
            reorderFloatingPillsForEdge(snapLeft)
        }

        snapAnimator?.cancel()
        snapAnimator = ValueAnimator.ofInt(params.x, targetX).apply {
            duration = 220L
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                if (!isRunning || floatingBubbleView == null) return@addUpdateListener
                params.x = anim.animatedValue as Int
                try {
                    windowManager.updateViewLayout(floatingBubbleView, params)
                } catch (_: Exception) {
                }
            }
            start()
        }
    }

    private fun reorderFloatingPillsForEdge(snapLeft: Boolean) {
        val root = floatingBubbleView?.findViewById<LinearLayout>(R.id.llFloatingRoot) ?: return
        val pills = floatingBubbleView?.findViewById<LinearLayout>(R.id.llControlPills) ?: return
        val fab = floatingBubbleView?.findViewById<FrameLayout>(R.id.flMainFabContainer) ?: return

        root.removeAllViews()
        if (snapLeft) {
            root.addView(fab)
            root.addView(pills)
        } else {
            root.addView(pills)
            root.addView(fab)
        }
    }

    private fun applyLanguageChange(newLangIndex: Int, showToast: Boolean) {
        currentLangIndex = newLangIndex.coerceIn(0, 3)
        getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(PREF_KEY_LANG, currentLangIndex)
            .apply()
        updateLangBadgeUI()
        comicTranslator.preWarm(currentLangIndex)

        scanGeneration.incrementAndGet()
        scrollTracker.clearReference()
        if (isAutoMode) {
            handler.removeCallbacks(autoPanelDebounceRunnable)
            handler.postDelayed(autoPanelDebounceRunnable, 100L)
        }

        if (showToast) {
            val langName = when (currentLangIndex) {
                0 -> "Jepang (JA)"
                1 -> "Korea (KO)"
                2 -> "Inggris (EN)"
                else -> "Mandarin / Manhua (ZH)"
            }
            Toast.makeText(this, "Bahasa komik: $langName", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateLangBadgeUI() {
        val tvLangBadge = floatingBubbleView?.findViewById<TextView>(R.id.tvLangBadge)
        tvLangBadge?.text = when (currentLangIndex) {
            0 -> "JA"
            1 -> "KO"
            2 -> "EN"
            else -> "ZH"
        }
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
            try {
                windowManager.removeView(floatingStatusView)
            } catch (_: Exception) {
            }
            isStatusAttached = false
        }
    }

    private fun toggleBgStyle() {
        bgStyle = if (bgStyle == OverlayView.STYLE_SEAMLESS_WHITE) {
            OverlayView.STYLE_TRANSPARENT
        } else {
            OverlayView.STYLE_SEAMLESS_WHITE
        }
        getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(PREF_KEY_BG_STYLE, bgStyle)
            .apply()
        overlayView?.setBackgroundStyle(bgStyle)
        updateBgStyleUI()

        val msg = if (bgStyle == OverlayView.STYLE_SEAMLESS_WHITE) {
            "BG Putih Solid 100% AKTIF (Menutupi bubble asli)"
        } else {
            "Mode Transparan AKTIF"
        }
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        scheduleIdleDim()
    }

    private fun updateBgStyleUI() {
        val btnBg = floatingBubbleView?.findViewById<FrameLayout>(R.id.btnToggleBg)
        val tvBgText = floatingBubbleView?.findViewById<TextView>(R.id.tvBgText)
        val viewBgDot = floatingBubbleView?.findViewById<View>(R.id.viewBgDot)

        if (bgStyle == OverlayView.STYLE_SEAMLESS_WHITE) {
            btnBg?.setBackgroundResource(R.drawable.bg_style_toggle_on)
            viewBgDot?.setBackgroundResource(R.drawable.bg_auto_dot_on)
            tvBgText?.text = "BG PUTIH"
        } else {
            btnBg?.setBackgroundResource(R.drawable.bg_auto_toggle_off)
            viewBgDot?.setBackgroundResource(R.drawable.bg_auto_dot_off)
            tvBgText?.text = "BG OFF"
        }
    }

    private fun toggleAutoMode() {
        isAutoMode = !isAutoMode
        updateAutoModeUI()
        scanGeneration.incrementAndGet()

        if (isAutoMode) {
            scrollTracker.clearReference()
            hideOverlay()
            scrollTracker.reset(0f)
            overlayView?.resetScrollState(0f)
            handler.removeCallbacks(autoPanelDebounceRunnable)
            handler.removeCallbacks(autoWatchdogRunnable)
            handler.post(autoPanelDebounceRunnable)
            handler.postDelayed(autoWatchdogRunnable, WATCHDOG_POLL_MS)
            Toast.makeText(
                this,
                "Mode Auto Panel AKTIF: Seluruh panel diterjemahkan otomatis",
                Toast.LENGTH_SHORT
            ).show()
        } else {
            handler.removeCallbacks(autoPanelDebounceRunnable)
            handler.removeCallbacks(autoWatchdogRunnable)
            hideOverlay()
            Toast.makeText(this, "Mode Auto NONAKTIF", Toast.LENGTH_SHORT).show()
        }
        scheduleIdleDim()
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

    /**
     * Event-driven automatic whole-panel capture:
     * Fires immediately ~160ms after scrolling settles, captures the full clean panel(s)
     * in ~55ms, and translates ALL bubbles across the panel in one single batch shot!
     */
    private fun performAutoPanelCapture() {
        if (isTranslating || mediaProjection == null || virtualDisplay == null) return
        if (!scrollTracker.isScrollingSettled(SCROLL_DEBOUNCE_MS)) return

        val minShiftPx = captureHeight * 0.055f
        if (!scrollTracker.hasChangedSinceReference(minShiftPx)) {
            return
        }

        val ivIcon = floatingBubbleView?.findViewById<ImageView>(R.id.ivIcon)
        val pbLoading = floatingBubbleView?.findViewById<ProgressBar>(R.id.pbLoading)

        val generation = scanGeneration.incrementAndGet()
        isTranslating = true
        ivIcon?.visibility = View.GONE
        pbLoading?.visibility = View.VISIBLE

        val hadAttachedOverlay = isOverlayAttached
        if (hadAttachedOverlay) {
            overlayView?.visibility = View.INVISIBLE
        }

        captureCleanScreen { cleanBmp, cleanShiftY ->
            if (hadAttachedOverlay && isOverlayAttached && generation == scanGeneration.get()) {
                overlayView?.visibility = View.VISIBLE
            }

            if (!isAutoMode || !isRunning || cleanBmp == null || generation != scanGeneration.get()) {
                cleanBmp?.recycle()
                finishTranslation(ivIcon, pbLoading)
                return@captureCleanScreen
            }

            scrollTracker.recordCleanReference(cleanBmp, cleanShiftY)
            processPanelTranslation(cleanBmp, cleanShiftY, generation, ivIcon, pbLoading, isAuto = true)
        }
    }

    private fun showOverlay(blocks: List<TranslatedBlock>) {
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        overlayParams?.flags = flags
        if (captureWidth > 0 && captureHeight > 0) {
            overlayParams?.width = captureWidth
            overlayParams?.height = captureHeight
            overlayView?.setScreenDimensions(captureWidth, captureHeight)
        }

        overlayView?.setBackgroundStyle(bgStyle)
        overlayView?.setTextScale(textScale)
        overlayView?.visibility = View.VISIBLE

        if (!isOverlayAttached && overlayView != null) {
            windowManager.addView(overlayView, overlayParams)
            isOverlayAttached = true
        }

        overlayView?.resetScrollState(scrollTracker.currentShiftY)
        overlayView?.setTranslatedBlocks(blocks)
    }

    private fun hideOverlay() {
        overlayView?.clear()
        overlayView?.visibility = View.VISIBLE
        if (isOverlayAttached && overlayView != null) {
            try {
                windowManager.removeView(overlayView)
            } catch (_: Exception) {
            }
            isOverlayAttached = false
        }
    }

    private fun collectMaskedRectsForTracker(): List<Rect> {
        val overlay = overlayView
        val overlayRects = if (isOverlayAttached && overlay != null &&
            overlay.visibility == View.VISIBLE && overlay.hasBlocks()
        ) {
            overlay.getMaskedScreenRects(scrollTracker.currentShiftY, extraPadding = 14)
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
        overlayView?.setScreenDimensions(captureWidth, captureHeight)

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

                    // 1. ULTRA-FAST PATH (< 0.4ms): track scroll directly from ByteBuffer
                    val hasActiveOverlay = isOverlayAttached && (overlayView?.hasBlocks() == true)
                    if (hasActiveOverlay || isAutoMode || isTranslating) {
                        val maskedRects = collectMaskedRectsForTracker()
                        val moved = scrollTracker.processFrame(
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
                        // Event-driven Auto Panel debounce: fires immediately 160ms after scroll stops!
                        if (moved && isAutoMode) {
                            handler.removeCallbacks(autoPanelDebounceRunnable)
                            handler.postDelayed(autoPanelDebounceRunnable, SCROLL_DEBOUNCE_MS)
                        }
                    }

                    // 2. ZERO-COPY DURING SCROLL: only copy full-screen bitmap when explicitly requested
                    if (pendingCleanCapture || !hasLatestBitmapFrame) {
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

                        if (pendingCleanCapture) {
                            pendingCleanCapture = false
                            cleanCaptureSeq.incrementAndGet()
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
            pendingCleanCapture = false
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
            scrollTracker.clearReference()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startScreenTranslation(ivIcon: ImageView?, pbLoading: ProgressBar?) {
        val generation = scanGeneration.incrementAndGet()
        isTranslating = true
        wakeFloatingBubble()
        ivIcon?.visibility = View.GONE
        pbLoading?.visibility = View.VISIBLE

        hideOverlay()
        scrollTracker.reset(0f)
        showStatus(getString(R.string.status_capturing))

        floatingBubbleView?.visibility = View.INVISIBLE

        captureCleanScreen { bitmap, scanShiftY ->
            floatingBubbleView?.visibility = View.VISIBLE

            if (bitmap == null || generation != scanGeneration.get()) {
                bitmap?.recycle()
                finishTranslation(ivIcon, pbLoading)
                if (bitmap == null) {
                    Toast.makeText(this, getString(R.string.status_failed), Toast.LENGTH_SHORT).show()
                }
                return@captureCleanScreen
            }

            scrollTracker.recordCleanReference(bitmap, scanShiftY)
            processPanelTranslation(bitmap, scanShiftY, generation, ivIcon, pbLoading, isAuto = false)
        }
    }

    /**
     * Whole-Panel Fast OCR & Single-Shot Batch Translation:
     * 1. Scales panel screenshot to max 1440px (~2.3x faster ML Kit OCR!).
     * 2. Maps detected text boxes accurately back to full physical display coordinates.
     * 3. Merges multi-line text inside each comic speech bubble & detects bubble boundaries on full-res bitmap.
     * 4. Translates ALL speech bubbles of the entire panel together in ONE single batch call!
     */
    private fun processPanelTranslation(
        fullBitmap: Bitmap,
        scanShiftY: Float,
        generation: Int,
        ivIcon: ImageView?,
        pbLoading: ProgressBar?,
        isAuto: Boolean = false
    ) {
        if (!isAuto) {
            showStatus(getString(R.string.status_ocr))
        }

        val recognizer: TextRecognizer = when (currentLangIndex) {
            0 -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
            1 -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
            3 -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            else -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        }

        // Fast scaled bitmap for ML Kit OCR (~65ms instead of ~200ms)
        val maxDim = maxOf(fullBitmap.width, fullBitmap.height)
        val ocrScale = if (maxDim > OCR_MAX_DIMENSION) {
            OCR_MAX_DIMENSION.toFloat() / maxDim.toFloat()
        } else {
            1.0f
        }
        val invScale = 1.0f / ocrScale
        val ocrBitmap = if (ocrScale < 0.99f) {
            val sw = (fullBitmap.width * ocrScale).roundToInt().coerceAtLeast(1)
            val sh = (fullBitmap.height * ocrScale).roundToInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(fullBitmap, sw, sh, true)
        } else {
            fullBitmap
        }

        val inputImage = InputImage.fromBitmap(ocrBitmap, 0)
        recognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                if (ocrBitmap !== fullBitmap) {
                    ocrBitmap.recycle()
                }
                recognizer.close()

                if (generation != scanGeneration.get() || (isAuto && !isAutoMode)) {
                    fullBitmap.recycle()
                    finishTranslation(ivIcon, pbLoading)
                    return@addOnSuccessListener
                }

                val bubbleRect = getBubbleScreenRect(padding = 20) ?: Rect()
                val rawItems = ArrayList<MergedOcrBlock>()

                for (block in visionText.textBlocks) {
                    val rawBox = block.boundingBox ?: continue
                    // Map OCR coordinates cleanly back to full physical screen coordinates
                    val box = if (ocrScale < 0.99f) {
                        Rect(
                            (rawBox.left * invScale).roundToInt().coerceIn(0, fullBitmap.width),
                            (rawBox.top * invScale).roundToInt().coerceIn(0, fullBitmap.height),
                            (rawBox.right * invScale).roundToInt().coerceIn(0, fullBitmap.width),
                            (rawBox.bottom * invScale).roundToInt().coerceIn(0, fullBitmap.height)
                        )
                    } else {
                        Rect(rawBox)
                    }

                    if (box.width() < 14 || box.height() < 14) continue
                    if (box.top < captureHeight * 0.052f) continue
                    if (box.bottom > captureHeight * 0.938f) continue
                    if (!bubbleRect.isEmpty && Rect.intersects(box, bubbleRect)) continue

                    val cleanText = comicTranslator.extractCleanBlockText(block)
                    val isCjkOrHangul = comicTranslator.containsJapaneseOrChinese(cleanText) ||
                        comicTranslator.containsHangul(cleanText)
                    val letterCount = cleanText.count { it.isLetter() }
                    if (isCjkOrHangul) {
                        if (letterCount < 1) continue
                    } else {
                        if (letterCount < 2) continue
                    }

                    if (isWebUrlOrDomain(cleanText)) continue
                    if (isDateOrTimestamp(cleanText)) continue
                    if (box.width() > captureWidth * 0.85f && cleanText.length > 55) continue
                    if (box.top < captureHeight * 0.20f && isSearchNavigationChip(cleanText)) continue

                    val bubbleBounds = SpeechBubbleDetector.detectBubbleBounds(fullBitmap, box)
                    rawItems.add(MergedOcrBlock(box, bubbleBounds, cleanText))
                }

                val bmpWidth = fullBitmap.width.coerceAtLeast(1).toFloat()
                val bmpHeight = fullBitmap.height.coerceAtLeast(1).toFloat()
                val visionJpegBytes = if (rawItems.isNotEmpty() && comicTranslator.isGeminiVisionReady()) {
                    encodeScreenVisionJpeg(fullBitmap)
                } else {
                    null
                }

                fullBitmap.recycle()

                if (rawItems.isEmpty()) {
                    finishTranslation(ivIcon, pbLoading)
                    hideOverlay()
                    if (!isAuto) {
                        Toast.makeText(this, getString(R.string.status_no_text), Toast.LENGTH_SHORT).show()
                    }
                    return@addOnSuccessListener
                }

                // Merge multi-line text inside each speech bubble & order panels top-to-bottom
                val panelBubbles = mergeSameBubbleBlocks(rawItems)
                    .sortedWith(compareBy<MergedOcrBlock> { it.bubbleBox.top / 160 }.thenBy { it.bubbleBox.left })

                if (!isAuto) {
                    showStatus(getString(R.string.status_translating))
                }

                val translationRequests = panelBubbles.map { item ->
                    val srcLang = comicTranslator.detectBubbleSourceLang(item.cleanText, currentLangIndex)
                    val normBox = intArrayOf(
                        (item.bubbleBox.top * 1000f / bmpHeight).roundToInt().coerceIn(0, 1000),
                        (item.bubbleBox.left * 1000f / bmpWidth).roundToInt().coerceIn(0, 1000),
                        (item.bubbleBox.bottom * 1000f / bmpHeight).roundToInt().coerceIn(0, 1000),
                        (item.bubbleBox.right * 1000f / bmpWidth).roundToInt().coerceIn(0, 1000)
                    )
                    ComicTranslator.TranslationRequest(item.cleanText, srcLang, normBox)
                }

                // Single-shot whole-panel batch translation (AI Comic + Vision or Multi-Engine Anti-Limit)
                comicTranslator.translatePanelBatch(translationRequests, visionJpegBytes) { translatedList ->
                    finishTranslation(ivIcon, pbLoading)

                    val scrolledTooFar = abs(scrollTracker.currentShiftY - scanShiftY) > captureHeight * 0.65f
                    if (generation != scanGeneration.get() || (isAuto && !isAutoMode) || scrolledTooFar) {
                        return@translatePanelBatch
                    }

                    val results = mutableListOf<TranslatedBlock>()
                    for (i in panelBubbles.indices) {
                        val translated = translatedList.getOrNull(i)
                        val item = panelBubbles[i]
                        if (!translated.isNullOrBlank()) {
                            results.add(
                                TranslatedBlock(
                                    boundingBox = item.boundingBox,
                                    translatedText = translated,
                                    bubbleBox = item.bubbleBox,
                                    captureShiftY = scanShiftY,
                                    originalText = item.cleanText,
                                    bubbleBgColor = Color.WHITE
                                )
                            )
                        }
                    }

                    if (results.isNotEmpty()) {
                        showOverlay(results)
                        if (!isAuto) {
                            Toast.makeText(
                                this,
                                "Panel diterjemahkan (${results.size} balon teks).",
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
                if (ocrBitmap !== fullBitmap) {
                    ocrBitmap.recycle()
                }
                fullBitmap.recycle()
                finishTranslation(ivIcon, pbLoading)
                recognizer.close()
                if (!isAuto) {
                    Toast.makeText(this, "Gagal memindai: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                }
            }
    }

    private fun mergeSameBubbleBlocks(items: List<MergedOcrBlock>): List<MergedOcrBlock> {
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

            val isVerticalLayout = group.count {
                it.boundingBox.height() > it.boundingBox.width() * 1.35
            } > group.size / 2
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
            val parts = ArrayList<String>(sorted.size)
            for (item in sorted) {
                unionTextBox.union(item.boundingBox)
                unionBubbleBox.union(item.bubbleBox)
                parts.add(item.cleanText)
            }

            val combinedText = comicTranslator.joinBubbleParts(parts)
            mergedList.add(
                MergedOcrBlock(
                    boundingBox = unionTextBox,
                    bubbleBox = unionBubbleBox,
                    cleanText = combinedText
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

        if (horizOverlap > minWidth * 0.35f && vertGap < maxHeight * 0.70f && bubbleOverlap > 0.35f) {
            return true
        }

        val vertOverlap = minOf(boxA.bottom, boxB.bottom) - maxOf(boxA.top, boxB.top)
        val minHeight = minOf(boxA.height(), boxB.height()).coerceAtLeast(1)
        val horizGap = maxOf(boxA.left, boxB.left) - minOf(boxA.right, boxB.right)
        val maxWidth = maxOf(boxA.width(), boxB.width()).coerceAtLeast(1)
        if (vertOverlap > minHeight * 0.35f && horizGap < maxWidth * 0.85f && bubbleOverlap > 0.35f) {
            return true
        }

        if (bubbleOverlap > 0.65f && vertGap < 75 && horizGap < 75) {
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
        scheduleIdleDim()
    }

    @Suppress("DEPRECATION")
    private fun captureCleanScreen(
        callback: (Bitmap?, Float) -> Unit
    ) {
        val mp = mediaProjection
        if (mp == null) {
            callback(null, 0f)
            return
        }

        if (virtualDisplay == null) {
            initScreenCapture()
        }

        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        if (metrics.widthPixels != captureWidth || metrics.heightPixels != captureHeight) {
            stopScreenCapture()
            initScreenCapture()
        }

        val seqBefore = cleanCaptureSeq.get()
        // Wait 24ms for SurfaceFlinger to clear overlay, then capture fresh clean panel frame (~50ms total)
        handler.postDelayed({
            pendingCleanCapture = true
            val nudgeTarget = when {
                floatingBubbleView?.visibility == View.VISIBLE -> floatingBubbleView
                isStatusAttached && floatingStatusView?.visibility == View.VISIBLE -> floatingStatusView
                else -> null
            }
            nudgeTarget?.let { v ->
                val cur = v.alpha
                v.alpha = if (cur > 0.96f) cur - 0.01f else cur + 0.01f
            }

            var attempts = 0
            fun checkFrame() {
                val gotFresh = cleanCaptureSeq.get() > seqBefore
                if (gotFresh || attempts >= 5) {
                    pendingCleanCapture = false
                    var resultBitmap: Bitmap? = null
                    var frameShiftY = 0f
                    synchronized(bitmapLock) {
                        val bmp = latestBitmap
                        if (bmp != null && !bmp.isRecycled && hasLatestBitmapFrame) {
                            resultBitmap = bmp.copy(Bitmap.Config.ARGB_8888, false)
                            frameShiftY = latestBitmapShiftY
                        }
                    }
                    callback(resultBitmap, frameShiftY)
                } else {
                    attempts++
                    handler.postDelayed({ checkFrame() }, 20L)
                }
            }

            handler.postDelayed({ checkFrame() }, 24L)
        }, 24L)
    }

    private fun isWebUrlOrDomain(text: String): Boolean {
        val lower = text.lowercase()
        return lower.contains("http://") ||
            lower.contains("https://") ||
            lower.contains("www.") ||
            lower.contains(".com") ||
            lower.contains(".net") ||
            lower.contains(".org") ||
            lower.contains(".co.") ||
            lower.contains(".xyz") ||
            lower.contains(".html") ||
            lower.contains(".php") ||
            lower.contains("webtoons") ||
            lower.contains("asurascans") ||
            lower.contains("reaperscans")
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
            "maps", "shopping", "terjemahkan", "google", "pail",
            "catur", "capcut", "video singkat", "alat", "telusuri", "search"
        )
        return lower in chips
    }

    override fun onDestroy() {
        isRunning = false
        isAutoMode = false
        scanGeneration.incrementAndGet()
        snapAnimator?.cancel()
        handler.removeCallbacksAndMessages(null)
        hideOverlay()
        hideStatus()

        if (floatingBubbleView != null) {
            try {
                windowManager.removeView(floatingBubbleView)
            } catch (_: Exception) {
            }
            floatingBubbleView = null
        }

        stopScreenCapture()
        comicTranslator.shutdown()

        mediaProjection?.stop()
        mediaProjection = null

        super.onDestroy()
    }

    private fun encodeScreenVisionJpeg(bitmap: Bitmap): ByteArray? {
        return try {
            val maxW = 720f
            val maxH = 1280f
            val scale = minOf(1f, maxW / bitmap.width.toFloat(), maxH / bitmap.height.toFloat())
            val scaled = if (scale < 0.99f) {
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).roundToInt().coerceAtLeast(1),
                    (bitmap.height * scale).roundToInt().coerceAtLeast(1),
                    true
                )
            } else {
                bitmap
            }
            val bos = java.io.ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 74, bos)
            if (scaled !== bitmap) scaled.recycle()
            bos.toByteArray()
        } catch (_: Exception) {
            null
        }
    }
}
