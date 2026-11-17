package com.pribadi.mangatranslator

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        const val STYLE_TRANSPARENT = 0      // No background card, comic outline halo
        const val STYLE_SEAMLESS_WHITE = 1   // Seamless white bubble patch (no border)
    }

    private class PreparedBlock(
        val block: TranslatedBlock,
        val fillLayout: StaticLayout,
        val strokeLayout: StaticLayout,
        val fillPaint: TextPaint,
        val strokePaint: TextPaint,
        val baseDrawX: Float,
        var baseDrawY: Float,
        val basePatchRect: RectF
    )

    private val blocksLock = Any()

    @Volatile
    private var preparedBlocks: List<PreparedBlock> = emptyList()

    var bgStyle: Int = STYLE_TRANSPARENT

    @Volatile
    var targetShiftY: Float = 0f
        private set

    @Volatile
    var velocityPxPerMs: Float = 0f
        private set

    @Volatile
    var lastUpdateNano: Long = 0L
        private set

    @Volatile
    private var renderedShiftY: Float = 0f

    @Volatile
    private var prevRenderedShiftY: Float = 0f

    private val baseStrokePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        isAntiAlias = true
    }

    private val baseFillPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.FILL
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        isAntiAlias = true
    }

    private val seamlessWhitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    /**
     * Replaces all displayed blocks with [newBlocks], deduplicating any overlapping bubbles
     * so bubbles never stack on top of each other.
     */
    fun setTranslatedBlocks(newBlocks: List<TranslatedBlock>) {
        val viewWidth = effectiveWidth()
        val viewHeight = effectiveHeight()
        val rawBuilt = newBlocks.mapNotNull { prepareBlock(it, viewWidth) }
        val resolved = resolveOverlapsAndPrune(rawBuilt, viewHeight, targetShiftY)
        synchronized(blocksLock) {
            preparedBlocks = resolved
        }
        postInvalidateOnAnimation()
    }

    fun updateScrollState(newShiftY: Float, velocity: Float) {
        targetShiftY = newShiftY
        velocityPxPerMs = velocity
        lastUpdateNano = System.nanoTime()
        pruneScrolledPastBlocks(newShiftY)
        if (abs(newShiftY - renderedShiftY) > 0.15f) {
            postInvalidateOnAnimation()
        }
    }

    fun resetScrollState(initialShiftY: Float = 0f) {
        targetShiftY = initialShiftY
        velocityPxPerMs = 0f
        lastUpdateNano = System.nanoTime()
        renderedShiftY = initialShiftY
        prevRenderedShiftY = initialShiftY
    }

    fun clear() {
        synchronized(blocksLock) {
            preparedBlocks = emptyList()
        }
        postInvalidate()
    }

    fun hasBlocks(): Boolean = preparedBlocks.isNotEmpty()

    fun hasVisibleBlocksAt(atShiftY: Float): Boolean {
        val snapshot = preparedBlocks
        if (snapshot.isEmpty()) return false
        val screenHeight = effectiveHeight()
        val topLimit = screenHeight * 0.045f
        val bottomLimit = screenHeight * 0.945f
        for (item in snapshot) {
            val deltaY = atShiftY - item.block.captureShiftY
            val curTop = item.basePatchRect.top + deltaY
            val curBottom = item.basePatchRect.bottom + deltaY
            if (curTop >= topLimit && curBottom <= bottomLimit) {
                return true
            }
        }
        return false
    }

    /**
     * Permanently removes any block that has scrolled past the visible top/bottom reading area
     * so scrolled-past bubbles disappear immediately and never stack or come back.
     */
    private fun pruneScrolledPastBlocks(atShiftY: Float) {
        val snapshot = preparedBlocks
        if (snapshot.isEmpty()) return

        val screenHeight = effectiveHeight()
        val topLimit = screenHeight * 0.04f
        val bottomLimit = screenHeight * 0.95f
        val maxTravel = screenHeight * 0.72f

        var anyRemoved = false
        for (item in snapshot) {
            val deltaY = atShiftY - item.block.captureShiftY
            val curTop = item.basePatchRect.top + deltaY
            val curBottom = item.basePatchRect.bottom + deltaY
            if (curTop < topLimit || curBottom > bottomLimit || abs(deltaY) > maxTravel) {
                anyRemoved = true
                break
            }
        }

        if (anyRemoved) {
            synchronized(blocksLock) {
                preparedBlocks = preparedBlocks.filter { item ->
                    val deltaY = atShiftY - item.block.captureShiftY
                    val curTop = item.basePatchRect.top + deltaY
                    val curBottom = item.basePatchRect.bottom + deltaY
                    curTop >= topLimit && curBottom <= bottomLimit && abs(deltaY) <= maxTravel
                }
            }
        }
    }

    /**
     * Returns swept screen-space bounding rectangles covering both previous/rendered and
     * current/target scroll offsets so [ScrollTracker] ignores overlay rendering completely.
     */
    fun getMaskedScreenRects(atShiftY: Float, extraPadding: Int = 12): List<Rect> {
        val snapshot = preparedBlocks
        if (snapshot.isEmpty()) return emptyList()

        val screenHeight = effectiveHeight()
        val minShift = minOf(renderedShiftY, prevRenderedShiftY, targetShiftY, atShiftY)
        val maxShift = maxOf(renderedShiftY, prevRenderedShiftY, targetShiftY, atShiftY)

        val rects = ArrayList<Rect>(snapshot.size)
        for (item in snapshot) {
            val minDelta = minShift - item.block.captureShiftY
            val maxDelta = maxShift - item.block.captureShiftY
            val sweptTop = (item.basePatchRect.top + minDelta - extraPadding).toInt()
            val sweptBottom = (item.basePatchRect.bottom + maxDelta + extraPadding).toInt()
            if (sweptBottom < 0 || sweptTop > screenHeight) continue

            val sweptLeft = (item.basePatchRect.left - extraPadding).toInt()
            val sweptRight = (item.basePatchRect.right + extraPadding).toInt()
            rects.add(Rect(sweptLeft, sweptTop, sweptRight, sweptBottom))
        }
        return rects
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && w != oldw && preparedBlocks.isNotEmpty()) {
            val widthF = w.toFloat()
            val heightF = if (h > 0) h.toFloat() else effectiveHeight()
            synchronized(blocksLock) {
                val rebuilt = preparedBlocks.mapNotNull { prepareBlock(it.block, widthF) }
                preparedBlocks = resolveOverlapsAndPrune(rebuilt, heightF, targetShiftY)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean = false

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        var snapshot = preparedBlocks
        if (snapshot.isEmpty()) return

        val screenHeight = effectiveHeight()

        prevRenderedShiftY = renderedShiftY
        val nowNano = System.nanoTime()
        val dtMs = ((nowNano - lastUpdateNano) / 1_000_000f).coerceIn(0f, 24f)
        val target = targetShiftY
        val vel = velocityPxPerMs

        if (abs(vel) > 0.06f && dtMs < 20f) {
            val predictedShiftY = target + vel * (dtMs * 0.55f)
            renderedShiftY += (predictedShiftY - renderedShiftY) * 0.80f
            postInvalidateOnAnimation()
        } else {
            val diff = target - renderedShiftY
            if (abs(diff) < 0.35f) {
                renderedShiftY = target
            } else {
                renderedShiftY += diff * 0.72f
                postInvalidateOnAnimation()
            }
        }

        // Permanently prune any block that has scrolled past the top/bottom reading bounds
        pruneScrolledPastBlocks(renderedShiftY)
        snapshot = preparedBlocks
        if (snapshot.isEmpty()) return

        val topFadeZone = screenHeight * 0.065f
        val bottomFadeZone = screenHeight * 0.925f
        val topFadeHalf = (topFadeZone * 0.6f).coerceAtLeast(16f)
        val topFadeRange = (topFadeZone * 0.7f).coerceAtLeast(20f)
        val bottomMarginHalf = ((screenHeight - bottomFadeZone) * 0.6f).coerceAtLeast(16f)
        val bottomFadeRange = ((screenHeight - bottomFadeZone) * 0.7f).coerceAtLeast(20f)

        for (item in snapshot) {
            val deltaY = renderedShiftY - item.block.captureShiftY
            val curTop = item.basePatchRect.top + deltaY
            val curBottom = item.basePatchRect.bottom + deltaY
            if (curBottom < 0f || curTop > screenHeight) continue

            val curCenterY = (curTop + curBottom) * 0.5f
            val topFactor = ((curCenterY - topFadeHalf) / topFadeRange).coerceIn(0f, 1f)
            val bottomFactor = (((screenHeight - bottomMarginHalf) - curCenterY) / bottomFadeRange)
                .coerceIn(0f, 1f)
            val alpha = (minOf(topFactor, bottomFactor) * 255f).roundToInt().coerceIn(0, 255)
            if (alpha <= 5) continue

            canvas.save()
            canvas.translate(0f, deltaY)

            if (bgStyle == STYLE_SEAMLESS_WHITE) {
                seamlessWhitePaint.alpha = alpha
                canvas.drawRoundRect(item.basePatchRect, 10f, 10f, seamlessWhitePaint)
            }

            item.strokePaint.alpha = alpha
            item.fillPaint.alpha = alpha

            canvas.translate(item.baseDrawX, item.baseDrawY)
            item.strokeLayout.draw(canvas)
            item.fillLayout.draw(canvas)
            canvas.restore()
        }
    }

    /**
     * Filters out off-screen blocks, removes duplicate/overlapping blocks that would stack on top
     * of each other, and gently separates vertically touching blocks.
     */
    private fun resolveOverlapsAndPrune(
        items: List<PreparedBlock>,
        screenHeight: Float,
        atShiftY: Float
    ): List<PreparedBlock> {
        if (items.isEmpty()) return emptyList()

        val topLimit = screenHeight * 0.04f
        val bottomLimit = screenHeight * 0.95f

        val visible = items.filter { item ->
            val deltaY = atShiftY - item.block.captureShiftY
            val curTop = item.basePatchRect.top + deltaY
            val curBottom = item.basePatchRect.bottom + deltaY
            curTop >= topLimit && curBottom <= bottomLimit
        }.sortedBy { it.basePatchRect.top }

        if (visible.size <= 1) return visible

        val deduplicated = ArrayList<PreparedBlock>(visible.size)
        for (candidate in visible) {
            var duplicateIdx = -1
            for (i in deduplicated.indices) {
                val existing = deduplicated[i]
                if (arePatchRectsStacking(existing.basePatchRect, candidate.basePatchRect)) {
                    duplicateIdx = i
                    break
                }
            }

            if (duplicateIdx >= 0) {
                val existing = deduplicated[duplicateIdx]
                // Keep the one with longer/more complete translated text
                if (candidate.block.translatedText.length > existing.block.translatedText.length) {
                    deduplicated[duplicateIdx] = candidate
                }
            } else {
                deduplicated.add(candidate)
            }
        }

        // Separate any slightly touching vertical neighbors so their boxes never overlap
        for (i in 0 until deduplicated.size - 1) {
            val upper = deduplicated[i]
            val lower = deduplicated[i + 1]
            val horizOverlap = minOf(upper.basePatchRect.right, lower.basePatchRect.right) -
                maxOf(upper.basePatchRect.left, lower.basePatchRect.left)
            if (horizOverlap > 12f) {
                val verticalOverlap = (upper.basePatchRect.bottom + 4f) - lower.basePatchRect.top
                if (verticalOverlap > 0f && verticalOverlap < 60f) {
                    val push = verticalOverlap * 0.5f
                    upper.baseDrawY -= push
                    upper.basePatchRect.offset(0f, -push)
                    lower.baseDrawY += push
                    lower.basePatchRect.offset(0f, push)
                }
            }
        }

        return deduplicated
    }

    private fun arePatchRectsStacking(a: RectF, b: RectF): Boolean {
        val centerDist = hypot(
            (a.centerX() - b.centerX()).toDouble(),
            (a.centerY() - b.centerY()).toDouble()
        )
        if (centerDist < 42.0) return true

        if (a.contains(b.centerX(), b.centerY()) || b.contains(a.centerX(), a.centerY())) {
            val horizOverlap = minOf(a.right, b.right) - maxOf(a.left, b.left)
            val minWidth = minOf(a.width(), b.width()).coerceAtLeast(1f)
            if (horizOverlap / minWidth > 0.45f) return true
        }

        val interLeft = maxOf(a.left, b.left)
        val interTop = maxOf(a.top, b.top)
        val interRight = minOf(a.right, b.right)
        val interBottom = minOf(a.bottom, b.bottom)
        if (interRight <= interLeft || interBottom <= interTop) return false

        val interArea = (interRight - interLeft) * (interBottom - interTop)
        val minArea = minOf(a.width() * a.height(), b.width() * b.height()).coerceAtLeast(1f)
        return (interArea / minArea) > 0.30f
    }

    private fun prepareBlock(block: TranslatedBlock, screenWidth: Float): PreparedBlock? {
        val text = block.translatedText.trim()
        if (text.isEmpty()) return null

        val box = block.bubbleBox ?: block.boundingBox
        val centerX = box.centerX().toFloat()
        val centerY = box.centerY().toFloat()

        val maxAvailWidth = (screenWidth - 24f).toInt().coerceAtLeast(100)
        val availableWidth = maxOf((box.width() * 0.88f), 100f)
            .toInt()
            .coerceAtMost(maxAvailWidth)
        val availableHeight = maxOf((box.height() * 0.88f), 40f)

        val fillPaint = TextPaint(baseFillPaint)
        var textSize = minOf(30f, maxOf(16f, box.height() * 0.38f))
        var selectedLayout: StaticLayout? = null

        while (textSize >= 12f) {
            fillPaint.textSize = textSize
            val layout = StaticLayout.Builder.obtain(
                text,
                0,
                text.length,
                fillPaint,
                availableWidth
            )
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setIncludePad(false)
                .build()

            selectedLayout = layout
            if (layout.height <= availableHeight) {
                break
            }
            textSize -= 1.5f
        }

        val fillLayout = selectedLayout ?: return null
        val strokePaint = TextPaint(baseStrokePaint).apply {
            this.textSize = textSize
            this.strokeWidth = maxOf(4f, textSize * 0.22f)
        }
        val strokeLayout = StaticLayout.Builder.obtain(
            text,
            0,
            text.length,
            strokePaint,
            fillLayout.width
        )
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .build()

        val layoutWidth = fillLayout.width.toFloat()
        val layoutHeight = fillLayout.height.toFloat()

        val rawDrawX = centerX - (layoutWidth / 2f)
        val baseDrawX = rawDrawX.coerceIn(8f, (screenWidth - layoutWidth - 8f).coerceAtLeast(8f))
        val baseDrawY = centerY - (layoutHeight / 2f)

        val pad = 6f
        val textRect = RectF(
            baseDrawX - pad,
            baseDrawY - pad,
            baseDrawX + layoutWidth + pad,
            baseDrawY + layoutHeight + pad
        )
        val origRect = RectF(block.boundingBox).apply { inset(-4f, -4f) }
        val basePatchRect = RectF(textRect).apply { union(origRect) }

        if (block.bubbleBox != null) {
            val innerBubble = RectF(block.bubbleBox)
            if (innerBubble.width() > 12f && innerBubble.height() > 12f) {
                innerBubble.inset(3f, 3f)
                basePatchRect.left = maxOf(basePatchRect.left, innerBubble.left)
                basePatchRect.top = maxOf(basePatchRect.top, innerBubble.top)
                basePatchRect.right = minOf(basePatchRect.right, innerBubble.right)
                basePatchRect.bottom = minOf(basePatchRect.bottom, innerBubble.bottom)
                basePatchRect.union(textRect)
            }
        }

        return PreparedBlock(
            block = block,
            fillLayout = fillLayout,
            strokeLayout = strokeLayout,
            fillPaint = fillPaint,
            strokePaint = strokePaint,
            baseDrawX = baseDrawX,
            baseDrawY = baseDrawY,
            basePatchRect = basePatchRect
        )
    }

    private fun effectiveWidth(): Float {
        return if (width > 0) width.toFloat() else resources.displayMetrics.widthPixels.toFloat()
    }

    private fun effectiveHeight(): Float {
        return if (height > 0) height.toFloat() else resources.displayMetrics.heightPixels.toFloat()
    }
}
