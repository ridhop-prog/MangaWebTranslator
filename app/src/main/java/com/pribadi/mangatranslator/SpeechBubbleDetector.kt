package com.pribadi.mangatranslator

import android.graphics.Bitmap
import android.graphics.Rect

/**
 * Utility to detect the geometric bounds of a comic/manga speech bubble surrounding
 * a recognized text block.
 */
object SpeechBubbleDetector {

    /**
     * Given the full screen [bitmap] and the OCR [textRect], finds the expanded
     * boundary of the surrounding speech bubble.
     *
     * In manga/comics, speech bubbles are typically white or very light regions
     * enclosed by a dark stroke or comic artwork.
     */
    fun detectBubbleBounds(bitmap: Bitmap, textRect: Rect): Rect {
        val bmpWidth = bitmap.width
        val bmpHeight = bitmap.height

        val left = textRect.left.coerceIn(0, bmpWidth - 1)
        val right = textRect.right.coerceIn(0, bmpWidth - 1)
        val top = textRect.top.coerceIn(0, bmpHeight - 1)
        val bottom = textRect.bottom.coerceIn(0, bmpHeight - 1)

        val textWidth = right - left
        val textHeight = bottom - top
        if (textWidth <= 0 || textHeight <= 0) return textRect

        // Sample luminance at corners just outside the text box to verify if it's in a light bubble
        val samplePoints = listOf(
            Pair((left - 4).coerceIn(0, bmpWidth - 1), (top - 4).coerceIn(0, bmpHeight - 1)),
            Pair((right + 4).coerceIn(0, bmpWidth - 1), (top - 4).coerceIn(0, bmpHeight - 1)),
            Pair((left - 4).coerceIn(0, bmpWidth - 1), (bottom + 4).coerceIn(0, bmpHeight - 1)),
            Pair((right + 4).coerceIn(0, bmpWidth - 1), (bottom + 4).coerceIn(0, bmpHeight - 1)),
            Pair(((left + right) / 2), (top - 4).coerceIn(0, bmpHeight - 1)),
            Pair(((left + right) / 2), (bottom + 4).coerceIn(0, bmpHeight - 1))
        )

        var lightCount = 0
        for ((sx, sy) in samplePoints) {
            val pixel = bitmap.getPixel(sx, sy)
            if (isLightPixel(pixel)) {
                lightCount++
            }
        }

        // If not mostly light/white around text, it is likely on dark/textured artwork
        if (lightCount < 3) {
            // Expand slightly around text center for natural padding
            val padX = (textWidth * 0.15f).toInt().coerceAtLeast(8)
            val padY = (textHeight * 0.15f).toInt().coerceAtLeast(8)
            return Rect(
                (left - padX).coerceAtLeast(0),
                (top - padY).coerceAtLeast(0),
                (right + padX).coerceAtMost(bmpWidth),
                (bottom + padY).coerceAtMost(bmpHeight)
            )
        }

        // Ray-casting limits: don't expand beyond reasonable comic bubble dimensions
        val maxExpandX = (textWidth * 0.9f).toInt().coerceIn(24, 250)
        val maxExpandY = (textHeight * 0.9f).toInt().coerceIn(24, 250)

        val y1 = top + (textHeight * 0.25).toInt()
        val y2 = top + (textHeight * 0.50).toInt()
        val y3 = top + (textHeight * 0.75).toInt()

        val x1 = left + (textWidth * 0.25).toInt()
        val x2 = left + (textWidth * 0.50).toInt()
        val x3 = left + (textWidth * 0.75).toInt()

        // 1. Expand LEFT: check 3 horizontal rays
        val leftLimit = (left - maxExpandX).coerceAtLeast(0)
        var bubbleLeft = left
        for (x in left downTo leftLimit step 2) {
            var darkHits = 0
            for (y in listOf(y1, y2, y3)) {
                if (y in 0 until bmpHeight) {
                    if (!isLightPixel(bitmap.getPixel(x, y))) {
                        darkHits++
                    }
                }
            }
            if (darkHits >= 2) {
                bubbleLeft = (x + 4).coerceAtMost(left)
                break
            }
            bubbleLeft = x
        }

        // 2. Expand RIGHT: check 3 horizontal rays
        val rightLimit = (right + maxExpandX).coerceAtMost(bmpWidth - 1)
        var bubbleRight = right
        for (x in right..rightLimit step 2) {
            var darkHits = 0
            for (y in listOf(y1, y2, y3)) {
                if (y in 0 until bmpHeight) {
                    if (!isLightPixel(bitmap.getPixel(x, y))) {
                        darkHits++
                    }
                }
            }
            if (darkHits >= 2) {
                bubbleRight = (x - 4).coerceAtLeast(right)
                break
            }
            bubbleRight = x
        }

        // 3. Expand TOP: check 3 vertical rays
        val topLimit = (top - maxExpandY).coerceAtLeast(0)
        var bubbleTop = top
        for (y in top downTo topLimit step 2) {
            var darkHits = 0
            for (x in listOf(x1, x2, x3)) {
                if (x in 0 until bmpWidth) {
                    if (!isLightPixel(bitmap.getPixel(x, y))) {
                        darkHits++
                    }
                }
            }
            if (darkHits >= 2) {
                bubbleTop = (y + 4).coerceAtMost(top)
                break
            }
            bubbleTop = y
        }

        // 4. Expand BOTTOM: check 3 vertical rays
        val bottomLimit = (bottom + maxExpandY).coerceAtMost(bmpHeight - 1)
        var bubbleBottom = bottom
        for (y in bottom..bottomLimit step 2) {
            var darkHits = 0
            for (x in listOf(x1, x2, x3)) {
                if (x in 0 until bmpWidth) {
                    if (!isLightPixel(bitmap.getPixel(x, y))) {
                        darkHits++
                    }
                }
            }
            if (darkHits >= 2) {
                bubbleBottom = (y - 4).coerceAtLeast(bottom)
                break
            }
            bubbleBottom = y
        }

        return Rect(bubbleLeft, bubbleTop, bubbleRight, bubbleBottom)
    }

    /**
     * Determines whether a pixel is light enough to be part of a comic speech bubble.
     * Comic speech bubbles are usually white or very light pastel (luminance > 190).
     */
    private fun isLightPixel(pixel: Int): Boolean {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        val luminance = 0.299 * r + 0.587 * g + 0.114 * b
        return luminance > 190.0
    }
}
