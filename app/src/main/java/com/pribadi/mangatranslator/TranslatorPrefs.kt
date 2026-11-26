package com.pribadi.mangatranslator

import android.content.Context
import android.content.SharedPreferences

/**
 * Centralized SharedPreferences helper for language, appearance, comic dialogue style,
 * and AI translation engine settings (Gemini / Groq / Multi-Engine Anti-Limit).
 */
object TranslatorPrefs {
    const val PREF_NAME = "ScreenMangaTranslatorPrefs"

    const val KEY_LANG_INDEX = "pref_key_lang_index"
    const val KEY_BG_STYLE = "pref_key_bg_style"
    const val KEY_TEXT_SCALE = "pref_key_text_scale"
    const val KEY_LAST_URL = "pref_key_last_comic_url"

    const val KEY_GEMINI_API_KEY = "pref_key_gemini_api_key"
    const val KEY_GROQ_API_KEY = "pref_key_groq_api_key"
    const val KEY_AI_PROVIDER = "pref_key_ai_provider"
    const val KEY_COMIC_STYLE = "pref_key_comic_style"
    const val KEY_GEMINI_VISION = "pref_key_gemini_vision"

    // AI Provider modes
    const val PROVIDER_AUTO = 0   // Gemini (if key) -> Groq (if key) -> Multi-Engine Anti-Limit
    const val PROVIDER_GEMINI = 1 // Prefer Gemini AI
    const val PROVIDER_GROQ = 2   // Prefer Groq AI
    const val PROVIDER_NO_KEY = 3 // Multi-Engine Anti-Limit only (no API key)

    // Comic dialogue styles
    const val STYLE_NATURAL = 0   // Webtoon Resmi: Aku / Kamu / Kau
    const val STYLE_CASUAL = 1    // Santai / Gaul: Gue / Lu

    fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun getLangIndex(context: Context): Int =
        prefs(context).getInt(KEY_LANG_INDEX, 0).coerceIn(0, 3)

    fun setLangIndex(context: Context, index: Int) {
        prefs(context).edit().putInt(KEY_LANG_INDEX, index.coerceIn(0, 3)).apply()
    }

    fun getBgStyle(context: Context): Int =
        prefs(context).getInt(KEY_BG_STYLE, OverlayView.STYLE_SEAMLESS_WHITE)

    fun setBgStyle(context: Context, style: Int) {
        prefs(context).edit().putInt(KEY_BG_STYLE, style).apply()
    }

    fun getTextScale(context: Context): Float =
        prefs(context).getFloat(KEY_TEXT_SCALE, 1.0f).coerceIn(0.75f, 1.35f)

    fun setTextScale(context: Context, scale: Float) {
        prefs(context).edit().putFloat(KEY_TEXT_SCALE, scale.coerceIn(0.75f, 1.35f)).apply()
    }

    fun getGeminiApiKey(context: Context): String =
        prefs(context).getString(KEY_GEMINI_API_KEY, "")?.trim().orEmpty()

    fun setGeminiApiKey(context: Context, key: String) {
        prefs(context).edit().putString(KEY_GEMINI_API_KEY, key.trim()).apply()
    }

    fun getGroqApiKey(context: Context): String =
        prefs(context).getString(KEY_GROQ_API_KEY, "")?.trim().orEmpty()

    fun setGroqApiKey(context: Context, key: String) {
        prefs(context).edit().putString(KEY_GROQ_API_KEY, key.trim()).apply()
    }

    fun getAiProvider(context: Context): Int =
        prefs(context).getInt(KEY_AI_PROVIDER, PROVIDER_AUTO).coerceIn(0, 3)

    fun setAiProvider(context: Context, provider: Int) {
        prefs(context).edit().putInt(KEY_AI_PROVIDER, provider.coerceIn(0, 3)).apply()
    }

    fun getComicStyle(context: Context): Int =
        prefs(context).getInt(KEY_COMIC_STYLE, STYLE_NATURAL).coerceIn(0, 1)

    fun setComicStyle(context: Context, style: Int) {
        prefs(context).edit().putInt(KEY_COMIC_STYLE, style.coerceIn(0, 1)).apply()
    }

    fun isGeminiVisionEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_GEMINI_VISION, true)

    fun setGeminiVisionEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_GEMINI_VISION, enabled).apply()
    }

    fun getStyleShortLabel(style: Int): String =
        if (style == STYLE_CASUAL) "Gaul" else "Natural"

    fun getStyleFullLabel(style: Int): String =
        if (style == STYLE_CASUAL) "Santai / Gaul (Gue/Lu)" else "Komik Natural (Aku/Kamu)"

    /**
     * Signature used in panel disk cache keys so changing style or switching AI/No-Key
     * invalidates old panel renders cleanly.
     */
    fun getTranslationCacheSignature(context: Context): String {
        val style = getComicStyle(context)
        val provider = getAiProvider(context)
        val hasGemini = getGeminiApiKey(context).isNotEmpty()
        val hasGroq = getGroqApiKey(context).isNotEmpty()
        val vision = isGeminiVisionEnabled(context)
        val tier = when (provider) {
            PROVIDER_NO_KEY -> "nokey"
            PROVIDER_GEMINI -> if (hasGemini) (if (vision) "gemv" else "gem") else "nokey"
            PROVIDER_GROQ -> if (hasGroq) "groq" else "nokey"
            else -> when {
                hasGemini -> if (vision) "gemv" else "gem"
                hasGroq -> "groq"
                else -> "nokey"
            }
        }
        return "ST=$style|ENG=$tier"
    }
}
