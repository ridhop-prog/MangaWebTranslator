package com.pribadi.mangatranslator

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.pribadi.mangatranslator.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private lateinit var projectionManager: MediaProjectionManager

    private val PREF_NAME = "ScreenMangaTranslatorPrefs"
    private val PREF_KEY_LANG = "pref_key_lang_index"
    private val PREF_KEY_BG_STYLE = "pref_key_bg_style"
    private val PREF_KEY_TEXT_SCALE = "pref_key_text_scale"

    private var selectedLangIndex = 0 // 0: JA, 1: KO, 2: EN, 3: ZH
    private var selectedBgStyle = OverlayView.STYLE_SEAMLESS_WHITE
    private var selectedTextScale = 1.0f

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            launchFloatingService(result.resultCode, result.data!!)
        } else {
            Toast.makeText(
                this,
                "Izin rekam layar diperlukan untuk mendeteksi teks komik.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // Notification permission granted or denied
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        loadSavedSettings()
        setupLanguageChips()
        setupBgStyleCards()
        setupTextScalePills()
        setupButtons()
        requestNotificationPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        loadSavedSettings()
        updateLanguageChipUI()
        updateBgStyleUI()
        updateTextScaleUI()
        updateUIState()
    }

    private fun loadSavedSettings() {
        selectedLangIndex = prefs.getInt(PREF_KEY_LANG, 0).coerceIn(0, 3)
        selectedBgStyle = prefs.getInt(PREF_KEY_BG_STYLE, OverlayView.STYLE_SEAMLESS_WHITE)
        selectedTextScale = prefs.getFloat(PREF_KEY_TEXT_SCALE, 1.0f)
    }

    private fun setupLanguageChips() {
        binding.chipLangJa.setOnClickListener { selectLanguage(0) }
        binding.chipLangKo.setOnClickListener { selectLanguage(1) }
        binding.chipLangEn.setOnClickListener { selectLanguage(2) }
        binding.chipLangZh.setOnClickListener { selectLanguage(3) }
        updateLanguageChipUI()
    }

    private fun selectLanguage(index: Int) {
        selectedLangIndex = index
        prefs.edit().putInt(PREF_KEY_LANG, index).apply()
        updateLanguageChipUI()

        if (FloatingService.isRunning) {
            val intent = Intent(this, FloatingService::class.java).apply {
                action = FloatingService.ACTION_UPDATE_LANG
                putExtra(FloatingService.EXTRA_LANG_INDEX, index)
            }
            startService(intent)
        }
    }

    private fun updateLanguageChipUI() {
        val chips = listOf(
            Triple(0, binding.chipLangJa, binding.tvLangJaTitle),
            Triple(1, binding.chipLangKo, binding.tvLangKoTitle),
            Triple(2, binding.chipLangEn, binding.tvLangEnTitle),
            Triple(3, binding.chipLangZh, binding.tvLangZhTitle)
        )
        for ((idx, container, title) in chips) {
            val isSelected = idx == selectedLangIndex
            container.setBackgroundResource(
                if (isSelected) R.drawable.bg_chip_selected else R.drawable.bg_chip_unselected
            )
            title.setTextColor(if (isSelected) Color.WHITE else Color.parseColor("#CFD8DC"))
        }
    }

    private fun setupBgStyleCards() {
        binding.cardBgSolid.setOnClickListener { selectBgStyle(OverlayView.STYLE_SEAMLESS_WHITE) }
        binding.cardBgTransparent.setOnClickListener { selectBgStyle(OverlayView.STYLE_TRANSPARENT) }
        updateBgStyleUI()
    }

    private fun selectBgStyle(style: Int) {
        selectedBgStyle = style
        prefs.edit().putInt(PREF_KEY_BG_STYLE, style).apply()
        updateBgStyleUI()

        if (FloatingService.isRunning) {
            val updateIntent = Intent(this, FloatingService::class.java).apply {
                action = FloatingService.ACTION_UPDATE_BG_STYLE
                putExtra(FloatingService.EXTRA_BG_STYLE, style)
            }
            startService(updateIntent)
        }
    }

    private fun updateBgStyleUI() {
        val isSolid = selectedBgStyle == OverlayView.STYLE_SEAMLESS_WHITE
        binding.cardBgSolid.setBackgroundResource(
            if (isSolid) R.drawable.bg_chip_selected else R.drawable.bg_chip_unselected
        )
        binding.cardBgTransparent.setBackgroundResource(
            if (!isSolid) R.drawable.bg_chip_selected else R.drawable.bg_chip_unselected
        )
        binding.tvBgSolidLabel.setTextColor(if (isSolid) Color.WHITE else Color.parseColor("#CFD8DC"))
        binding.tvBgTransparentLabel.setTextColor(if (!isSolid) Color.WHITE else Color.parseColor("#CFD8DC"))
    }

    private fun setupTextScalePills() {
        binding.chipScaleSmall.setOnClickListener { selectTextScale(0.85f) }
        binding.chipScaleNormal.setOnClickListener { selectTextScale(1.0f) }
        binding.chipScaleLarge.setOnClickListener { selectTextScale(1.2f) }
        updateTextScaleUI()
    }

    private fun selectTextScale(scale: Float) {
        selectedTextScale = scale
        prefs.edit().putFloat(PREF_KEY_TEXT_SCALE, scale).apply()
        updateTextScaleUI()

        if (FloatingService.isRunning) {
            val intent = Intent(this, FloatingService::class.java).apply {
                action = FloatingService.ACTION_UPDATE_TEXT_SCALE
                putExtra(FloatingService.EXTRA_TEXT_SCALE, scale)
            }
            startService(intent)
        }
    }

    private fun updateTextScaleUI() {
        val isSmall = selectedTextScale < 0.92f
        val isLarge = selectedTextScale > 1.1f
        val isNormal = !isSmall && !isLarge

        binding.chipScaleSmall.setBackgroundResource(
            if (isSmall) R.drawable.bg_chip_selected else R.drawable.bg_chip_unselected
        )
        binding.chipScaleNormal.setBackgroundResource(
            if (isNormal) R.drawable.bg_chip_selected else R.drawable.bg_chip_unselected
        )
        binding.chipScaleLarge.setBackgroundResource(
            if (isLarge) R.drawable.bg_chip_selected else R.drawable.bg_chip_unselected
        )

        binding.chipScaleSmall.setTextColor(if (isSmall) Color.WHITE else Color.parseColor("#CFD8DC"))
        binding.chipScaleNormal.setTextColor(if (isNormal) Color.WHITE else Color.parseColor("#CFD8DC"))
        binding.chipScaleLarge.setTextColor(if (isLarge) Color.WHITE else Color.parseColor("#CFD8DC"))
    }

    private fun setupButtons() {
        binding.btnGrantOverlay.setOnClickListener {
            openOverlaySettings()
        }

        binding.btnOpenWebReader.setOnClickListener {
            preDownloadLanguageModel(selectedLangIndex)
            startActivity(Intent(this, WebComicActivity::class.java))
        }

        binding.btnStartFloating.setOnClickListener {
            if (!hasOverlayPermission()) {
                openOverlaySettings()
                Toast.makeText(
                    this,
                    "Aktifkan izin 'Tampilkan di atas aplikasi lain' terlebih dahulu.",
                    Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }

            preDownloadLanguageModel(selectedLangIndex)
            screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent())
        }

        binding.btnStopFloating.setOnClickListener {
            stopFloatingService()
        }
    }

    private fun preDownloadLanguageModel(langIndex: Int) {
        val languagesToPreload = listOf(
            TranslateLanguage.ENGLISH,
            when (langIndex) {
                0 -> TranslateLanguage.JAPANESE
                1 -> TranslateLanguage.KOREAN
                3 -> TranslateLanguage.CHINESE
                else -> TranslateLanguage.ENGLISH
            }
        ).distinct()

        for (sourceLang in languagesToPreload) {
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(sourceLang)
                .setTargetLanguage(TranslateLanguage.INDONESIAN)
                .build()
            val client = Translation.getClient(options)
            val conditions = DownloadConditions.Builder().build()
            client.downloadModelIfNeeded(conditions)
                .addOnCompleteListener {
                    client.close()
                }
        }
    }

    private fun hasOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

    private fun openOverlaySettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun launchFloatingService(resultCode: Int, resultData: Intent) {
        val serviceIntent = Intent(this, FloatingService::class.java).apply {
            putExtra(FloatingService.EXTRA_RESULT_CODE, resultCode)
            putExtra(FloatingService.EXTRA_RESULT_DATA, resultData)
            putExtra(FloatingService.EXTRA_LANG_INDEX, selectedLangIndex)
            putExtra(FloatingService.EXTRA_BG_STYLE, selectedBgStyle)
            putExtra(FloatingService.EXTRA_TEXT_SCALE, selectedTextScale)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(this, serviceIntent)
        } else {
            startService(serviceIntent)
        }

        Toast.makeText(
            this,
            "Tombol melayang aktif! Buka aplikasi komik favorit Anda.",
            Toast.LENGTH_LONG
        ).show()

        updateUIState()
        moveTaskToBack(true)
    }

    private fun stopFloatingService() {
        val stopIntent = Intent(this, FloatingService::class.java).apply {
            action = FloatingService.ACTION_STOP
        }
        startService(stopIntent)

        Toast.makeText(this, "Layanan tombol melayang dihentikan.", Toast.LENGTH_SHORT).show()
        updateUIState()
    }

    private fun updateUIState() {
        val overlayGranted = hasOverlayPermission()
        binding.cardOverlayAlert.visibility = if (overlayGranted) View.GONE else View.VISIBLE

        val serviceRunning = FloatingService.isRunning
        if (serviceRunning) {
            binding.llStatusPill.setBackgroundResource(R.drawable.bg_status_active)
            binding.viewStatusDot.setBackgroundResource(R.drawable.bg_auto_dot_on)
            binding.tvServiceStatus.text = "Aktif"
            binding.tvServiceStatus.setTextColor(Color.parseColor("#00E676"))
            binding.btnStartFloating.text = "Refresh Izin Layar"
            binding.btnStopFloating.visibility = View.VISIBLE
        } else {
            binding.llStatusPill.setBackgroundResource(R.drawable.bg_status_idle)
            binding.viewStatusDot.setBackgroundResource(R.drawable.bg_auto_dot_off)
            binding.tvServiceStatus.text = "Siap"
            binding.tvServiceStatus.setTextColor(Color.parseColor("#FFB74D"))
            binding.btnStartFloating.text = "Tombol Melayang (Untuk Aplikasi Lain)"
            binding.btnStopFloating.visibility = View.GONE
        }
    }
}
