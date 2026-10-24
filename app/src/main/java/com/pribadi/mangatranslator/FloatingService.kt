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
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
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
                }
            }, handler)
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

    private fun showOverlay(blocks: List<TranslatedBlock>) {
        if (!isOverlayAttached && overlayView != null) {
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

    private fun startScreenTranslation(ivIcon: ImageView?, pbLoading: ProgressBar?) {
        isTranslating = true
        ivIcon?.visibility = View.GONE
        pbLoading?.visibility = View.VISIBLE

        hideOverlay()
        showStatus(getString(R.string.status_capturing))

        // Temporarily hide bubble for clean screenshot capture
        floatingBubbleView?.visibility = View.INVISIBLE

        handler.postDelayed({
            captureScreen { bitmap ->
                floatingBubbleView?.visibility = View.VISIBLE

                if (bitmap == null) {
                    finishTranslation(ivIcon, pbLoading)
                    Toast.makeText(this, getString(R.string.status_failed), Toast.LENGTH_SHORT).show()
                    return@captureScreen
                }

                processBitmapTranslation(bitmap, ivIcon, pbLoading)
            }
        }, 120)
    }

    private fun processBitmapTranslation(
        bitmap: Bitmap,
        ivIcon: ImageView?,
        pbLoading: ProgressBar?
    ) {
        showStatus(getString(R.string.status_ocr))

        val (recognizer: TextRecognizer, sourceLangCode: String) = when (currentLangIndex) {
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
                val validBlocks = visionText.textBlocks.filter { block ->
                    val box = block.boundingBox ?: return@filter false
                    if (box.width() < 15 || box.height() < 15) return@filter false
                    val cleanText = block.text.replace("\n", " ").trim()
                    cleanText.any { it.isLetter() }
                }

                if (validBlocks.isEmpty()) {
                    finishTranslation(ivIcon, pbLoading)
                    recognizer.close()
                    Toast.makeText(this, getString(R.string.status_no_text), Toast.LENGTH_SHORT).show()
                    return@addOnSuccessListener
                }

                showStatus(getString(R.string.status_downloading_model))

                val translatorOptions = TranslatorOptions.Builder()
                    .setSourceLanguage(sourceLangCode)
                    .setTargetLanguage(TranslateLanguage.INDONESIAN)
                    .build()

                val translator = Translation.getClient(translatorOptions)
                val conditions = DownloadConditions.Builder().build()

                translator.downloadModelIfNeeded(conditions)
                    .addOnSuccessListener {
                        showStatus(getString(R.string.status_translating))

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
                                    Toast.makeText(
                                        this,
                                        "Terjemahan siap. Ketuk layar untuk menutup.",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                } else {
                                    Toast.makeText(this, getString(R.string.status_no_text), Toast.LENGTH_SHORT).show()
                                }
                            }
                    }
                    .addOnFailureListener { e ->
                        finishTranslation(ivIcon, pbLoading)
                        recognizer.close()
                        translator.close()
                        Toast.makeText(this, "Gagal mengunduh kamus: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                    }
            }
            .addOnFailureListener { e ->
                finishTranslation(ivIcon, pbLoading)
                recognizer.close()
                Toast.makeText(this, "Gagal memindai: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
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

        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val dpi = metrics.densityDpi

        val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        var virtualDisplay: VirtualDisplay? = null
        var isCaptured = false

        imageReader.setOnImageAvailableListener({ reader ->
            if (isCaptured) return@setOnImageAvailableListener
            var image: Image? = null
            try {
                image = reader.acquireLatestImage()
                if (image != null) {
                    isCaptured = true
                    val planes = image.planes
                    val buffer = planes[0].buffer
                    val pixelStride = planes[0].pixelStride
                    val rowStride = planes[0].rowStride
                    val rowPadding = rowStride - pixelStride * width

                    val bitmap = Bitmap.createBitmap(
                        width + rowPadding / pixelStride,
                        height,
                        Bitmap.Config.ARGB_8888
                    )
                    bitmap.copyPixelsFromBuffer(buffer)

                    val finalBitmap = if (rowPadding == 0) {
                        bitmap
                    } else {
                        val cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height)
                        bitmap.recycle()
                        cropped
                    }

                    handler.post {
                        virtualDisplay?.release()
                        imageReader.close()
                        callback(finalBitmap)
                    }
                }
            } catch (e: Exception) {
                handler.post {
                    virtualDisplay?.release()
                    imageReader.close()
                    callback(null)
                }
            } finally {
                image?.close()
            }
        }, handler)

        virtualDisplay = mp.createVirtualDisplay(
            "ScreenMangaCapture",
            width,
            height,
            dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface,
            null,
            handler
        )

        // Failsafe timeout in case device delays image generation
        handler.postDelayed({
            if (!isCaptured) {
                isCaptured = true
                virtualDisplay?.release()
                imageReader.close()
                callback(null)
            }
        }, 2000)
    }

    override fun onDestroy() {
        isRunning = false
        hideOverlay()
        hideStatus()

        if (floatingBubbleView != null) {
            windowManager.removeView(floatingBubbleView)
            floatingBubbleView = null
        }

        mediaProjection?.stop()
        mediaProjection = null

        super.onDestroy()
    }
}
