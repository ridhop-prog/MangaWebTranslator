package com.pribadi.mangatranslator

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Html
import android.util.Base64
import android.util.LruCache
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.text.Text
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Hybrid AI Comic Translator + Multi-Engine Anti-Limit:
 *
 * AI Comic Engines (when API Key is configured):
 *  - Google Gemini 2.5 Flash / 2.0 Flash (supports Gemini Vision to inspect comic panels & fix OCR typos)
 *  - Groq AI (Llama 3.3 70B Versatile / Llama 3.1 8B Instant)
 *
 * Zero-API-Key Multi-Engine Anti-Limit Cascade (Cross-Panel Batching + Endpoint Rotation):
 *  - Tier 1: Rotated Google Neural Endpoints (GTX, AT, Chrome-Ex, Clients5, Lingva Neural Mirrors)
 *  - Tier 2: MyMemory Neural MT Batch (chunked <= 390 UTF-8 bytes)
 *  - Tier 3: Pollinations Keyless AI Comic Translator
 *  - Tier 4: Pre-Warmed ML Kit On-Device Translator (100% offline fallback)
 */
class ComicTranslator(private val context: Context) {

    companion object {
        private const val NEURAL_FAST_CONNECT_MS = 2200
        private const val NEURAL_FAST_READ_MS = 3200
        private const val MYMEMORY_CONNECT_MS = 3500
        private const val MYMEMORY_READ_MS = 5000
        private const val AI_CONNECT_TIMEOUT_MS = 5500
        private const val AI_READ_TIMEOUT_MS = 18000
        private const val KEYLESS_AI_READ_TIMEOUT_MS = 8000
        private const val RATE_LIMIT_COOLDOWN_MS = 90_000L
        private const val AUTH_ERROR_COOLDOWN_MS = 300_000L
        private const val MYMEMORY_MAX_UTF8_BYTES = 390
        private const val NEURAL_ENDPOINT_COUNT = 5

        private val GEMINI_MODELS = listOf("gemini-2.5-flash", "gemini-2.0-flash")
        private val GROQ_MODELS = listOf("llama-3.3-70b-versatile", "llama-3.1-8b-instant")
        private val LINGVA_HOSTS = listOf("lingva.ml", "lingva.garudalinux.org", "lingva.lunar.icu")

        private val MULTI_SPACE_REGEX = Regex("\\s+")
        private val CJK_SPACE_REGEX =
            Regex("([\\u3040-\\u30FF\\u4E00-\\u9FFF\\u3000-\\u303F])\\s+([\\u3040-\\u30FF\\u4E00-\\u9FFF\\u3000-\\u303F])")
        private val PIPE_AS_I_REGEX = Regex("(?<=^|\\s)\\|(?=\\s|'|\u2019|$)")
        private val PIPE_OR_L_APOS_REGEX = Regex("\\b[|l]'([mMdDsSvVrRlL])")
        private val ZERO_IN_WORD_REGEX = Regex("(?<=[A-Za-z])0(?=[A-Za-z])")
        private val SPACE_BEFORE_PUNCT_REGEX = Regex("\\s+([!?.,:;])")
        private val STANDALONE_I_REGEX = Regex("\\bi\\b")
        private val I_CONTRACTION_REGEX = Regex("\\bi'([mdsvrl])", RegexOption.IGNORE_CASE)
        private val NUMBERED_PREFIX_REGEX = Regex("^\\s*(?:\\[?\\d+\\]?[.):\\-]\\s*)")

        private val KOREAN_LAUGH_REGEX = Regex("^[ㅋㅎ]{2,}[!?.~]*$")
        private val KOREAN_CRY_REGEX = Regex("^[ㅠㅜ]{2,}[!?.~]*$")

        private val SHORT_IDIOM_MAP = mapOf(
            "え?" to "Eh?",
            "えっ" to "Eh?!",
            "えっ?" to "Eh?!",
            "え?!" to "Eh?!",
            "え!?" to "Eh?!",
            "なっ" to "Apa?!",
            "なに!?" to "Apa?!",
            "何!?" to "Apa?!",
            "何?!" to "Apa?!",
            "まさか" to "Jangan-jangan...",
            "まさか!" to "Tidak mungkin!",
            "くそっ" to "Sial!",
            "くそ!" to "Sial!",
            "クソッ" to "Sial!",
            "やめて" to "Hentikan!",
            "やめて!" to "Hentikan!",
            "待て!" to "Tunggu!",
            "待って!" to "Tunggu!",
            "本当?" to "Benarkah?",
            "本当か?" to "Benarkah?",
            "嘘だろ" to "Bohong, kan?!",
            "なんだと!?" to "Apa kau bilang?!",
            "뭐?" to "Apa?",
            "뭐?!" to "Apa?!",
            "뭐야?" to "Apa-apaan ini?",
            "뭐야?!" to "Apa-apaan ini?!",
            "설마" to "Jangan-jangan...",
            "젠장" to "Sial!",
            "잠깐!" to "Tunggu!",
            "안돼!" to "Tidak boleh!",
            "말도 안돼!" to "Tidak mungkin!"
        )
    }

    data class TranslationRequest(
        val text: String,
        val sourceLangCode: String, // "ja", "ko", "zh-CN", or "en"
        val normalizedBox: IntArray? = null // [ymin, xmin, ymax, xmax] in 0..1000 for Gemini Vision
    )

    data class PanelBatchInput(
        val bubbles: List<TranslationRequest>,
        val panelJpegBytes: ByteArray? = null
    )

    private data class FlatBubbleItem(
        val panelIdx: Int,
        val bubbleIdx: Int,
        val id: Int,
        val cleanText: String,
        val sourceLangCode: String,
        val normalizedBox: IntArray?
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newFixedThreadPool(6)
    private val cache = LruCache<String, String>(1000)
    private val mlKitClients = ConcurrentHashMap<String, Translator>()
    private val mlKitModelReady = ConcurrentHashMap<String, Boolean>()

    // Per-endpoint cooldowns for rotated neural MT endpoints (prevents rapid 429 loops)
    private val neuralCooldowns = LongArray(NEURAL_ENDPOINT_COUNT)
    private val neuralRoundRobin = AtomicInteger(0)
    private val lingvaHostCounter = AtomicInteger(0)

    @Volatile
    private var myMemoryCooldownUntilMs: Long = 0L

    @Volatile
    private var keylessAiCooldownUntilMs: Long = 0L

    @Volatile
    private var geminiCooldownUntilMs: Long = 0L

    @Volatile
    private var groqCooldownUntilMs: Long = 0L

    @Volatile
    var lastUsedEngineLabel: String = "Multi-Engine"
        private set

    fun getConfiguredEngineLabel(): String {
        val provider = TranslatorPrefs.getAiProvider(context)
        val hasGemini = TranslatorPrefs.getGeminiApiKey(context).isNotEmpty()
        val hasGroq = TranslatorPrefs.getGroqApiKey(context).isNotEmpty()
        val vision = TranslatorPrefs.isGeminiVisionEnabled(context)

        return when (provider) {
            TranslatorPrefs.PROVIDER_NO_KEY -> "Multi-Engine"
            TranslatorPrefs.PROVIDER_GEMINI -> if (hasGemini) {
                if (vision) "Gemini Vision" else "Gemini AI"
            } else "Multi-Engine"
            TranslatorPrefs.PROVIDER_GROQ -> if (hasGroq) "Groq AI" else "Multi-Engine"
            else -> when {
                hasGemini -> if (vision) "Gemini Vision" else "Gemini AI"
                hasGroq -> "Groq AI"
                else -> "Multi-Engine"
            }
        }
    }

    fun isAiActive(): Boolean {
        val provider = TranslatorPrefs.getAiProvider(context)
        if (provider == TranslatorPrefs.PROVIDER_NO_KEY) return false
        val hasGemini = TranslatorPrefs.getGeminiApiKey(context).isNotEmpty()
        val hasGroq = TranslatorPrefs.getGroqApiKey(context).isNotEmpty()
        return when (provider) {
            TranslatorPrefs.PROVIDER_GEMINI -> hasGemini
            TranslatorPrefs.PROVIDER_GROQ -> hasGroq
            else -> hasGemini || hasGroq
        }
    }

    fun isGeminiVisionReady(): Boolean {
        val provider = TranslatorPrefs.getAiProvider(context)
        if (provider == TranslatorPrefs.PROVIDER_NO_KEY || provider == TranslatorPrefs.PROVIDER_GROQ) {
            return false
        }
        return TranslatorPrefs.getGeminiApiKey(context).isNotEmpty() &&
            TranslatorPrefs.isGeminiVisionEnabled(context)
    }

    fun preWarm(userLangIndex: Int) {
        val langs = listOf(
            TranslateLanguage.ENGLISH,
            when (userLangIndex) {
                0 -> TranslateLanguage.JAPANESE
                1 -> TranslateLanguage.KOREAN
                3 -> TranslateLanguage.CHINESE
                else -> TranslateLanguage.ENGLISH
            }
        ).distinct()

        executor.execute {
            for (lang in langs) {
                if (mlKitModelReady[lang] == true) continue
                try {
                    val client = getOrCreateMlKitClient(lang)
                    val conditions = DownloadConditions.Builder().build()
                    Tasks.await(client.downloadModelIfNeeded(conditions), 15, TimeUnit.SECONDS)
                    mlKitModelReady[lang] = true
                } catch (_: Exception) {
                }
            }
        }
    }

    fun extractCleanBlockText(block: Text.TextBlock): String {
        val lines = block.lines
        if (lines.isEmpty()) return normalizeComicText(block.text)

        val rawCombined = lines.joinToString("") { it.text }
        val isCjk = containsJapaneseOrChinese(rawCombined) && !hasDominantLatin(rawCombined)
        val isVertical = isCjk && lines.count { line ->
            val b = line.boundingBox
            b != null && b.height() > b.width() * 1.35f
        } > lines.size / 2

        val sortedLines = if (isVertical) {
            lines.sortedWith(
                compareByDescending<Text.Line> { it.boundingBox?.centerX() ?: 0 }
                    .thenBy { it.boundingBox?.centerY() ?: 0 }
            )
        } else {
            lines.sortedWith(
                compareBy<Text.Line> { it.boundingBox?.centerY() ?: 0 }
                    .thenBy { it.boundingBox?.centerX() ?: 0 }
            )
        }

        val joined = joinLines(sortedLines.map { it.text.trim() }, isCjk)
        return normalizeComicText(joined)
    }

    fun joinBubbleParts(parts: List<String>): String {
        if (parts.isEmpty()) return ""
        if (parts.size == 1) return normalizeComicText(parts[0])
        val combined = parts.joinToString("")
        val isCjk = containsJapaneseOrChinese(combined) && !hasDominantLatin(combined)
        return normalizeComicText(joinLines(parts, isCjk))
    }

    private fun joinLines(parts: List<String>, isCjk: Boolean): String {
        val sb = StringBuilder()
        for (raw in parts) {
            val part = raw.trim()
            if (part.isEmpty()) continue
            if (sb.isEmpty()) {
                sb.append(part)
            } else if (isCjk) {
                sb.append(part)
            } else if (sb.length >= 2 && sb[sb.length - 1] == '-' && sb[sb.length - 2].isLetter() && part[0].isLetter()) {
                sb.deleteCharAt(sb.length - 1)
                sb.append(part)
            } else {
                sb.append(' ').append(part)
            }
        }
        return sb.toString()
    }

    fun normalizeComicText(raw: String): String {
        var text = raw.replace("\r", " ").replace("\n", " ").trim()
        if (text.isEmpty()) return ""

        text = text.replace('！', '!')
            .replace('？', '?')
            .replace('…', '.')
            .replace("..", "...")
            .replace("....", "...")

        if (containsJapaneseOrChinese(text)) {
            var prev: String
            do {
                prev = text
                text = CJK_SPACE_REGEX.replace(text, "$1$2")
            } while (text != prev)
        }

        text = MULTI_SPACE_REGEX.replace(text, " ").trim()

        if (hasDominantLatin(text)) {
            text = PIPE_AS_I_REGEX.replace(text, "I")
            text = PIPE_OR_L_APOS_REGEX.replace(text, "I'$1")
            text = ZERO_IN_WORD_REGEX.replace(text, "O")
            text = SPACE_BEFORE_PUNCT_REGEX.replace(text, "$1")

            val letters = text.filter { it.isLetter() }
            if (letters.length >= 4) {
                val upperCount = letters.count { it.isUpperCase() }
                if (upperCount.toFloat() / letters.length.toFloat() > 0.78f) {
                    text = toNaturalSentenceCase(text)
                }
            }
        }

        return text.trim()
    }

    private fun toNaturalSentenceCase(allCaps: String): String {
        val lower = allCaps.lowercase(Locale.ENGLISH)
        val sb = StringBuilder(lower.length)
        var capitalizeNext = true

        for (c in lower) {
            if (capitalizeNext && c.isLetter()) {
                sb.append(c.uppercaseChar())
                capitalizeNext = false
            } else {
                sb.append(c)
                if (c == '.' || c == '!' || c == '?') {
                    capitalizeNext = true
                }
            }
        }

        var result = sb.toString()
        result = STANDALONE_I_REGEX.replace(result, "I")
        result = I_CONTRACTION_REGEX.replace(result) { match ->
            "I'" + match.groupValues[1].lowercase(Locale.ENGLISH)
        }
        return result
    }

    fun detectBubbleSourceLang(text: String, userLangIndex: Int): String {
        var hiraganaKatakana = 0
        var hanziKanji = 0
        var hangul = 0
        var latin = 0

        for (c in text) {
            when {
                c in '\u3040'..'\u30FF' -> hiraganaKatakana++
                c in '\uAC00'..'\uD7AF' || c in '\u1100'..'\u11FF' || c in '\u3130'..'\u318F' -> hangul++
                c in '\u4E00'..'\u9FFF' -> hanziKanji++
                c in 'a'..'z' || c in 'A'..'Z' -> latin++
            }
        }

        return when {
            hiraganaKatakana > 0 -> "ja"
            hangul > 0 -> "ko"
            hanziKanji > 0 -> if (userLangIndex == 0) "ja" else "zh-CN"
            latin > 0 -> "en"
            else -> when (userLangIndex) {
                0 -> "ja"
                1 -> "ko"
                3 -> "zh-CN"
                else -> "en"
            }
        }
    }

    /**
     * Async single-panel translation entry point (used by FloatingService).
     * Optionally accepts [panelJpegBytes] for Gemini Vision OCR verification.
     */
    fun translatePanelBatch(
        requests: List<TranslationRequest>,
        panelJpegBytes: ByteArray? = null,
        onComplete: (List<String?>) -> Unit
    ) {
        if (requests.isEmpty()) {
            mainHandler.post { onComplete(emptyList()) }
            return
        }

        executor.execute {
            val result = translateMultiPanelBatchSync(
                listOf(PanelBatchInput(requests, panelJpegBytes))
            ).firstOrNull() ?: List(requests.size) { null }
            mainHandler.post { onComplete(result) }
        }
    }

    /**
     * Synchronous cross-panel batch translation (used by WebComicActivity & translatePanelBatch).
     * Combines bubbles across multiple consecutive panels into a single AI or Multi-Engine request
     * so story context is preserved and API rate-limits are avoided.
     */
    fun translateMultiPanelBatchSync(panels: List<PanelBatchInput>): List<List<String?>> {
        if (panels.isEmpty()) return emptyList()

        val comicStyle = TranslatorPrefs.getComicStyle(context)
        val provider = TranslatorPrefs.getAiProvider(context)
        val geminiKey = TranslatorPrefs.getGeminiApiKey(context)
        val groqKey = TranslatorPrefs.getGroqApiKey(context)
        val visionEnabled = TranslatorPrefs.isGeminiVisionEnabled(context)

        val useGemini = provider != TranslatorPrefs.PROVIDER_NO_KEY &&
            provider != TranslatorPrefs.PROVIDER_GROQ &&
            geminiKey.isNotEmpty()
        val useGroq = provider != TranslatorPrefs.PROVIDER_NO_KEY &&
            provider != TranslatorPrefs.PROVIDER_GEMINI &&
            groqKey.isNotEmpty()
        val aiRequested = useGemini || useGroq

        val outMatrix = panels.map { arrayOfNulls<String>(it.bubbles.size) }
        val uncachedItems = ArrayList<FlatBubbleItem>()
        var nextGlobalId = 1

        for (pIdx in panels.indices) {
            val panel = panels[pIdx]
            val hasVisionForPanel = useGemini && visionEnabled && panel.panelJpegBytes != null
            for (bIdx in panel.bubbles.indices) {
                val req = panel.bubbles[bIdx]
                val clean = req.text.replace("\n", " ").trim()
                if (clean.isEmpty()) continue

                // For pure text or obvious Korean laughs/cries, check fast idiom shortcuts
                val idiom = lookupComicIdiom(clean, hasVisionForPanel)
                if (idiom != null) {
                    outMatrix[pIdx][bIdx] = idiom
                    continue
                }

                val lowerNorm = clean.lowercase(Locale.ROOT)
                val aiCacheKey = "ai:$comicStyle:${req.sourceLangCode}:$lowerNorm"
                val mtCacheKey = "mt:$comicStyle:${req.sourceLangCode}:$lowerNorm"

                val cachedAi = cache.get(aiCacheKey)
                if (cachedAi != null) {
                    outMatrix[pIdx][bIdx] = cachedAi
                    continue
                }
                if (!aiRequested) {
                    val cachedMt = cache.get(mtCacheKey)
                    if (cachedMt != null) {
                        outMatrix[pIdx][bIdx] = cachedMt
                        continue
                    }
                }

                uncachedItems.add(
                    FlatBubbleItem(
                        panelIdx = pIdx,
                        bubbleIdx = bIdx,
                        id = nextGlobalId++,
                        cleanText = clean,
                        sourceLangCode = req.sourceLangCode,
                        normalizedBox = req.normalizedBox
                    )
                )
            }
        }

        if (uncachedItems.isEmpty()) {
            return outMatrix.map { it.toList() }
        }

        val online = isNetworkAvailable()
        val translatedMap = HashMap<Int, String>(uncachedItems.size)
        var translatedByAi = false

        if (online) {
            val now = SystemClock.uptimeMillis()

            // 1. Primary AI Mode: Google Gemini Flash (with optional Gemini Vision)
            if (useGemini && now >= geminiCooldownUntilMs) {
                val gemResult = translateViaGeminiBatch(
                    items = uncachedItems,
                    panels = panels,
                    apiKey = geminiKey,
                    comicStyle = comicStyle,
                    useVision = visionEnabled
                )
                if (gemResult != null && gemResult.isNotEmpty()) {
                    translatedMap.putAll(gemResult)
                    translatedByAi = true
                    val hasImages = visionEnabled && panels.any { it.panelJpegBytes != null }
                    lastUsedEngineLabel = if (hasImages) "Gemini Vision" else "Gemini AI"
                }
            }

            // 2. Groq AI (Llama 3.3 70B) if primary is Groq OR Gemini unavailable/rate-limited
            val remainingForGroq = uncachedItems.filter { !translatedMap.containsKey(it.id) }
            if (remainingForGroq.isNotEmpty() && useGroq && SystemClock.uptimeMillis() >= groqCooldownUntilMs) {
                val groqResult = translateViaGroqBatch(
                    items = remainingForGroq,
                    apiKey = groqKey,
                    comicStyle = comicStyle
                )
                if (groqResult != null && groqResult.isNotEmpty()) {
                    translatedMap.putAll(groqResult)
                    translatedByAi = true
                    lastUsedEngineLabel = "Groq AI"
                }
            }

            // 3. Multi-Engine Anti-Limit (Rotated Google Neural Endpoints + MyMemory + Pollinations)
            val remainingForMt = uncachedItems.filter { !translatedMap.containsKey(it.id) }
            if (remainingForMt.isNotEmpty()) {
                val mtResult = translateViaAntiLimitCascade(remainingForMt, comicStyle)
                translatedMap.putAll(mtResult)
                if (!translatedByAi && mtResult.isNotEmpty()) {
                    lastUsedEngineLabel = "Multi-Engine"
                }
            }
        }

        // 4. Final Offline Safety Net: Pre-Warmed ML Kit On-Device Translator
        val remainingForMlKit = uncachedItems.filter { translatedMap[it.id].isNullOrBlank() }
        if (remainingForMlKit.isNotEmpty()) {
            val byLang = remainingForMlKit.groupBy { it.sourceLangCode }
            for ((langCode, group) in byLang) {
                val mlRes = translatePanelViaMlKitSync(group.map { it.cleanText }, langCode)
                for (i in group.indices) {
                    val t = mlRes.getOrNull(i)
                    if (!t.isNullOrBlank()) {
                        translatedMap[group[i].id] = t
                    }
                }
            }
            if (!translatedByAi && translatedMap.size == remainingForMlKit.size) {
                lastUsedEngineLabel = "ML Kit Offline"
            }
        }

        // Post-process & populate output matrix + cache
        for (item in uncachedItems) {
            val raw = translatedMap[item.id]
            if (!raw.isNullOrBlank()) {
                val polished = postProcessIndonesian(raw)
                if (polished.isNotEmpty()) {
                    outMatrix[item.panelIdx][item.bubbleIdx] = polished
                    val prefix = if (translatedByAi) "ai" else "mt"
                    cache.put(
                        "$prefix:$comicStyle:${item.sourceLangCode}:${item.cleanText.lowercase(Locale.ROOT)}",
                        polished
                    )
                }
            }
        }

        return outMatrix.map { it.toList() }
    }

    /**
     * Quick API Key validation for MainActivity "Tes" button.
     */
    fun testApiKey(
        isGemini: Boolean,
        apiKey: String,
        onResult: (Boolean, String) -> Unit
    ) {
        val trimmed = apiKey.trim()
        if (trimmed.isEmpty()) {
            mainHandler.post { onResult(false, "API Key masih kosong.") }
            return
        }
        if (!isNetworkAvailable()) {
            mainHandler.post { onResult(false, "Tidak ada koneksi internet.") }
            return
        }

        executor.execute {
            val result = if (isGemini) {
                testGeminiKeySync(trimmed)
            } else {
                testGroqKeySync(trimmed)
            }
            mainHandler.post { onResult(result.first, result.second) }
        }
    }

    private fun testGeminiKeySync(apiKey: String): Pair<Boolean, String> {
        for (model in GEMINI_MODELS) {
            var conn: HttpURLConnection? = null
            try {
                val url =
                    "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=${
                        URLEncoder.encode(apiKey, "UTF-8")
                    }"
                val genCfg = JSONObject().apply {
                    put("temperature", 0.1)
                    put("maxOutputTokens", 64)
                    if (model.startsWith("gemini-2.5")) {
                        put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
                    }
                }
                val payload = JSONObject().apply {
                    put(
                        "contents",
                        JSONArray().put(
                            JSONObject().put("role", "user").put(
                                "parts",
                                JSONArray().put(JSONObject().put("text", "Balas hanya kata: OK"))
                            )
                        )
                    )
                    put("generationConfig", genCfg)
                }

                conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = AI_CONNECT_TIMEOUT_MS
                    readTimeout = 10000
                    setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                }
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use {
                    it.write(payload.toString())
                    it.flush()
                }
                val code = conn.responseCode
                if (code == HttpURLConnection.HTTP_OK) {
                    geminiCooldownUntilMs = 0L
                    return Pair(true, "Gemini terhubung ($model) ✓")
                }
                val errBody = readStreamSafe(conn.errorStream)
                if (code == 400 || code == 401 || code == 403) {
                    if (errBody.contains("API_KEY_INVALID", ignoreCase = true) ||
                        errBody.contains("API key not valid", ignoreCase = true)
                    ) {
                        return Pair(false, "API Key Gemini tidak valid.")
                    }
                    return Pair(false, "Ditolak Gemini (HTTP $code).")
                }
                if (code == 429) {
                    continue
                }
            } catch (e: Exception) {
                return Pair(false, "Gagal terhubung: ${e.localizedMessage ?: "Timeout"}")
            } finally {
                conn?.disconnect()
            }
        }
        return Pair(false, "Gemini sedang limit/sibuk (429). Coba lagi sebentar.")
    }

    private fun testGroqKeySync(apiKey: String): Pair<Boolean, String> {
        for (model in GROQ_MODELS) {
            var conn: HttpURLConnection? = null
            try {
                val payload = JSONObject().apply {
                    put("model", model)
                    put("temperature", 0.1)
                    put("max_tokens", 32)
                    put(
                        "messages",
                        JSONArray().put(
                            JSONObject().put("role", "user").put("content", "Reply only: OK")
                        )
                    )
                }
                conn = (URL("https://api.groq.com/openai/v1/chat/completions").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = AI_CONNECT_TIMEOUT_MS
                    readTimeout = 10000
                    setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                    setRequestProperty("Authorization", "Bearer $apiKey")
                }
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use {
                    it.write(payload.toString())
                    it.flush()
                }
                val code = conn.responseCode
                if (code == HttpURLConnection.HTTP_OK) {
                    groqCooldownUntilMs = 0L
                    return Pair(true, "Groq terhubung ($model) ✓")
                }
                if (code == 401 || code == 403) {
                    return Pair(false, "API Key Groq tidak valid (HTTP $code).")
                }
                if (code == 429) {
                    continue
                }
                return Pair(false, "Groq error (HTTP $code).")
            } catch (e: Exception) {
                return Pair(false, "Gagal terhubung: ${e.localizedMessage ?: "Timeout"}")
            } finally {
                conn?.disconnect()
            }
        }
        return Pair(false, "Groq sedang limit (429). Coba lagi sebentar.")
    }

    // =========================================================================
    // ENGINE A: GOOGLE GEMINI FLASH (TEXT + VISION)
    // =========================================================================

    private fun translateViaGeminiBatch(
        items: List<FlatBubbleItem>,
        panels: List<PanelBatchInput>,
        apiKey: String,
        comicStyle: Int,
        useVision: Boolean
    ): Map<Int, String>? {
        if (items.isEmpty()) return emptyMap()

        val hasAnyImage = useVision && panels.any { p ->
            p.panelJpegBytes != null && items.any { it.panelIdx == panels.indexOf(p) }
        }
        val sysPrompt = buildComicAiSystemPrompt(comicStyle, hasVision = hasAnyImage)

        val partsArray = JSONArray()
        val activePanelIndices = items.map { it.panelIdx }.distinct().sorted()

        if (hasAnyImage) {
            for (pIdx in activePanelIndices) {
                val bytes = panels[pIdx].panelJpegBytes ?: continue
                partsArray.put(JSONObject().put("text", "Panel ${pIdx + 1}:"))
                partsArray.put(
                    JSONObject().put(
                        "inlineData",
                        JSONObject().apply {
                            put("mimeType", "image/jpeg")
                            put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
                        }
                    )
                )
            }
        }

        val itemsJson = JSONArray()
        for (item in items) {
            itemsJson.put(
                JSONObject().apply {
                    put("id", item.id)
                    put("panel", item.panelIdx + 1)
                    put("lang", item.sourceLangCode)
                    if (hasAnyImage && item.normalizedBox != null && item.normalizedBox.size == 4) {
                        put(
                            "box",
                            JSONArray().apply {
                                put(item.normalizedBox[0])
                                put(item.normalizedBox[1])
                                put(item.normalizedBox[2])
                                put(item.normalizedBox[3])
                            }
                        )
                    }
                    put("ocr", item.cleanText)
                }
            )
        }

        val userPrompt = buildString {
            if (hasAnyImage) {
                append("Periksa teks langsung di dalam gambar panel sesuai koordinat box [ymin, xmin, ymax, xmax] skala 0-1000 untuk memperbaiki kesalahan OCR (font miring/artistik), pahami konteks percakapan antar-panel, lalu terjemahkan ke Bahasa Indonesia.\n")
            } else {
                append("Terjemahkan seluruh dialog komik berikut secara berurutan dengan memahami konteks antar-panel.\n")
            }
            append("Balas HANYA JSON array: [{\"id\":1,\"t\":\"...\"}, ...]\n")
            append("Data:\n")
            append(itemsJson.toString())
        }
        partsArray.put(JSONObject().put("text", userPrompt))

        for (model in GEMINI_MODELS) {
            var conn: HttpURLConnection? = null
            try {
                val urlStr =
                    "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=${
                        URLEncoder.encode(apiKey, "UTF-8")
                    }"

                val genCfg = JSONObject().apply {
                    put("temperature", 0.25)
                    put("responseMimeType", "application/json")
                    put("maxOutputTokens", 2500)
                    if (model.startsWith("gemini-2.5")) {
                        put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
                    }
                }

                val payload = JSONObject().apply {
                    put(
                        "systemInstruction",
                        JSONObject().put("parts", JSONArray().put(JSONObject().put("text", sysPrompt)))
                    )
                    put(
                        "contents",
                        JSONArray().put(
                            JSONObject().apply {
                                put("role", "user")
                                put("parts", partsArray)
                            }
                        )
                    )
                    put("generationConfig", genCfg)
                }

                conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = AI_CONNECT_TIMEOUT_MS
                    readTimeout = AI_READ_TIMEOUT_MS
                    setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                }

                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                    writer.write(payload.toString())
                    writer.flush()
                }

                val code = conn.responseCode
                if (code == 401 || code == 403 || code == 400) {
                    val err = readStreamSafe(conn.errorStream)
                    if (err.contains("API_KEY_INVALID", ignoreCase = true) ||
                        err.contains("API key not valid", ignoreCase = true) ||
                        code == 401 || code == 403
                    ) {
                        geminiCooldownUntilMs = SystemClock.uptimeMillis() + AUTH_ERROR_COOLDOWN_MS
                        return null
                    }
                    // Try next model if 400 was model-specific
                    continue
                }
                if (code == 429 || code == 503 || code == 404) {
                    continue
                }
                if (code != HttpURLConnection.HTTP_OK) return null

                val body = readStreamSafe(conn.inputStream)
                if (body.isEmpty()) continue

                val root = JSONObject(body)
                val candidates = root.optJSONArray("candidates") ?: continue
                val firstCand = candidates.optJSONObject(0) ?: continue
                val content = firstCand.optJSONObject("content") ?: continue
                val respParts = content.optJSONArray("parts") ?: continue
                val sb = StringBuilder()
                for (i in 0 until respParts.length()) {
                    val txt = respParts.optJSONObject(i)?.optString("text", "").orEmpty()
                    if (txt.isNotEmpty()) sb.append(txt)
                }

                val parsed = parseAiJsonTranslations(sb.toString(), items)
                if (parsed != null && parsed.isNotEmpty()) {
                    return parsed
                }
            } catch (_: Exception) {
            } finally {
                conn?.disconnect()
            }
        }

        // All Gemini models failed or rate-limited; brief cooldown so fallback runs immediately
        geminiCooldownUntilMs = SystemClock.uptimeMillis() + 30_000L
        return null
    }

    // =========================================================================
    // ENGINE B: GROQ AI (LLAMA 3.3 70B VERSATILE)
    // =========================================================================

    private fun translateViaGroqBatch(
        items: List<FlatBubbleItem>,
        apiKey: String,
        comicStyle: Int
    ): Map<Int, String>? {
        if (items.isEmpty()) return emptyMap()

        val sysPrompt = buildComicAiSystemPrompt(comicStyle, hasVision = false) +
            "\nFormat output WAJIB JSON object: {\"translations\":[{\"id\":1,\"t\":\"...\"}]}"

        val itemsJson = JSONArray()
        for (item in items) {
            itemsJson.put(
                JSONObject().apply {
                    put("id", item.id)
                    put("panel", item.panelIdx + 1)
                    put("lang", item.sourceLangCode)
                    put("text", item.cleanText)
                }
            )
        }

        val userPrompt =
            "Terjemahkan dialog komik berikut ke Bahasa Indonesia dalam format JSON {\"translations\":[{\"id\":...,\"t\":...}]}:\n$itemsJson"

        for (model in GROQ_MODELS) {
            var conn: HttpURLConnection? = null
            try {
                val payload = JSONObject().apply {
                    put("model", model)
                    put("temperature", 0.25)
                    put("max_tokens", 2500)
                    put("response_format", JSONObject().put("type", "json_object"))
                    put(
                        "messages",
                        JSONArray().apply {
                            put(JSONObject().put("role", "system").put("content", sysPrompt))
                            put(JSONObject().put("role", "user").put("content", userPrompt))
                        }
                    )
                }

                conn = (URL("https://api.groq.com/openai/v1/chat/completions").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = AI_CONNECT_TIMEOUT_MS
                    readTimeout = AI_READ_TIMEOUT_MS
                    setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                    setRequestProperty("Authorization", "Bearer $apiKey")
                    setRequestProperty("User-Agent", "ScreenMangaTranslator/3.2")
                }

                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                    writer.write(payload.toString())
                    writer.flush()
                }

                val code = conn.responseCode
                if (code == 401 || code == 403) {
                    groqCooldownUntilMs = SystemClock.uptimeMillis() + AUTH_ERROR_COOLDOWN_MS
                    return null
                }
                if (code == 429 || code >= 500) {
                    continue
                }
                if (code != HttpURLConnection.HTTP_OK) return null

                val body = readStreamSafe(conn.inputStream)
                if (body.isEmpty()) continue

                val root = JSONObject(body)
                val msgText = root.optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content", "")
                    .orEmpty()

                val parsed = parseAiJsonTranslations(msgText, items)
                if (parsed != null && parsed.isNotEmpty()) {
                    return parsed
                }
            } catch (_: Exception) {
            } finally {
                conn?.disconnect()
            }
        }

        groqCooldownUntilMs = SystemClock.uptimeMillis() + 30_000L
        return null
    }

    private fun buildComicAiSystemPrompt(comicStyle: Int, hasVision: Boolean): String {
        val styleRules = if (comicStyle == TranslatorPrefs.STYLE_CASUAL) {
            """
            GAYA BAHASA: Santai / Gaul (cocok untuk manhwa sekolah, modern, slice of life, komedi).
            - Gunakan kata ganti santai seperti "gue / lu" (atau "aku / kamu" jika jelas situasi formal/romantis/keluarga/atasan).
            - Boleh pakai ungkapan luwes anak muda Indonesia ("santai aja", "anjir", "gila", "parah", "ngapain", "beneran", dll) secara natural sesuai ekspresi tokoh, jangan kaku seperti buku pelajaran.
            """.trimIndent()
        } else {
            """
            GAYA BAHASA: Komik Natural / Webtoon Resmi (rapi, dramatis, emosional, enak dibaca).
            - Gunakan kata ganti "aku / kamu / kau" (atau "Saya / Anda / Tuan / Nona" untuk bawahan ke atasan atau bangsawan).
            - Kalimat harus luwes, hidup, dan mengalir seperti lokalisasi Webtoon/Manga resmi, hindari terjemahan harfiah/robotik.
            """.trimIndent()
        }

        val visionRule = if (hasVision) {
            "- Kamu juga menerima gambar panel. Jika teks OCR terpotong/typo karena font miring/artistik, baca langsung dari balon teks pada gambar di koordinat box yang diberikan."
        } else {
            ""
        }

        return """
            Kamu adalah penerjemah komik (manga, manhwa, manhua) profesional ke Bahasa Indonesia.
            $styleRules
            ATURAN TERJEMAHAN:
            - Pahami konteks antar-balon dan antar-panel sehingga subjek (aku/kamu/dia) dan alur percakapan nyambung.
            - Pertahankan atau adaptasi istilah populer komik secara wajar: senpai, kouhai, hyung, noona, oppa, unnie, guild, dungeon, hunter, rank, quest, skill, mana, qi, kultivasi, sekte, pendekar.
            - Untuk efek suara (SFX) atau seruan pendek, terjemahkan singkat dan ekspresif.
            $visionRule
            - JANGAN menambah komentar atau penjelasan apa pun di luar JSON.
        """.trimIndent()
    }

    private fun parseAiJsonTranslations(
        rawText: String,
        items: List<FlatBubbleItem>
    ): Map<Int, String>? {
        var cleaned = rawText.trim()
        if (cleaned.isEmpty()) return null

        // Strip markdown ```json ... ``` fences if present
        if (cleaned.startsWith("```")) {
            val firstNewline = cleaned.indexOf('\n')
            if (firstNewline > 0) {
                cleaned = cleaned.substring(firstNewline + 1)
            }
            val lastFence = cleaned.lastIndexOf("```")
            if (lastFence >= 0) {
                cleaned = cleaned.substring(0, lastFence)
            }
            cleaned = cleaned.trim()
        }

        val result = HashMap<Int, String>(items.size)
        val validIds = items.map { it.id }.toSet()

        try {
            if (cleaned.startsWith("[")) {
                val arr = JSONArray(cleaned)
                extractArrayIntoMap(arr, items, validIds, result)
                return if (result.isNotEmpty()) result else null
            }

            if (cleaned.startsWith("{")) {
                val obj = JSONObject(cleaned)
                val arr = obj.optJSONArray("translations")
                    ?: obj.optJSONArray("items")
                    ?: obj.optJSONArray("results")
                    ?: obj.optJSONArray("data")
                if (arr != null) {
                    extractArrayIntoMap(arr, items, validIds, result)
                    if (result.isNotEmpty()) return result
                }
                // Handle {"1": "...", "2": "..."} map format
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val id = k.toIntOrNull()
                    if (id != null && validIds.contains(id)) {
                        val valStr = obj.optString(k, "").trim()
                        if (valStr.isNotEmpty()) result[id] = valStr
                    }
                }
                if (result.isNotEmpty()) return result
            }
        } catch (_: Exception) {
        }

        // Fallback: try extracting first [...] array substring
        val arrStart = cleaned.indexOf('[')
        val arrEnd = cleaned.lastIndexOf(']')
        if (arrStart >= 0 && arrEnd > arrStart) {
            try {
                val arr = JSONArray(cleaned.substring(arrStart, arrEnd + 1))
                extractArrayIntoMap(arr, items, validIds, result)
                if (result.isNotEmpty()) return result
            } catch (_: Exception) {
            }
        }

        return null
    }

    private fun extractArrayIntoMap(
        arr: JSONArray,
        items: List<FlatBubbleItem>,
        validIds: Set<Int>,
        out: MutableMap<Int, String>
    ) {
        for (i in 0 until arr.length()) {
            val elem = arr.opt(i)
            when (elem) {
                is JSONObject -> {
                    val id = elem.optInt("id", -1)
                    val text = elem.optString("t", "").ifBlank {
                        elem.optString("text", "").ifBlank {
                            elem.optString("translation", "")
                        }
                    }.trim()
                    val resolvedId = if (validIds.contains(id)) id else items.getOrNull(i)?.id ?: -1
                    if (resolvedId != -1 && text.isNotEmpty()) {
                        out[resolvedId] = text
                    }
                }
                is String -> {
                    val resolvedId = items.getOrNull(i)?.id ?: -1
                    if (resolvedId != -1 && elem.isNotBlank()) {
                        out[resolvedId] = elem.trim()
                    }
                }
            }
        }
    }

    // =========================================================================
    // ENGINE C: MULTI-ENGINE ANTI-LIMIT (NO-KEY MODE / FALLBACK)
    // =========================================================================

    private fun translateViaAntiLimitCascade(
        items: List<FlatBubbleItem>,
        comicStyle: Int
    ): Map<Int, String> {
        val result = HashMap<Int, String>(items.size)
        val byLang = items.groupBy { it.sourceLangCode }

        for ((langCode, group) in byLang) {
            val texts = group.map { it.cleanText }

            // Tier 1: Rotated Google Neural / Lingva Endpoints (spreads requests across 5 endpoints)
            var translatedList = translateViaRotatedNeuralEndpoints(texts, langCode)

            // If a big cross-panel batch had a newline alignment mismatch, split into smaller safe chunks of 5
            if (translatedList == null && texts.size > 5) {
                val chunkedOut = ArrayList<String?>(texts.size)
                var allOk = true
                for (chunk in texts.chunked(5)) {
                    val part = translateViaRotatedNeuralEndpoints(chunk, langCode)
                    if (part == null) {
                        allOk = false
                        break
                    }
                    chunkedOut.addAll(part)
                }
                if (allOk && chunkedOut.size == texts.size) {
                    translatedList = chunkedOut
                }
            }

            // Tier 2: MyMemory Neural MT Batch
            if (translatedList == null && SystemClock.uptimeMillis() >= myMemoryCooldownUntilMs) {
                translatedList = translateViaMyMemoryBatches(texts, langCode)
            }

            // Tier 3: Pollinations Keyless AI Comic Translator (style-aware)
            if (translatedList == null && SystemClock.uptimeMillis() >= keylessAiCooldownUntilMs) {
                translatedList = translateViaKeylessAiBatch(texts, langCode, comicStyle)
            }

            if (translatedList != null) {
                for (i in group.indices) {
                    val t = translatedList.getOrNull(i)
                    if (!t.isNullOrBlank()) {
                        result[group[i].id] = t
                    }
                }
            }

            // Fill any individual gaps via rotated single-item or MyMemory
            for (item in group) {
                if (!result[item.id].isNullOrBlank()) continue
                val singleNeural = translateViaRotatedNeuralEndpoints(listOf(item.cleanText), langCode)?.firstOrNull()
                if (!singleNeural.isNullOrBlank()) {
                    result[item.id] = singleNeural
                } else if (SystemClock.uptimeMillis() >= myMemoryCooldownUntilMs) {
                    val singleMm = translateMyMemorySingleChunk(listOf(item.cleanText), langCode)?.firstOrNull()
                    if (!singleMm.isNullOrBlank()) {
                        result[item.id] = singleMm
                    }
                }
            }
        }

        return result
    }

    /**
     * Rotates through 5 independent neural translation endpoints so high-volume chapter scanning
     * avoids single-endpoint IP rate limits.
     */
    private fun translateViaRotatedNeuralEndpoints(
        texts: List<String>,
        sourceLang: String
    ): List<String?>? {
        if (texts.isEmpty()) return emptyList()
        val now = SystemClock.uptimeMillis()
        val startIdx = (neuralRoundRobin.getAndIncrement() and 0x7FFFFFFF) % NEURAL_ENDPOINT_COUNT

        for (offset in 0 until NEURAL_ENDPOINT_COUNT) {
            val endpointIdx = (startIdx + offset) % NEURAL_ENDPOINT_COUNT
            if (now < neuralCooldowns[endpointIdx]) continue

            val res = when (endpointIdx) {
                0 -> callGoogleSingleEndpoint(
                    url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=$sourceLang&tl=id&dt=t",
                    texts = texts,
                    endpointIdx = endpointIdx
                )
                1 -> callGoogleSingleEndpoint(
                    url = "https://translate.google.com/translate_a/single?client=at&dt=t&sl=$sourceLang&tl=id",
                    texts = texts,
                    endpointIdx = endpointIdx
                )
                2 -> callGoogleSingleEndpoint(
                    url = "https://translate.googleapis.com/translate_a/single?client=dict-chrome-ex&sl=$sourceLang&tl=id&dt=t",
                    texts = texts,
                    endpointIdx = endpointIdx
                )
                3 -> callClients5Endpoint(texts, sourceLang, endpointIdx)
                else -> callLingvaEndpoint(texts, sourceLang, endpointIdx)
            }
            if (res != null) return res
        }
        return null
    }

    private fun callGoogleSingleEndpoint(
        url: String,
        texts: List<String>,
        endpointIdx: Int
    ): List<String?>? {
        var conn: HttpURLConnection? = null
        return try {
            val joinedPayload = texts.joinToString("\n")
            val postBody = "q=" + URLEncoder.encode(joinedPayload, "UTF-8")
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                instanceFollowRedirects = false
                doOutput = true
                connectTimeout = NEURAL_FAST_CONNECT_MS
                readTimeout = NEURAL_FAST_READ_MS
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126.0.0.0 Mobile Safari/537.36"
                )
            }

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(postBody)
                writer.flush()
            }

            val code = conn.responseCode
            if (code in 300..399 || code == 429 || code == 403 || code == 503) {
                neuralCooldowns[endpointIdx] = SystemClock.uptimeMillis() + RATE_LIMIT_COOLDOWN_MS
                return null
            }
            if (code != HttpURLConnection.HTTP_OK) return null

            val body = readStreamSafe(conn.inputStream)
            if (body.isEmpty() || body.startsWith("<")) {
                neuralCooldowns[endpointIdx] = SystemClock.uptimeMillis() + RATE_LIMIT_COOLDOWN_MS
                return null
            }

            val root = JSONArray(body)
            val segments = root.optJSONArray(0) ?: return null
            val sb = StringBuilder()
            for (i in 0 until segments.length()) {
                val seg = segments.optJSONArray(i) ?: continue
                val piece = seg.optString(0, "")
                if (piece.isNotEmpty() && piece != "null") {
                    sb.append(piece)
                }
            }

            val fullTranslated = sb.toString().trim()
            if (fullTranslated.isEmpty()) return null
            if (texts.size == 1) return listOf(fullTranslated.replace("\n", " ").trim())

            val splitLines = fullTranslated.split("\n").map { it.trim() }
            if (splitLines.size == texts.size) splitLines else null
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun callClients5Endpoint(
        texts: List<String>,
        sourceLang: String,
        endpointIdx: Int
    ): List<String?>? {
        var conn: HttpURLConnection? = null
        return try {
            val joinedPayload = texts.joinToString("\n")
            val postBody = "q=" + URLEncoder.encode(joinedPayload, "UTF-8")
            val urlStr = "https://clients5.google.com/translate_a/t?client=dict-chrome-ex&sl=$sourceLang&tl=id"

            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                instanceFollowRedirects = false
                doOutput = true
                connectTimeout = NEURAL_FAST_CONNECT_MS
                readTimeout = NEURAL_FAST_READ_MS
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/126.0.0.0 Safari/537.36"
                )
            }

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(postBody)
                writer.flush()
            }

            val code = conn.responseCode
            if (code in 300..399 || code == 429 || code == 403 || code == 503) {
                neuralCooldowns[endpointIdx] = SystemClock.uptimeMillis() + RATE_LIMIT_COOLDOWN_MS
                return null
            }
            if (code != HttpURLConnection.HTTP_OK) return null

            val body = readStreamSafe(conn.inputStream)
            if (body.isEmpty() || body.startsWith("<")) {
                neuralCooldowns[endpointIdx] = SystemClock.uptimeMillis() + RATE_LIMIT_COOLDOWN_MS
                return null
            }

            val fullTranslated = parseClients5Body(body) ?: return null
            if (texts.size == 1) return listOf(fullTranslated.replace("\n", " ").trim())

            val splitLines = fullTranslated.split("\n").map { it.trim() }
            if (splitLines.size == texts.size) splitLines else null
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun parseClients5Body(body: String): String? {
        return try {
            if (body.startsWith("[")) {
                val arr = JSONArray(body)
                if (arr.length() == 0) return null
                val first = arr.opt(0)
                when (first) {
                    is String -> first.trim()
                    is JSONArray -> first.optString(0, "").trim()
                    else -> null
                }
            } else if (body.startsWith("{")) {
                val obj = JSONObject(body)
                val sentences = obj.optJSONArray("sentences") ?: return null
                val sb = StringBuilder()
                for (i in 0 until sentences.length()) {
                    val t = sentences.optJSONObject(i)?.optString("trans", "").orEmpty()
                    sb.append(t)
                }
                sb.toString().trim().ifEmpty { null }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun callLingvaEndpoint(
        texts: List<String>,
        sourceLang: String,
        endpointIdx: Int
    ): List<String?>? {
        val lingvaSrc = if (sourceLang == "zh-CN") "zh" else sourceLang
        val joined = texts.joinToString("\n").replace("/", " ")
        if (joined.length > 900) return null

        val encodedPath = URLEncoder.encode(joined, "UTF-8").replace("+", "%20")
        val host = LINGVA_HOSTS[(lingvaHostCounter.getAndIncrement() and 0x7FFFFFFF) % LINGVA_HOSTS.size]
        var conn: HttpURLConnection? = null
        return try {
            val urlStr = "https://$host/api/v1/$lingvaSrc/id/$encodedPath"
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = NEURAL_FAST_CONNECT_MS
                readTimeout = NEURAL_FAST_READ_MS
                setRequestProperty("User-Agent", "ScreenMangaTranslator/3.2")
            }

            val code = conn.responseCode
            if (code == 429 || code >= 500) {
                neuralCooldowns[endpointIdx] = SystemClock.uptimeMillis() + 60_000L
                return null
            }
            if (code != HttpURLConnection.HTTP_OK) return null

            val body = readStreamSafe(conn.inputStream)
            if (body.isEmpty() || !body.startsWith("{")) return null

            val translated = JSONObject(body).optString("translation", "").trim()
            if (translated.isEmpty()) return null
            if (texts.size == 1) return listOf(translated.replace("\n", " ").trim())

            val splitLines = translated.split("\n").map { it.trim() }
            if (splitLines.size == texts.size) splitLines else null
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Tier 2: MyMemory Official Free Neural MT API.
     */
    private fun translateViaMyMemoryBatches(
        texts: List<String>,
        sourceLang: String
    ): List<String?>? {
        if (texts.isEmpty()) return emptyList()

        val chunks = ArrayList<List<String>>()
        var currentChunk = ArrayList<String>()
        var currentBytes = 0

        for (line in texts) {
            val lineBytes = line.toByteArray(Charsets.UTF_8).size + 2
            if (currentChunk.isNotEmpty() && currentBytes + lineBytes > MYMEMORY_MAX_UTF8_BYTES) {
                chunks.add(currentChunk)
                currentChunk = ArrayList()
                currentBytes = 0
            }
            currentChunk.add(line)
            currentBytes += lineBytes
        }
        if (currentChunk.isNotEmpty()) {
            chunks.add(currentChunk)
        }

        if (chunks.size == 1) {
            return translateMyMemorySingleChunk(chunks[0], sourceLang)
        }

        val futures = chunks.map { chunk ->
            executor.submit(Callable {
                translateMyMemorySingleChunk(chunk, sourceLang)
            })
        }

        val combined = ArrayList<String?>(texts.size)
        for (i in futures.indices) {
            val part = try {
                futures[i].get(7, TimeUnit.SECONDS)
            } catch (_: Exception) {
                null
            } ?: return null
            combined.addAll(part)
        }

        return if (combined.size == texts.size) combined else null
    }

    private fun translateMyMemorySingleChunk(
        chunk: List<String>,
        sourceLang: String
    ): List<String?>? {
        if (chunk.isEmpty()) return emptyList()
        var conn: HttpURLConnection? = null
        return try {
            val joined = chunk.joinToString("\n")
            val safeQuery = trimUtf8Bytes(joined, 470)
            val encodedQ = URLEncoder.encode(safeQuery, "UTF-8")
            val encodedPair = URLEncoder.encode("$sourceLang|id", "UTF-8")
            val urlStr =
                "https://api.mymemory.translated.net/get?q=$encodedQ&langpair=$encodedPair"

            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = MYMEMORY_CONNECT_MS
                readTimeout = MYMEMORY_READ_MS
                setRequestProperty("User-Agent", "ScreenMangaTranslator/3.2 (Android)")
            }

            val code = conn.responseCode
            if (code == 429 || code == 403) {
                myMemoryCooldownUntilMs = SystemClock.uptimeMillis() + RATE_LIMIT_COOLDOWN_MS
                return null
            }
            if (code != HttpURLConnection.HTTP_OK) return null

            val body = readStreamSafe(conn.inputStream)
            val root = JSONObject(body)
            if (root.optBoolean("quotaFinished", false)) {
                myMemoryCooldownUntilMs = SystemClock.uptimeMillis() + 600_000L
                return null
            }
            val status = root.optInt("responseStatus", 0)
            if (status != 200) return null

            val rawTranslated = root.optJSONObject("responseData")
                ?.optString("translatedText", "")
                .orEmpty()
                .trim()
            if (rawTranslated.isEmpty() ||
                rawTranslated.contains("MYMEMORY WARNING", ignoreCase = true) ||
                rawTranslated.contains("QUERY LENGTH LIMIT", ignoreCase = true)
            ) {
                return null
            }

            val decoded = decodeHtmlEntities(rawTranslated)
            if (chunk.size == 1) {
                return listOf(decoded.replace("\n", " ").trim())
            }

            val lines = decoded.split("\n").map { it.trim() }
            if (lines.size == chunk.size) {
                lines
            } else {
                val perLine = ArrayList<String?>(chunk.size)
                for (single in chunk) {
                    val singleRes = translateMyMemorySingleChunk(listOf(single), sourceLang)?.firstOrNull()
                    if (singleRes.isNullOrBlank()) return null
                    perLine.add(singleRes)
                }
                perLine
            }
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Tier 3: Public Keyless AI Comic Translator (Pollinations OpenAI-compatible text endpoint).
     */
    private fun translateViaKeylessAiBatch(
        texts: List<String>,
        sourceLang: String,
        comicStyle: Int
    ): List<String?>? {
        if (texts.isEmpty()) return emptyList()
        var conn: HttpURLConnection? = null
        return try {
            val langName = when (sourceLang) {
                "ja" -> "Japanese manga"
                "ko" -> "Korean manhwa"
                "zh-CN" -> "Chinese manhua"
                else -> "English comic"
            }
            val styleDesc = if (comicStyle == TranslatorPrefs.STYLE_CASUAL) {
                "casual Indonesian comic slang (gue/lu)"
            } else {
                "natural official Indonesian Webtoon dialogue (aku/kamu/kau)"
            }
            val numberedInput = texts.mapIndexed { idx, s -> "[${idx + 1}] $s" }.joinToString("\n")
            val sysPrompt =
                "You are a professional Indonesian comic translator. Translate every numbered line from $langName into $styleDesc. Keep the exact [number] prefix on each line, one line per item, with NO extra commentary."

            val payload = JSONObject().apply {
                put("model", "openai")
                put("temperature", 0.2)
                put(
                    "messages",
                    JSONArray().apply {
                        put(JSONObject().put("role", "system").put("content", sysPrompt))
                        put(JSONObject().put("role", "user").put("content", numberedInput))
                    }
                )
            }

            conn = (URL("https://text.pollinations.ai/").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = AI_CONNECT_TIMEOUT_MS
                readTimeout = KEYLESS_AI_READ_TIMEOUT_MS
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("User-Agent", "ScreenMangaTranslator/3.2")
            }

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(payload.toString())
                writer.flush()
            }

            val code = conn.responseCode
            if (code == 429 || code >= 500) {
                keylessAiCooldownUntilMs = SystemClock.uptimeMillis() + RATE_LIMIT_COOLDOWN_MS
                return null
            }
            if (code != HttpURLConnection.HTTP_OK) return null

            val rawResp = readStreamSafe(conn.inputStream)
            if (rawResp.isEmpty() || rawResp.startsWith("<")) return null

            val outLines = rawResp.lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            if (outLines.size != texts.size) return null

            outLines.map { line ->
                NUMBERED_PREFIX_REGEX.replace(line, "").trim()
            }
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun lookupComicIdiom(clean: String, preferVision: Boolean): String? {
        if (KOREAN_LAUGH_REGEX.matches(clean)) return "Haha!"
        if (KOREAN_CRY_REGEX.matches(clean)) return "Hiks..."
        if (!preferVision) {
            SHORT_IDIOM_MAP[clean]?.let { return it }
        }
        return null
    }

    private fun translatePanelViaMlKitSync(
        texts: List<String>,
        sourceLangCode: String
    ): List<String?> {
        val mlKitLang = when (sourceLangCode) {
            "ja" -> TranslateLanguage.JAPANESE
            "ko" -> TranslateLanguage.KOREAN
            "zh-CN" -> TranslateLanguage.CHINESE
            else -> TranslateLanguage.ENGLISH
        }

        return try {
            val client = getOrCreateMlKitClient(mlKitLang)
            if (mlKitModelReady[mlKitLang] != true) {
                val conditions = DownloadConditions.Builder().build()
                Tasks.await(client.downloadModelIfNeeded(conditions), 10, TimeUnit.SECONDS)
                mlKitModelReady[mlKitLang] = true
            }

            val tasks = texts.map { client.translate(it) }
            Tasks.await(Tasks.whenAllComplete(tasks), 4, TimeUnit.SECONDS)
            tasks.map { task ->
                if (task.isSuccessful) task.result else null
            }
        } catch (_: Exception) {
            List(texts.size) { null }
        }
    }

    private fun getOrCreateMlKitClient(mlKitLang: String): Translator {
        return mlKitClients.computeIfAbsent(mlKitLang) { lang ->
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(lang)
                .setTargetLanguage(TranslateLanguage.INDONESIAN)
                .build()
            Translation.getClient(options)
        }
    }

    private fun readStreamSafe(stream: java.io.InputStream?): String {
        if (stream == null) return ""
        return try {
            BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText().trim() }
        } catch (_: Exception) {
            ""
        }
    }

    private fun decodeHtmlEntities(input: String): String {
        if (!input.contains('&')) return input
        return try {
            val withTokens = input.replace("\n", " __MTNL__ ")
            val decoded = Html.fromHtml(withTokens, Html.FROM_HTML_MODE_LEGACY).toString()
            decoded.replace(" __MTNL__ ", "\n")
                .replace("__MTNL__", "\n")
        } catch (_: Exception) {
            input.replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .replace("&amp;", "&")
        }
    }

    private fun trimUtf8Bytes(input: String, maxBytes: Int): String {
        val bytes = input.toByteArray(Charsets.UTF_8)
        if (bytes.size <= maxBytes) return input
        val sb = StringBuilder()
        var used = 0
        for (ch in input) {
            val cBytes = ch.toString().toByteArray(Charsets.UTF_8).size
            if (used + cBytes > maxBytes) break
            sb.append(ch)
            used += cBytes
        }
        return sb.toString()
    }

    private fun postProcessIndonesian(raw: String): String {
        var text = decodeHtmlEntities(raw)
            .replace("\r", " ")
            .replace("\n", " ")
            .trim()
        text = NUMBERED_PREFIX_REGEX.replace(text, "")
        text = MULTI_SPACE_REGEX.replace(text, " ")
        text = SPACE_BEFORE_PUNCT_REGEX.replace(text, "$1")
        text = text.replace("! ?", "!?")
            .replace("? !", "?!")
            .replace(" . . .", "...")
            .replace(". . .", "...")
            .trim('"', '“', '”', ' ')
        if (text.isNotEmpty() && text[0].isLowerCase()) {
            text = text[0].uppercaseChar() + text.substring(1)
        }
        return text.trim()
    }

    private fun isNetworkAvailable(): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) {
            true
        }
    }

    fun containsJapaneseOrChinese(text: String): Boolean {
        return text.any { c ->
            (c in '\u3040'..'\u30FF') || (c in '\u4E00'..'\u9FFF')
        }
    }

    fun containsHangul(text: String): Boolean {
        return text.any { c ->
            (c in '\uAC00'..'\uD7AF') || (c in '\u1100'..'\u11FF') || (c in '\u3130'..'\u318F')
        }
    }

    private fun hasDominantLatin(text: String): Boolean {
        var latin = 0
        var cjkOrHangul = 0
        for (c in text) {
            when {
                c in 'a'..'z' || c in 'A'..'Z' -> latin++
                (c in '\u3040'..'\u30FF') || (c in '\u4E00'..'\u9FFF') ||
                    (c in '\uAC00'..'\uD7AF') -> cjkOrHangul++
            }
        }
        return latin > 0 && latin >= cjkOrHangul
    }

    fun shutdown() {
        executor.shutdownNow()
        for (client in mlKitClients.values) {
            try {
                client.close()
            } catch (_: Exception) {
            }
        }
        mlKitClients.clear()
        mlKitModelReady.clear()
    }
}
