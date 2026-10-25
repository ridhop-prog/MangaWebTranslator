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
import android.widget.ArrayAdapter
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

    // Launcher for Screen Capture MediaProjection
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

    // Launcher for Notification Permission (Android 13+)
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

        setupLanguageSpinner()
        setupBgStyleSelector()
        setupButtons()
        requestNotificationPermissionIfNeeded()

        // Check overlay permission on start
        checkOverlayPermissionPrompt()
    }

    override fun onResume() {
        super.onResume()
        updateUIState()
    }

    private fun setupLanguageSpinner() {
        val languages = arrayOf(
            getString(R.string.lang_japanese),
            getString(R.string.lang_korean),
            getString(R.string.lang_english)
        )
        val adapter = ArrayAdapter(this, R.layout.spinner_item, languages)
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
        binding.spinnerLang.adapter = adapter

        val savedLang = prefs.getInt(PREF_KEY_LANG, 0)
        binding.spinnerLang.setSelection(savedLang)
    }

    private fun setupBgStyleSelector() {
        val savedStyle = prefs.getInt(PREF_KEY_BG_STYLE, OverlayView.STYLE_TRANSPARENT)
        if (savedStyle == OverlayView.STYLE_SEAMLESS_WHITE) {
            binding.rbSeamlessWhite.isChecked = true
        } else {
            binding.rbTransparent.isChecked = true
        }

        binding.rgBgStyle.setOnCheckedChangeListener { _, checkedId ->
            val style = if (checkedId == R.id.rbSeamlessWhite) {
                OverlayView.STYLE_SEAMLESS_WHITE
            } else {
                OverlayView.STYLE_TRANSPARENT
            }
            prefs.edit().putInt(PREF_KEY_BG_STYLE, style).apply()
        }
    }

    private fun setupButtons() {
        binding.btnGrantOverlay.setOnClickListener {
            openOverlaySettings()
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

            // Save selected language
            val selectedLang = binding.spinnerLang.selectedItemPosition
            prefs.edit().putInt(PREF_KEY_LANG, selectedLang).apply()

            // Pre-download translator models in background so they are ready
            preDownloadLanguageModel(selectedLang)

            // Request Screen Capture
            screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent())
        }

        binding.btnStopFloating.setOnClickListener {
            stopFloatingService()
        }
    }

    private fun preDownloadLanguageModel(langIndex: Int) {
        val languagesToPreload = listOf(
            TranslateLanguage.ENGLISH, // Always ensure English is ready (most popular for web scans)
            when (langIndex) {
                0 -> TranslateLanguage.JAPANESE
                1 -> TranslateLanguage.KOREAN
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

    private fun checkOverlayPermissionPrompt() {
        if (!hasOverlayPermission()) {
            Toast.makeText(
                this,
                "Mohon berikan izin 'Tampilkan di atas aplikasi lain' untuk menggunakan tombol melayang.",
                Toast.LENGTH_LONG
            ).show()
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
        val selectedLang = binding.spinnerLang.selectedItemPosition
        val bgStyle = if (binding.rbSeamlessWhite.isChecked) {
            OverlayView.STYLE_SEAMLESS_WHITE
        } else {
            OverlayView.STYLE_TRANSPARENT
        }

        val serviceIntent = Intent(this, FloatingService::class.java).apply {
            putExtra(FloatingService.EXTRA_RESULT_CODE, resultCode)
            putExtra(FloatingService.EXTRA_RESULT_DATA, resultData)
            putExtra(FloatingService.EXTRA_LANG_INDEX, selectedLang)
            putExtra(FloatingService.EXTRA_BG_STYLE, bgStyle)
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

        // Move to background so user can go read comics immediately
        moveTaskToBack(true)
    }

    private fun stopFloatingService() {
        val stopIntent = Intent(this, FloatingService::class.java).apply {
            action = FloatingService.ACTION_STOP
        }
        startService(stopIntent)

        Toast.makeText(this, "Layanan tombol melayang telah dihentikan.", Toast.LENGTH_SHORT).show()
        updateUIState()
    }

    private fun updateUIState() {
        val overlayGranted = hasOverlayPermission()
        if (overlayGranted) {
            binding.tvOverlayStatus.text = getString(R.string.overlay_granted)
            binding.tvOverlayStatus.setTextColor(Color.parseColor("#4CAF50"))
            binding.btnGrantOverlay.visibility = View.GONE
        } else {
            binding.tvOverlayStatus.text = "Izin Overlay Belum Aktif ⚠"
            binding.tvOverlayStatus.setTextColor(Color.parseColor("#FF5252"))
            binding.btnGrantOverlay.visibility = View.VISIBLE
        }

        val serviceRunning = FloatingService.isRunning
        if (serviceRunning) {
            binding.tvServiceStatus.text = getString(R.string.status_service_running)
            binding.tvServiceStatus.setTextColor(Color.parseColor("#4CAF50"))
            binding.btnStartFloating.text = "Layanan Sedang Berjalan (Ketuk untuk Refresh)"
            binding.btnStopFloating.visibility = View.VISIBLE
        } else {
            binding.tvServiceStatus.text = getString(R.string.status_service_stopped)
            binding.tvServiceStatus.setTextColor(Color.parseColor("#FF7043"))
            binding.btnStartFloating.text = getString(R.string.btn_start_service)
            binding.btnStopFloating.visibility = View.GONE
        }
    }
}
