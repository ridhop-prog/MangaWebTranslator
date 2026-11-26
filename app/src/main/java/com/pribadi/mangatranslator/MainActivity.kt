package com.pribadi.mangatranslator

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.TextView
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

    companion object {
        private const val URL_GEMINI_API_KEY = "https://aistudio.google.com/apikey"
        private const val URL_GROQ_API_KEY = "https://console.groq.com/keys"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var projectionManager: MediaProjectionManager
    private lateinit var keyTester: ComicTranslator

    private var selectedLangIndex = 0 // 0: JA, 1: KO, 2: EN, 3: ZH
    private var selectedBgStyle = OverlayView.STYLE_SEAMLESS_WHITE
    private var selectedTextScale = 1.0f
    private var selectedComicStyle = TranslatorPrefs.STYLE_NATURAL
    private var selectedAiProvider = TranslatorPrefs.PROVIDER_AUTO
    private var geminiKeyVisible = false
    private var groqKeyVisible = false
    private var isUpdatingKeyFields = false

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
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        keyTester = ComicTranslator(this)

        loadSavedSettings()
        setupLanguageChips()
        setupAiAndStyleControls()
        setupBgStyleCards()
        setupTextScalePills()
        setupButtons()
        requestNotificationPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        loadSavedSettings()
        updateLanguageChipUI()
        updateComicStyleUI()
        updateProviderChipUI()
        updateEngineSummaryBadge()
        updateBgStyleUI()
        updateTextScaleUI()
        updateUIState()
    }

    override fun onDestroy() {
        keyTester.shutdown()
        super.onDestroy()
    }

    private fun loadSavedSettings() {
        selectedLangIndex = TranslatorPrefs.getLangIndex(this)
        selectedBgStyle = TranslatorPrefs.getBgStyle(this)
        selectedTextScale = TranslatorPrefs.getTextScale(this)
        selectedComicStyle = TranslatorPrefs.getComicStyle(this)
        selectedAiProvider = TranslatorPrefs.getAiProvider(this)

        isUpdatingKeyFields = true
        val savedGem = TranslatorPrefs.getGeminiApiKey(this)
        if (binding.etGeminiKey.text?.toString() != savedGem) {
            binding.etGeminiKey.setText(savedGem)
        }
        val savedGroq = TranslatorPrefs.getGroqApiKey(this)
        if (binding.etGroqKey.text?.toString() != savedGroq) {
            binding.etGroqKey.setText(savedGroq)
        }
        binding.switchGeminiVision.isChecked = TranslatorPrefs.isGeminiVisionEnabled(this)
        isUpdatingKeyFields = false
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
        TranslatorPrefs.setLangIndex(this, index)
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

    private fun setupAiAndStyleControls() {
        binding.cardStyleNatural.setOnClickListener {
            selectedComicStyle = TranslatorPrefs.STYLE_NATURAL
            TranslatorPrefs.setComicStyle(this, selectedComicStyle)
            updateComicStyleUI()
        }
        binding.cardStyleCasual.setOnClickListener {
            selectedComicStyle = TranslatorPrefs.STYLE_CASUAL
            TranslatorPrefs.setComicStyle(this, selectedComicStyle)
            updateComicStyleUI()
        }

        binding.chipProviderAuto.setOnClickListener { selectAiProvider(TranslatorPrefs.PROVIDER_AUTO) }
        binding.chipProviderGemini.setOnClickListener { selectAiProvider(TranslatorPrefs.PROVIDER_GEMINI) }
        binding.chipProviderGroq.setOnClickListener { selectAiProvider(TranslatorPrefs.PROVIDER_GROQ) }
        binding.chipProviderNoKey.setOnClickListener { selectAiProvider(TranslatorPrefs.PROVIDER_NO_KEY) }

        binding.switchGeminiVision.setOnCheckedChangeListener { _, isChecked ->
            if (!isUpdatingKeyFields) {
                TranslatorPrefs.setGeminiVisionEnabled(this, isChecked)
                updateEngineSummaryBadge()
            }
        }

        binding.btnGetGeminiKey.setOnClickListener { openExternalUrl(URL_GEMINI_API_KEY) }
        binding.btnGetGroqKey.setOnClickListener { openExternalUrl(URL_GROQ_API_KEY) }

        binding.btnToggleGeminiVis.setOnClickListener {
            geminiKeyVisible = !geminiKeyVisible
            applyPasswordVisibility(binding.etGeminiKey, binding.btnToggleGeminiVis, geminiKeyVisible)
        }
        binding.btnToggleGroqVis.setOnClickListener {
            groqKeyVisible = !groqKeyVisible
            applyPasswordVisibility(binding.etGroqKey, binding.btnToggleGroqVis, groqKeyVisible)
        }

        binding.etGeminiKey.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (isUpdatingKeyFields) return
                TranslatorPrefs.setGeminiApiKey(this@MainActivity, s?.toString().orEmpty())
                binding.tvGeminiStatus.visibility = View.GONE
                updateEngineSummaryBadge()
            }
        })

        binding.etGroqKey.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (isUpdatingKeyFields) return
                TranslatorPrefs.setGroqApiKey(this@MainActivity, s?.toString().orEmpty())
                binding.tvGroqStatus.visibility = View.GONE
                updateEngineSummaryBadge()
            }
        })

        binding.btnTestGemini.setOnClickListener {
            val key = binding.etGeminiKey.text?.toString()?.trim().orEmpty()
            TranslatorPrefs.setGeminiApiKey(this, key)
            updateEngineSummaryBadge()
            binding.btnTestGemini.isEnabled = false
            binding.btnTestGemini.text = "..."
            binding.tvGeminiStatus.visibility = View.VISIBLE
            binding.tvGeminiStatus.setTextColor(Color.parseColor("#FFD54F"))
            binding.tvGeminiStatus.text = "Menguji koneksi Gemini..."

            keyTester.testApiKey(isGemini = true, apiKey = key) { ok, msg ->
                binding.btnTestGemini.isEnabled = true
                binding.btnTestGemini.text = "Tes"
                binding.tvGeminiStatus.visibility = View.VISIBLE
                binding.tvGeminiStatus.setTextColor(
                    Color.parseColor(if (ok) "#00E676" else "#FF8A80")
                )
                binding.tvGeminiStatus.text = msg
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnTestGroq.setOnClickListener {
            val key = binding.etGroqKey.text?.toString()?.trim().orEmpty()
            TranslatorPrefs.setGroqApiKey(this, key)
            updateEngineSummaryBadge()
            binding.btnTestGroq.isEnabled = false
            binding.btnTestGroq.text = "..."
            binding.tvGroqStatus.visibility = View.VISIBLE
            binding.tvGroqStatus.setTextColor(Color.parseColor("#FFD54F"))
            binding.tvGroqStatus.text = "Menguji koneksi Groq..."

            keyTester.testApiKey(isGemini = false, apiKey = key) { ok, msg ->
                binding.btnTestGroq.isEnabled = true
                binding.btnTestGroq.text = "Tes"
                binding.tvGroqStatus.visibility = View.VISIBLE
                binding.tvGroqStatus.setTextColor(
                    Color.parseColor(if (ok) "#00E676" else "#FF8A80")
                )
                binding.tvGroqStatus.text = msg
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
        }

        updateComicStyleUI()
        updateProviderChipUI()
        updateEngineSummaryBadge()
    }

    private fun selectAiProvider(provider: Int) {
        selectedAiProvider = provider
        TranslatorPrefs.setAiProvider(this, provider)
        updateProviderChipUI()
        updateEngineSummaryBadge()
    }

    private fun updateComicStyleUI() {
        val isNatural = selectedComicStyle == TranslatorPrefs.STYLE_NATURAL
        binding.cardStyleNatural.setBackgroundResource(
            if (isNatural) R.drawable.bg_chip_selected else R.drawable.bg_chip_unselected
        )
        binding.cardStyleCasual.setBackgroundResource(
            if (!isNatural) R.drawable.bg_chip_selected else R.drawable.bg_chip_unselected
        )
        binding.tvStyleNaturalTitle.setTextColor(if (isNatural) Color.WHITE else Color.parseColor("#CFD8DC"))
        binding.tvStyleCasualTitle.setTextColor(if (!isNatural) Color.WHITE else Color.parseColor("#CFD8DC"))
    }

    private fun updateProviderChipUI() {
        val pills = listOf(
            TranslatorPrefs.PROVIDER_AUTO to binding.chipProviderAuto,
            TranslatorPrefs.PROVIDER_GEMINI to binding.chipProviderGemini,
            TranslatorPrefs.PROVIDER_GROQ to binding.chipProviderGroq,
            TranslatorPrefs.PROVIDER_NO_KEY to binding.chipProviderNoKey
        )
        for ((id, view) in pills) {
            val active = id == selectedAiProvider
            view.setBackgroundResource(
                if (active) R.drawable.bg_chip_selected else R.drawable.bg_chip_unselected
            )
            view.setTextColor(if (active) Color.WHITE else Color.parseColor("#CFD8DC"))
        }
    }

    private fun updateEngineSummaryBadge() {
        val hasGemini = TranslatorPrefs.getGeminiApiKey(this).isNotEmpty()
        val hasGroq = TranslatorPrefs.getGroqApiKey(this).isNotEmpty()
        val vision = TranslatorPrefs.isGeminiVisionEnabled(this)

        val (label, colorHex) = when (selectedAiProvider) {
            TranslatorPrefs.PROVIDER_NO_KEY -> "Multi-Engine" to "#FFD54F"
            TranslatorPrefs.PROVIDER_GEMINI -> if (hasGemini) {
                (if (vision) "Gemini Vision ✓" else "Gemini AI ✓") to "#00E676"
            } else {
                "Isi Key Gemini" to "#FF8A80"
            }
            TranslatorPrefs.PROVIDER_GROQ -> if (hasGroq) {
                "Groq AI ✓" to "#00E676"
            } else {
                "Isi Key Groq" to "#FF8A80"
            }
            else -> when {
                hasGemini -> (if (vision) "Gemini Vision ✓" else "Gemini AI ✓") to "#00E676"
                hasGroq -> "Groq AI ✓" to "#00E676"
                else -> "Multi-Engine" to "#80CBC4"
            }
        }
        binding.tvEngineSummary.text = label
        binding.tvEngineSummary.setTextColor(Color.parseColor(colorHex))
    }

    private fun applyPasswordVisibility(editText: EditText, toggleBtn: TextView, visible: Boolean) {
        val sel = editText.selectionEnd
        editText.inputType = if (visible) {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        } else {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        if (sel >= 0) editText.setSelection(sel.coerceAtMost(editText.text?.length ?: 0))
        toggleBtn.text = if (visible) "Tutup" else "Lihat"
    }

    private fun openExternalUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            Toast.makeText(this, "Tidak dapat membuka browser.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupBgStyleCards() {
        binding.cardBgSolid.setOnClickListener { selectBgStyle(OverlayView.STYLE_SEAMLESS_WHITE) }
        binding.cardBgTransparent.setOnClickListener { selectBgStyle(OverlayView.STYLE_TRANSPARENT) }
        updateBgStyleUI()
    }

    private fun selectBgStyle(style: Int) {
        selectedBgStyle = style
        TranslatorPrefs.setBgStyle(this, style)
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
        TranslatorPrefs.setTextScale(this, scale)
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
