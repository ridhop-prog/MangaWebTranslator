package com.pribadi.mangatranslator

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.text.Text
import org.json.JSONArray
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * High-accuracy comic dialogue cleaner & translator.
 * - Cleans vertical Japanese, Korean, and ALL-CAPS English comic OCR text before translating.
 * - Uses online neural Google Translate (GTX) concurrently as primary engine for natural Indonesian.
 * - Automatically falls back to ML Kit On-Device Translator when offline or on network error.
 */
class ComicTranslator(private val context: Context) {

    companion object {
        private const val CONNECT_TIMEOUT_MS = 2500
        private const val READ_TIMEOUT_MS = 3000
        private val MULTI_SPACE_REGEX = Regex("\\s+")
        private val CJK_SPACE_REGEX = Regex("([\\u3040-\\u30FF\\u4E00-\\u9FFF\\u3000-\\u303F])\\s+([\\u3040-\\u30FF\\u4E00-\\u9FFF\\u3000-\\u303F])")
        private val PIPE_AS_I_REGEX = Regex("(?<=^|\\s)\\|(?=\\s|'|\u2019|$)")
        private val PIPE_OR_L_APOS_REGEX = Regex("\\b[|l]'([mMdDsSvVrRlL])")
        private val ZERO_IN_WORD_REGEX = Regex("(?<=[A-Za-z])0(?=[A-Za-z])")
        private val SPACE_BEFORE_PUNCT_REGEX = Regex("\\s+([!?.,:;])")
        private val STANDALONE_I_REGEX = Regex("\\bi\\b")
        private val I_CONTRACTION_REGEX = Regex("\\bi'([mdsvrl])", RegexOption.IGNORE_CASE)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newFixedThreadPool(4)
    private val cache = LruCache<String, String>(400)
    private val mlKitClients = ConcurrentHashMap<String, Translator>()

    data class TranslationRequest(
        val text: String,
        val sourceLangCode: String // "ja", "ko", "zh-CN", "en", or "auto"
    )

    /**
     * Extracts and cleans OCR text from an ML Kit [Text.TextBlock] with proper reading order
     * (right-to-left for vertical Japanese, top-to-bottom for horizontal) and comic text cleanup.
     */
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

    /**
     * Joins multiple text fragments belonging to the same speech bubble.
     */
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
                // Rejoin hyphenated English word wrap (e.g. "IMPOS-" + "SIBLE" -> "IMPOSSIBLE")
                sb.deleteCharAt(sb.length - 1)
                sb.append(part)
            } else {
                sb.append(' ').append(part)
            }
        }
        return sb.toString()
    }

    /**
     * Cleans OCR artifacts and converts ALL-CAPS English comic lettering to natural sentence case
     * so both neural online and on-device translators produce clear, accurate Indonesian.
     */
    fun normalizeComicText(raw: String): String {
        var text = raw.replace("\r", " ").replace("\n", " ").trim()
        if (text.isEmpty()) return ""

        // Remove stray spaces between Japanese/Chinese characters
        if (containsJapaneseOrChinese(text)) {
            var prev: String
            do {
                prev = text
                text = CJK_SPACE_REGEX.replace(text, "$1$2")
            } while (text != prev)
        }

        text = MULTI_SPACE_REGEX.replace(text, " ").trim()

        // Normalize common English comic font OCR issues & ALL-CAPS lettering
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

    /**
     * Detects the source language individually per bubble so mixed-language comic panels
     * (e.g. Korean/Japanese dialogue alongside English sound effects or system boxes)
     * translate accurately without corrupting the user's OCR recognizer setting.
     */
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
            hanziKanji > 0 -> when (userLangIndex) {
                0 -> "ja"
                3 -> "zh-CN"
                else -> "auto"
            }
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
     * Translates a list of comic bubbles into clear Indonesian concurrently.
     * Callback is always invoked on the main thread.
     */
    fun translateBatch(
        requests: List<TranslationRequest>,
        onComplete: (List<String?>) -> Unit
    ) {
        if (requests.isEmpty()) {
            mainHandler.post { onComplete(emptyList()) }
            return
        }

        val onlineAvailable = isNetworkAvailable()

        executor.execute {
            val futures = requests.map { req ->
                executor.submit(Callable {
                    translateSingleSync(req.text, req.sourceLangCode, onlineAvailable)
                })
            }

            val results = ArrayList<String?>(requests.size)
            for (future in futures) {
                val res = try {
                    future.get(5, TimeUnit.SECONDS)
                } catch (_: Exception) {
                    null
                }
                results.add(res)
            }

            mainHandler.post { onComplete(results) }
        }
    }

    private fun translateSingleSync(
        text: String,
        sourceLangCode: String,
        tryOnline: Boolean
    ): String? {
        val normalized = text.trim()
        if (normalized.isEmpty()) return null

        val cacheKey = "$sourceLangCode:${normalized.lowercase(Locale.ROOT)}"
        cache.get(cacheKey)?.let { return it }

        // 1. Primary: High-accuracy Google Translate Neural Web API (natural Indonesian)
        if (tryOnline) {
            val onlineResult = translateViaGoogleGtx(normalized, sourceLangCode)
            if (!onlineResult.isNullOrBlank()) {
                val polished = postProcessIndonesian(onlineResult)
                cache.put(cacheKey, polished)
                return polished
            }
        }

        // 2. Fallback: Offline ML Kit On-Device Translator
        val offlineResult = translateViaMlKitSync(normalized, sourceLangCode)
        if (!offlineResult.isNullOrBlank()) {
            val polished = postProcessIndonesian(offlineResult)
            cache.put(cacheKey, polished)
            return polished
        }

        return null
    }

    private fun translateViaGoogleGtx(text: String, sourceLang: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            val encoded = URLEncoder.encode(text, "UTF-8")
            val sl = if (sourceLang.isBlank()) "auto" else sourceLang
            val urlStr =
                "https://translate.googleapis.com/translate_a/single?client=gtx&sl=$sl&tl=id&dt=t&q=$encoded"
            connection = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36"
                )
            }

            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null

            val body = BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use {
                it.readText()
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
            val output = sb.toString().trim()
            output.ifEmpty { null }
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun translateViaMlKitSync(text: String, sourceLangCode: String): String? {
        val mlKitLang = when (sourceLangCode) {
            "ja" -> TranslateLanguage.JAPANESE
            "ko" -> TranslateLanguage.KOREAN
            "zh-CN" -> TranslateLanguage.CHINESE
            else -> TranslateLanguage.ENGLISH
        }

        return try {
            val client = mlKitClients.computeIfAbsent(mlKitLang) { lang ->
                val options = TranslatorOptions.Builder()
                    .setSourceLanguage(lang)
                    .setTargetLanguage(TranslateLanguage.INDONESIAN)
                    .build()
                Translation.getClient(options)
            }
            val conditions = DownloadConditions.Builder().build()
            Tasks.await(client.downloadModelIfNeeded(conditions), 8, TimeUnit.SECONDS)
            Tasks.await(client.translate(text), 4, TimeUnit.SECONDS)
        } catch (_: Exception) {
            null
        }
    }

    private fun postProcessIndonesian(raw: String): String {
        var text = raw.replace("\r", " ").replace("\n", " ").trim()
        text = MULTI_SPACE_REGEX.replace(text, " ")
        text = SPACE_BEFORE_PUNCT_REGEX.replace(text, "$1")
        // Fix common awkward double-quotes/ellipsis spacing
        text = text.replace(" . . .", "...").replace(". . .", "...")
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
    }
}
