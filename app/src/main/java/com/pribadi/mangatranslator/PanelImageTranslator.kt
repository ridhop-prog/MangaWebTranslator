package com.pribadi.mangatranslator

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Base64
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Downloads a comic panel image, detects all speech bubbles across the entire panel
 * (including tall vertical Webtoon strips via overlapping vertical chunking), translates
 * all bubbles together, and bakes solid white bubbles + Indonesian text directly into
 * a cached panel image for 100% native lag-free scrolling.
 */
class PanelImageTranslator(
    private val context: Context,
    private val comicTranslator: ComicTranslator
) {

    companion object {
        private const val MAX_PANEL_WIDTH = 1080
        private const val MAX_SINGLE_OCR_HEIGHT = 1750
        private const val CHUNK_HEIGHT = 1550
        private const val CHUNK_OVERLAP = 200
        private const val DOWNLOAD_CONNECT_TIMEOUT_MS = 8000
        private const val DOWNLOAD_READ_TIMEOUT_MS = 12000
    }

    private data class RawBubbleCandidate(
        val boundingBox: Rect,
        val bubbleBox: Rect,
        val cleanText: String
    )

    private val outputDir: File = File(context.cacheDir, "translated_panels").apply {
        if (!exists()) mkdirs()
    }

    init {
        cleanOldCacheFiles()
    }

    fun cleanOldCacheFiles() {
        try {
            val files = outputDir.listFiles() ?: return
            val cutoff = System.currentTimeMillis() - 2 * 3600 * 1000L
            for (f in files) {
                if (f.lastModified() < cutoff) {
                    f.delete()
                }
            }
        } catch (_: Exception) {
        }
    }

    fun clearAllCache() {
        try {
            outputDir.listFiles()?.forEach { it.delete() }
        } catch (_: Exception) {
        }
    }

    fun getCachedFile(fileName: String): File? {
        val safeName = File(fileName).name
        val f = File(outputDir, safeName)
        return if (f.exists() && f.isFile) f else null
    }

    /**
     * Synchronously downloads, OCRs, translates, and bakes a comic panel image on a worker thread.
     * Returns the cached file name if at least 1 speech bubble was translated, or "" if no text found,
     * or null if download/decode failed.
     */
    fun translatePanelImageSync(
        imageUrl: String,
        pageUrl: String,
        cookie: String?,
        userAgent: String,
        langIndex: Int,
        bgStyle: Int,
        textScale: Float,
        isCancelled: () -> Boolean
    ): String? {
        if (isCancelled()) return null

        val cacheKey = sha256("$imageUrl|L=$langIndex|B=$bgStyle|S=$textScale")
        val outFileName = "panel_$cacheKey.jpg"
        val outFile = File(outputDir, outFileName)
        if (outFile.exists() && outFile.length() > 512) {
            return outFileName
        }

        val rawBytes = fetchImageBytes(imageUrl, pageUrl, cookie, userAgent) ?: return null
        if (isCancelled()) return null

        val bitmap = decodeOptimalPanelBitmap(rawBytes) ?: return null
        if (bitmap.width < 180 || bitmap.height < 140) {
            bitmap.recycle()
            return ""
        }

        if (isCancelled()) {
            bitmap.recycle()
            return null
        }

        val candidates = runChunkedPanelOcr(bitmap, langIndex, isCancelled)
        if (candidates == null) {
            bitmap.recycle()
            return null
        }
        if (candidates.isEmpty()) {
            bitmap.recycle()
            return ""
        }

        val mergedBubbles = mergeSameBubbleCandidates(candidates)
        if (mergedBubbles.isEmpty()) {
            bitmap.recycle()
            return ""
        }

        val requests = mergedBubbles.map { item ->
            val srcLang = comicTranslator.detectBubbleSourceLang(item.cleanText, langIndex)
            ComicTranslator.TranslationRequest(item.cleanText, srcLang)
        }

        var translatedTexts: List<String?> = emptyList()
        val latch = CountDownLatch(1)
        comicTranslator.translatePanelBatch(requests) { results ->
            translatedTexts = results
            latch.countDown()
        }
        try {
            latch.await(12, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            bitmap.recycle()
            return null
        }

        if (isCancelled()) {
            bitmap.recycle()
            return null
        }

        val blocksToBake = ArrayList<TranslatedBlock>(mergedBubbles.size)
        for (i in mergedBubbles.indices) {
            val trans = translatedTexts.getOrNull(i)
            val bubble = mergedBubbles[i]
            if (!trans.isNullOrBlank()) {
                blocksToBake.add(
                    TranslatedBlock(
                        boundingBox = bubble.boundingBox,
                        translatedText = trans,
                        bubbleBox = bubble.bubbleBox,
                        originalText = bubble.cleanText
                    )
                )
            }
        }

        if (blocksToBake.isEmpty()) {
            bitmap.recycle()
            return ""
        }

        bakeTranslationsOntoBitmap(bitmap, blocksToBake, bgStyle, textScale)

        return try {
            FileOutputStream(outFile).use { fos ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 88, fos)
                fos.flush()
            }
            bitmap.recycle()
            outFileName
        } catch (_: Exception) {
            bitmap.recycle()
            null
        }
    }

    private fun fetchImageBytes(
        imageUrl: String,
        pageUrl: String,
        cookie: String?,
        userAgent: String
    ): ByteArray? {
        if (imageUrl.startsWith("data:image/")) {
            val commaIdx = imageUrl.indexOf(',')
            if (commaIdx > 0) {
                return try {
                    Base64.decode(imageUrl.substring(commaIdx + 1), Base64.DEFAULT)
                } catch (_: Exception) {
                    null
                }
            }
            return null
        }

        if (!imageUrl.startsWith("http://") && !imageUrl.startsWith("https://")) {
            return null
        }

        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(imageUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = true
                connectTimeout = DOWNLOAD_CONNECT_TIMEOUT_MS
                readTimeout = DOWNLOAD_READ_TIMEOUT_MS
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
                if (pageUrl.isNotBlank()) {
                    setRequestProperty("Referer", pageUrl)
                }
                if (!cookie.isNullOrBlank()) {
                    setRequestProperty("Cookie", cookie)
                }
            }

            if (conn.responseCode !in 200..299) return null

            val bos = ByteArrayOutputStream()
            conn.inputStream.use { input ->
                val buf = ByteArray(16 * 1024)
                var read: Int
                var total = 0
                while (input.read(buf).also { read = it } != -1) {
                    total += read
                    if (total > 25 * 1024 * 1024) return null // Safety cap 25MB per single strip
                    bos.write(buf, 0, read)
                }
            }
            bos.toByteArray()
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun decodeOptimalPanelBitmap(bytes: ByteArray): Bitmap? {
        return try {
            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOpts)
            val rawW = boundsOpts.outWidth
            val rawH = boundsOpts.outHeight
            if (rawW <= 0 || rawH <= 0) return null

            var sampleSize = 1
            while (rawW / (sampleSize * 2) >= MAX_PANEL_WIDTH || (rawH / sampleSize) > 9500) {
                sampleSize *= 2
            }

            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inMutable = true
            }
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
                ?: return null

            if (decoded.width > MAX_PANEL_WIDTH) {
                val scale = MAX_PANEL_WIDTH.toFloat() / decoded.width.toFloat()
                val targetH = (decoded.height * scale).roundToInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(decoded, MAX_PANEL_WIDTH, targetH, true)
                if (scaled !== decoded) decoded.recycle()
                if (!scaled.isMutable) {
                    val mut = scaled.copy(Bitmap.Config.ARGB_8888, true)
                    scaled.recycle()
                    mut
                } else {
                    scaled
                }
            } else if (!decoded.isMutable) {
                val mut = decoded.copy(Bitmap.Config.ARGB_8888, true)
                decoded.recycle()
                mut
            } else {
                decoded
            }
        } catch (_: OutOfMemoryError) {
            null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Runs ML Kit OCR across the panel bitmap. If the panel is a tall vertical Webtoon strip,
     * slices into overlapping vertical chunks so OCR maintains full crisp horizontal resolution.
     */
    private fun runChunkedPanelOcr(
        bitmap: Bitmap,
        langIndex: Int,
        isCancelled: () -> Boolean
    ): List<RawBubbleCandidate>? {
        val recognizer: TextRecognizer = when (langIndex) {
            0 -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
            1 -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
            3 -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            else -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        }

        val candidates = ArrayList<RawBubbleCandidate>()
        try {
            val totalH = bitmap.height
            val totalW = bitmap.width

            if (totalH <= MAX_SINGLE_OCR_HEIGHT) {
                val visionText = Tasks.await(
                    recognizer.process(InputImage.fromBitmap(bitmap, 0)),
                    8,
                    TimeUnit.SECONDS
                )
                collectCandidatesFromVisionText(visionText, bitmap, 0, candidates)
            } else {
                var yTop = 0
                while (yTop < totalH) {
                    if (isCancelled()) return null
                    val sliceH = minOf(CHUNK_HEIGHT, totalH - yTop)
                    if (sliceH < 80) break

                    val sliceBmp = Bitmap.createBitmap(bitmap, 0, yTop, totalW, sliceH)
                    try {
                        val visionText = Tasks.await(
                            recognizer.process(InputImage.fromBitmap(sliceBmp, 0)),
                            8,
                            TimeUnit.SECONDS
                        )
                        collectCandidatesFromVisionText(visionText, bitmap, yTop, candidates)
                    } finally {
                        if (sliceBmp !== bitmap) sliceBmp.recycle()
                    }

                    if (yTop + sliceH >= totalH) break
                    yTop += (CHUNK_HEIGHT - CHUNK_OVERLAP)
                }
            }
        } catch (_: Exception) {
            return emptyList()
        } finally {
            try {
                recognizer.close()
            } catch (_: Exception) {
            }
        }
        return candidates
    }

    private fun collectCandidatesFromVisionText(
        visionText: Text,
        fullBitmap: Bitmap,
        yOffset: Int,
        out: MutableList<RawBubbleCandidate>
    ) {
        val w = fullBitmap.width
        val h = fullBitmap.height

        for (block in visionText.textBlocks) {
            val rawBox = block.boundingBox ?: continue
            val box = Rect(
                rawBox.left.coerceIn(0, w),
                (rawBox.top + yOffset).coerceIn(0, h),
                rawBox.right.coerceIn(0, w),
                (rawBox.bottom + yOffset).coerceIn(0, h)
            )
            if (box.width() < 12 || box.height() < 12) continue

            val cleanText = comicTranslator.extractCleanBlockText(block)
            val isCjkOrHangul = comicTranslator.containsJapaneseOrChinese(cleanText) ||
                comicTranslator.containsHangul(cleanText)
            val letterCount = cleanText.count { it.isLetter() }
            if (isCjkOrHangul) {
                if (letterCount < 1) continue
            } else {
                if (letterCount < 2) continue
            }

            if (isWatermarkOrUrl(cleanText)) continue

            val bubbleBox = SpeechBubbleDetector.detectBubbleBounds(fullBitmap, box)
            out.add(RawBubbleCandidate(box, bubbleBox, cleanText))
        }
    }

    private fun mergeSameBubbleCandidates(items: List<RawBubbleCandidate>): List<RawBubbleCandidate> {
        if (items.size <= 1) return items

        // First deduplicate near-identical boxes from overlapping Webtoon chunk boundaries
        val unique = ArrayList<RawBubbleCandidate>(items.size)
        for (cand in items) {
            val dupIdx = unique.indexOfFirst { existing ->
                val interLeft = maxOf(existing.boundingBox.left, cand.boundingBox.left)
                val interTop = maxOf(existing.boundingBox.top, cand.boundingBox.top)
                val interRight = minOf(existing.boundingBox.right, cand.boundingBox.right)
                val interBottom = minOf(existing.boundingBox.bottom, cand.boundingBox.bottom)
                if (interRight <= interLeft || interBottom <= interTop) {
                    false
                } else {
                    val interArea = (interRight - interLeft).toFloat() * (interBottom - interTop).toFloat()
                    val minArea = minOf(
                        existing.boundingBox.width() * existing.boundingBox.height(),
                        cand.boundingBox.width() * cand.boundingBox.height()
                    ).coerceAtLeast(1).toFloat()
                    interArea / minArea > 0.65f
                }
            }
            if (dupIdx >= 0) {
                if (cand.cleanText.length > unique[dupIdx].cleanText.length) {
                    unique[dupIdx] = cand
                }
            } else {
                unique.add(cand)
            }
        }

        if (unique.size <= 1) return unique

        val n = unique.size
        val parent = IntArray(n) { it }
        fun find(x: Int): Int {
            var r = x
            while (parent[r] != r) r = parent[r]
            var c = x
            while (c != r) {
                val nxt = parent[c]
                parent[c] = r
                c = nxt
            }
            return r
        }
        fun union(a: Int, b: Int) {
            val ra = find(a)
            val rb = find(b)
            if (ra != rb) parent[rb] = ra
        }

        for (i in 0 until n) {
            for (j in i + 1 until n) {
                if (shouldMerge(unique[i], unique[j])) {
                    union(i, j)
                }
            }
        }

        val groups = LinkedHashMap<Int, MutableList<RawBubbleCandidate>>()
        for (i in 0 until n) {
            groups.getOrPut(find(i)) { ArrayList() }.add(unique[i])
        }

        val merged = ArrayList<RawBubbleCandidate>(groups.size)
        for (group in groups.values) {
            if (group.size == 1) {
                merged.add(group[0])
                continue
            }
            val isVertical = group.count {
                it.boundingBox.height() > it.boundingBox.width() * 1.35
            } > group.size / 2
            val sorted = if (isVertical) {
                group.sortedWith(
                    compareByDescending<RawBubbleCandidate> { it.boundingBox.centerX() }
                        .thenBy { it.boundingBox.centerY() }
                )
            } else {
                group.sortedWith(
                    compareBy<RawBubbleCandidate> { it.boundingBox.centerY() }
                        .thenBy { it.boundingBox.centerX() }
                )
            }

            val uBox = Rect(sorted[0].boundingBox)
            val uBubble = Rect(sorted[0].bubbleBox)
            val parts = ArrayList<String>(sorted.size)
            for (g in sorted) {
                uBox.union(g.boundingBox)
                uBubble.union(g.bubbleBox)
                parts.add(g.cleanText)
            }
            merged.add(RawBubbleCandidate(uBox, uBubble, comicTranslator.joinBubbleParts(parts)))
        }
        return merged
    }

    private fun shouldMerge(a: RawBubbleCandidate, b: RawBubbleCandidate): Boolean {
        val boxA = a.boundingBox
        val boxB = b.boundingBox
        if (Rect.intersects(boxA, boxB)) return true

        val bubbleContainment = containmentRatio(a.bubbleBox, b.bubbleBox)
        val horizOverlap = minOf(boxA.right, boxB.right) - maxOf(boxA.left, boxB.left)
        val minWidth = minOf(boxA.width(), boxB.width()).coerceAtLeast(1)
        val vertGap = maxOf(boxA.top, boxB.top) - minOf(boxA.bottom, boxB.bottom)
        val maxHeight = maxOf(boxA.height(), boxB.height()).coerceAtLeast(1)

        if (horizOverlap > minWidth * 0.35f && vertGap < maxHeight * 0.70f && bubbleContainment > 0.35f) {
            return true
        }

        val vertOverlap = minOf(boxA.bottom, boxB.bottom) - maxOf(boxA.top, boxB.top)
        val minHeight = minOf(boxA.height(), boxB.height()).coerceAtLeast(1)
        val horizGap = maxOf(boxA.left, boxB.left) - minOf(boxA.right, boxB.right)
        val maxWidth = maxOf(boxA.width(), boxB.width()).coerceAtLeast(1)
        if (vertOverlap > minHeight * 0.35f && horizGap < maxWidth * 0.85f && bubbleContainment > 0.35f) {
            return true
        }

        if (bubbleContainment > 0.65f && vertGap < 65 && horizGap < 65) {
            return true
        }
        return false
    }

    private fun containmentRatio(a: Rect, b: Rect): Float {
        val il = maxOf(a.left, b.left)
        val it = maxOf(a.top, b.top)
        val ir = minOf(a.right, b.right)
        val ib = minOf(a.bottom, b.bottom)
        if (ir <= il || ib <= it) return 0f
        val inter = (ir - il).toFloat() * (ib - it).toFloat()
        val minA = minOf(a.width() * a.height(), b.width() * b.height()).coerceAtLeast(1).toFloat()
        return inter / minA
    }

    /**
     * Paints solid 100% pure white speech bubble patches + crisp bold Indonesian dialogue
     * directly onto [bitmap] at image-proportional resolution.
     */
    private fun bakeTranslationsOntoBitmap(
        bitmap: Bitmap,
        blocks: List<TranslatedBlock>,
        bgStyle: Int,
        userTextScale: Float
    ) {
        val canvas = Canvas(bitmap)
        val imgW = bitmap.width.toFloat()
        val imgH = bitmap.height.toFloat()
        // Scale font metrics proportionally to panel pixel width (reference = 900px wide panel)
        val resScale = (imgW / 900f).coerceIn(0.55f, 1.5f) * userTextScale.coerceIn(0.75f, 1.35f)

        val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
            alpha = 255
        }
        val subtleBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(40, 0, 0, 0)
            style = Paint.Style.STROKE
            strokeWidth = (1.4f * resScale).coerceAtLeast(1f)
        }
        val fillPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.FILL
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }
        val strokePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }

        data class BakedItem(
            val fillLayout: StaticLayout,
            val strokeLayout: StaticLayout,
            val drawX: Float,
            var drawY: Float,
            val patchRect: RectF,
            val cornerRadius: Float
        )

        val built = ArrayList<BakedItem>(blocks.size)
        val padOrig = (11f * resScale).coerceAtLeast(7f)

        for (block in blocks) {
            val text = block.translatedText.trim()
            if (text.isEmpty()) continue

            val box = block.bubbleBox ?: block.boundingBox
            val availW = maxOf(box.width() * 0.90f, 95f * resScale)
                .toInt()
                .coerceAtMost((imgW - 16f).toInt().coerceAtLeast(80))
            val availH = maxOf(box.height() * 0.90f, 36f * resScale)

            val minSize = (11f * resScale).coerceAtLeast(9f)
            val maxSize = (28f * resScale).coerceAtMost(38f)
            var tSize = minOf(maxSize, maxOf(14.5f * resScale, box.height() * 0.34f * resScale))
            var chosenFill: StaticLayout? = null

            while (tSize >= minSize) {
                fillPaint.textSize = tSize
                val layout = StaticLayout.Builder.obtain(text, 0, text.length, fillPaint, availW)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER)
                    .setIncludePad(false)
                    .build()
                chosenFill = layout
                if (layout.height <= availH) break
                tSize -= (1.2f * resScale).coerceAtLeast(1f)
            }

            val finalFill = chosenFill ?: continue
            val itemFillPaint = TextPaint(fillPaint).apply { textSize = tSize }
            val finalFillOwn = StaticLayout.Builder.obtain(text, 0, text.length, itemFillPaint, finalFill.width)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setIncludePad(false)
                .build()

            val itemStrokePaint = TextPaint(strokePaint).apply {
                textSize = tSize
                strokeWidth = maxOf(3.8f * resScale, tSize * 0.24f)
            }
            val finalStrokeOwn = StaticLayout.Builder.obtain(text, 0, text.length, itemStrokePaint, finalFillOwn.width)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setIncludePad(false)
                .build()

            val lw = finalFillOwn.width.toFloat()
            val lh = finalFillOwn.height.toFloat()
            val cx = box.centerX().toFloat()
            val cy = box.centerY().toFloat()

            val rawX = (cx - lw * 0.5f).coerceIn(6f, (imgW - lw - 6f).coerceAtLeast(6f))
            val rawY = (cy - lh * 0.5f).coerceIn(6f, (imgH - lh - 6f).coerceAtLeast(6f))

            val origRect = RectF(block.boundingBox).apply { inset(-padOrig, -padOrig) }
            val textRect = RectF(
                rawX - 9f * resScale,
                rawY - 7f * resScale,
                rawX + lw + 9f * resScale,
                rawY + lh + 7f * resScale
            )
            val patch = RectF(origRect).apply {
                union(textRect)
                if (block.bubbleBox != null) {
                    val bRect = RectF(block.bubbleBox)
                    if (bRect.width() <= imgW * 0.80f && bRect.height() <= 900f * resScale) {
                        union(bRect)
                    }
                }
            }
            patch.left = patch.left.coerceAtLeast(2f)
            patch.top = patch.top.coerceAtLeast(2f)
            patch.right = patch.right.coerceAtMost(imgW - 2f)
            patch.bottom = patch.bottom.coerceAtMost(imgH - 2f)

            val centeredX = (patch.centerX() - lw * 0.5f)
                .coerceIn(patch.left + 4f, (patch.right - lw - 4f).coerceAtLeast(patch.left + 4f))
            val centeredY = (patch.centerY() - lh * 0.5f)
                .coerceIn(patch.top + 3f, (patch.bottom - lh - 3f).coerceAtLeast(patch.top + 3f))
            val radius = minOf(20f * resScale, minOf(patch.width(), patch.height()) * 0.28f).coerceAtLeast(8f)

            built.add(BakedItem(finalFillOwn, finalStrokeOwn, centeredX, centeredY, patch, radius))
        }

        // Deduplicate stacking boxes inside the panel
        val sorted = built.sortedBy { it.patchRect.top }
        val cleanList = ArrayList<BakedItem>(sorted.size)
        for (cand in sorted) {
            val overlaps = cleanList.any { ex ->
                val dist = hypot(
                    (ex.patchRect.centerX() - cand.patchRect.centerX()).toDouble(),
                    (ex.patchRect.centerY() - cand.patchRect.centerY()).toDouble()
                )
                dist < 32.0 * resScale
            }
            if (!overlaps) cleanList.add(cand)
        }

        for (item in cleanList) {
            if (bgStyle == OverlayView.STYLE_SEAMLESS_WHITE) {
                canvas.drawRoundRect(item.patchRect, item.cornerRadius, item.cornerRadius, whitePaint)
                canvas.drawRoundRect(item.patchRect, item.cornerRadius, item.cornerRadius, subtleBorderPaint)
                canvas.save()
                canvas.translate(item.drawX, item.drawY)
                item.fillLayout.draw(canvas)
                canvas.restore()
            } else {
                canvas.save()
                canvas.translate(item.drawX, item.drawY)
                item.strokeLayout.draw(canvas)
                item.fillLayout.draw(canvas)
                canvas.restore()
            }
        }
    }

    private fun isWatermarkOrUrl(text: String): Boolean {
        val lower = text.lowercase()
        return lower.contains("http://") ||
            lower.contains("https://") ||
            lower.contains("www.") ||
            lower.contains(".com") ||
            lower.contains(".net") ||
            lower.contains(".org") ||
            lower.contains(".xyz") ||
            lower.contains("asurascans") ||
            lower.contains("reaperscans")
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(32)
        for (i in 0 until 16) {
            sb.append(String.format("%02x", digest[i]))
        }
        return sb.toString()
    }
}
