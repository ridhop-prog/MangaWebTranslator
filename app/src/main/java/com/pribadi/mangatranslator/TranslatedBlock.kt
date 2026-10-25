package com.pribadi.mangatranslator

import android.graphics.Rect

data class TranslatedBlock(
    val boundingBox: Rect,
    val translatedText: String,
    val bubbleBox: Rect? = null
)
