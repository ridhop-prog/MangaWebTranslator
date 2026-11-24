package com.pribadi.mangatranslator

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect

/**
 * Utility to detect the geometric bounds and solid background color of a comic/manga
 * speech bubble surrounding a recognized text block.
 */
object SpeechBubbleDetector {

    /**
     * Given the full screen [bitmap] and the OCR [textRect], finds the expanded
     * boundary of the surrounding speech bubble.
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
            if (isLightPixel(bitmap.getPixel(sx, sy))) {
                lightCount++
            }
        }

        if (lightCount < 3) {
            val padX = (textWidth * 0.16f).toInt().coerceAtLeast(10)
            val padY = (textHeight * 0.16f).toInt().coerceAtLeast(10)
            return Rect(
                (left - padX).coerceAtLeast(0),
                (top - padY).coerceAtLeast(0),
                (right + padX).coerceAtMost(bmpWidth),
                (bottom + padY).coerceAtMost(bmpHeight)
            )
        }

        val maxExpandX = (textWidth * 0.85f).toInt().coerceIn(24, 240)
        val maxExpandY = (textHeight * 0.85f).toInt().coerceIn(24, 240)

        val y1 = top + (textHeight * 0.25).toInt()
        val y2 = top + (textHeight * 0.50).toInt()
        val y3 = top + (textHeight * 0.75).toInt()

        val x1 = left + (textWidth * 0.25).toInt()
        val x2 = left + (textWidth * 0.50).toInt()
        val x3 = left + (textWidth * 0.75).toInt()

        // 1. Expand LEFT
        val leftLimit = (left - maxExpandX).coerceAtLeast(0)
        var bubbleLeft = left
        for (x in left downTo leftLimit step 2) {
            var darkHits = 0
            for (y in listOf(y1, y2, y3)) {
                if (y in 0 until bmpHeight && !isLightPixel(bitmap.getPixel(x, y))) {
                    darkHits++
                }
            }
            if (darkHits >= 2) {
                bubbleLeft = (x + 4).coerceAtMost(left)
                break
            }
            bubbleLeft = x
        }

        // 2. Expand RIGHT
        val rightLimit = (right + maxExpandX).coerceAtMost(bmpWidth - 1)
        var bubbleRight = right
        for (x in right..rightLimit step 2) {
            var darkHits = 0
            for (y in listOf(y1, y2, y3)) {
                if (y in 0 until bmpHeight && !isLightPixel(bitmap.getPixel(x, y))) {
                    darkHits++
                }
            }
            if (darkHits >= 2) {
                bubbleRight = (x - 4).coerceAtLeast(right)
                break
            }
            bubbleRight = x
        }

        // 3. Expand TOP
        val topLimit = (top - maxExpandY).coerceAtLeast(0)
        var bubbleTop = top
        for (y in top downTo topLimit step 2) {
            var darkHits = 0
            for (x in listOf(x1, x2, x3)) {
                if (x in 0 until bmpWidth && !isLightPixel(bitmap.getPixel(x, y))) {
                    darkHits++
                }
            }
            if (darkHits >= 2) {
                bubbleTop = (y + 4).coerceAtMost(top)
                break
            }
            bubbleTop = y
        }

        // 4. Expand BOTTOM
        val bottomLimit = (bottom + maxExpandY).coerceAtMost(bmpHeight - 1)
        var bubbleBottom = bottom
        for (y in bottom..bottomLimit step 2) {
            var darkHits = 0
            for (x in listOf(x1, x2, x3)) {
                if (x in 0 until bmpWidth && !isLightPixel(bitmap.getPixel(x, y))) {
                    darkHits++
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
     * Samples pixels along the immediate perimeter of [textRect] to determine the best
     * 100%-opaque solid fill color that cleanly erases the original comic text.
     */
    fun detectBubbleBgColor(bitmap: Bitmap, textRect: Rect): Int {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 4 || h <= 4) return Color.WHITE

        val l = (textRect.left - 3).coerceIn(0, w - 1)
        val r = (textRect.right + 3).coerceIn(0, w - 1)
        val t = (textRect.top - 3).coerceIn(0, h - 1)
        val b = (textRect.bottom + 3).coerceIn(0, h - 1)
        val cx = ((l + r) / 2).coerceIn(0, w - 1)
        val cy = ((t + b) / 2).coerceIn(0, h - 1)
        val qx1 = ((l * 3 + r) / 4).coerceIn(0, w - 1)
        val qx3 = ((l + r * 3) / 4).coerceIn(0, w - 1)
        val qy1 = ((t * 3 + b) / 4).coerceIn(0, h - 1)
        val qy3 = ((t + b * 3) / 4).coerceIn(0, h - 1)

        val coords = intArrayOf(
            l, t,  qx1, t,  cx, t,  qx3, t,  r, t,
            l, b,  qx1, b,  cx, b,  qx3, b,  r, b,
            l, qy1,  l, cy,  l, qy3,
            r, qy1,  r, cy,  r, qy3
        )

        var lightCount = 0
        var darkCount = 0
        var sumR = 0
        var sumG = 0
        var sumB = 0
        val total = coords.size / 2

        val lumaList = IntArray(total)
        val rList = IntArray(total)
        val gList = IntArray(total)
        val bList = IntArray(total)

        for (i in 0 until total) {
            val px = bitmap.getPixel(coords[i * 2], coords[i * 2 + 1])
            val red = (px shr 16) and 0xFF
            val green = (px shr 8) and 0xFF
            val blue = px and 0xFF
            val lum = (77 * red + 150 * green + 29 * blue) shr 8

            lumaList[i] = lum
            rList[i] = red
            gList[i] = green
            bList[i] = blue
            sumR += red
            sumG += green
            sumB += blue

            if (lum >= 180) lightCount++
            else if (lum <= 80) darkCount++
        }

        // Standard white / light comic speech bubble -> crisp 100% opaque pure white
        if (lightCount >= total / 2) {
            return Color.WHITE
        }

        // Dark / black comic speech bubble (e.g. inner monologue / action narration box)
        if (darkCount >= (total * 2) / 3) {
            return Color.rgb(20, 20, 26)
        }

        // Colored or gray speech bubble: average the middle-luminance cluster (ignore extreme ink)
        val avgR = (sumR / total).coerceIn(0, 255)
        val avgG = (sumG / total).coerceIn(0, 255)
        val avgB = (sumB / total).coerceIn(0, 255)
        val avgLum = (77 * avgR + 150 * avgG + 29 * avgB) shr 8

        return when {
            avgLum >= 195 -> Color.WHITE
            avgLum <= 55 -> Color.rgb(20, 20, 26)
            else -> Color.rgb(avgR, avgG, avgB)
        }
    }

    /**
     * Returns true if [color] is light enough to pair with dark comic text.
     */
    fun isLightColor(color: Int): Boolean {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        val lum = (77 * r + 150 * g + 29 * b) shr 8
        return lum >= 138
    }

    private fun isLightPixel(pixel: Int): Boolean {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        val luminance = 0.299 * r + 0.587 * g + 0.114 * b
        return luminance > 185.0
    }
}
