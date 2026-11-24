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
        const val STYLE_SEAMLESS_WHITE = 1   // Solid high-opacity background covering original text
    }

    private class PreparedBlock(
        val block: TranslatedBlock,
        val fillLayout: StaticLayout,
        val strokeLayout: StaticLayout,
        val fillPaint: TextPaint,
        val strokePaint: TextPaint,
        val bgColor: Int,
        val isLightBg: Boolean,
        val baseDrawX: Float,
        var baseDrawY: Float,
        val basePatchRect: RectF,
        val cornerRadius: Float
    )

    private val blocksLock = Any()

    @Volatile
    private var preparedBlocks: List<PreparedBlock> = emptyList()

    @Volatile
    var bgStyle: Int = STYLE_SEAMLESS_WHITE

    @Volatile
    var textScale: Float = 1.0f
        private set

    @Volatile
    var targetShiftY: Float = 0f
        private set

    @Volatile
    var velocityPxPerMs: Float = 0f
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
        color = Color.rgb(17, 17, 17)
        style = Paint.Style.FILL
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        isAntiAlias = true
    }

    private val solidBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        alpha = 255
    }

    fun setBackgroundStyle(newStyle: Int) {
        if (bgStyle == newStyle) return
        bgStyle = newStyle
        rebuildPreparedBlocks()
    }

    fun setTextScale(newScale: Float) {
        val clamped = newScale.coerceIn(0.75f, 1.35f)
        if (abs(textScale - clamped) < 0.01f) return
        textScale = clamped
        rebuildPreparedBlocks()
    }

    private fun rebuildPreparedBlocks() {
        val viewWidth = effectiveWidth()
        val viewHeight = effectiveHeight()
        synchronized(blocksLock) {
            if (preparedBlocks.isNotEmpty()) {
                val rebuilt = preparedBlocks.mapNotNull { prepareBlock(it.block, viewWidth) }
                preparedBlocks = resolveOverlapsAndPrune(rebuilt, viewHeight, targetShiftY)
            }
        }
        postInvalidateOnAnimation()
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
        pruneScrolledPastBlocks(newShiftY)
        if (abs(newShiftY - renderedShiftY) > 0.2f) {
            postInvalidateOnAnimation()
        }
    }

    fun resetScrollState(initialShiftY: Float = 0f) {
        targetShiftY = initialShiftY
        velocityPxPerMs = 0f
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

    /**
     * Permanently removes any block once it has completely scrolled past the top or bottom
     * reading boundary so scrolled-past bubbles disappear and never stack or reappear.
     */
    private fun pruneScrolledPastBlocks(atShiftY: Float) {
        val snapshot = preparedBlocks
        if (snapshot.isEmpty()) return

        val screenHeight = effectiveHeight()
        val topExitThreshold = screenHeight * 0.055f
        val bottomExitThreshold = screenHeight * 0.94f
        val maxTravel = screenHeight * 0.85f

        var anyRemoved = false
        for (item in snapshot) {
            val deltaY = atShiftY - item.block.captureShiftY
            val curTop = item.basePatchRect.top + deltaY
            val curBottom = item.basePatchRect.bottom + deltaY
            if (curBottom < topExitThreshold || curTop > bottomExitThreshold || abs(deltaY) > maxTravel) {
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
                    curBottom >= topExitThreshold &&
                        curTop <= bottomExitThreshold &&
                        abs(deltaY) <= maxTravel
                }
            }
        }
    }

    /**
     * Returns swept screen-space bounding rectangles covering both previous/rendered and
     * current/target scroll offsets so [ScrollTracker] ignores overlay rendering completely.
     */
    fun getMaskedScreenRects(atShiftY: Float, extraPadding: Int = 14): List<Rect> {
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

        val screenWidth = effectiveWidth()
        val screenHeight = effectiveHeight()

        // Smooth, critically-damped tracking (no velocity overshoot / bounce-back)
        prevRenderedShiftY = renderedShiftY
        val target = targetShiftY
        val diff = target - renderedShiftY
        if (abs(diff) <= 0.5f) {
            renderedShiftY = target
        } else {
            renderedShiftY += diff * 0.85f
            postInvalidateOnAnimation()
        }

        pruneScrolledPastBlocks(renderedShiftY)
        snapshot = preparedBlocks
        if (snapshot.isEmpty()) return

        // Clip cleanly inside system status bar and navigation bar so scrolled bubbles glide out smoothly
        val topClip = screenHeight * 0.045f
        val bottomClip = screenHeight * 0.945f
        val fadeBand = (screenHeight * 0.03f).coerceAtLeast(20f)
        val currentStyle = bgStyle

        canvas.save()
        canvas.clipRect(0f, topClip, screenWidth, bottomClip)

        for (item in snapshot) {
            val deltaY = renderedShiftY - item.block.captureShiftY
            val curTop = item.basePatchRect.top + deltaY
            val curBottom = item.basePatchRect.bottom + deltaY
            if (curBottom <= topClip || curTop >= bottomClip) continue

            // Keep 100% solid opacity (alpha = 255) across the reading area; only fade gently
            // right as the bubble exits past the very top/bottom edge.
            val topVis = ((curBottom - topClip) / fadeBand).coerceIn(0f, 1f)
            val botVis = ((bottomClip - curTop) / fadeBand).coerceIn(0f, 1f)
            val alpha = (minOf(topVis, botVis) * 255f).roundToInt().coerceIn(0, 255)
            if (alpha <= 5) continue

            canvas.save()
            canvas.translate(0f, deltaY)

            if (currentStyle == STYLE_SEAMLESS_WHITE) {
                // High-opacity (100% opaque) solid fill covering all original foreign text
                solidBgPaint.color = item.bgColor
                solidBgPaint.alpha = alpha
                canvas.drawRoundRect(
                    item.basePatchRect,
                    item.cornerRadius,
                    item.cornerRadius,
                    solidBgPaint
                )

                item.fillPaint.alpha = alpha
                canvas.translate(item.baseDrawX, item.baseDrawY)
                item.fillLayout.draw(canvas)
            } else {
                item.strokePaint.alpha = alpha
                item.fillPaint.alpha = alpha
                canvas.translate(item.baseDrawX, item.baseDrawY)
                item.strokeLayout.draw(canvas)
                item.fillLayout.draw(canvas)
            }

            canvas.restore()
        }

        canvas.restore()
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

        val topLimit = screenHeight * 0.045f
        val bottomLimit = screenHeight * 0.945f

        val visible = items.filter { item ->
            val deltaY = atShiftY - item.block.captureShiftY
            val curTop = item.basePatchRect.top + deltaY
            val curBottom = item.basePatchRect.bottom + deltaY
            curBottom >= topLimit + 10f && curTop <= bottomLimit - 10f
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
                if (candidate.block.translatedText.length > existing.block.translatedText.length) {
                    deduplicated[duplicateIdx] = candidate
                }
            } else {
                deduplicated.add(candidate)
            }
        }

        // Separate any slightly touching vertical neighbors so their patches never overlap
        for (i in 0 until deduplicated.size - 1) {
            val upper = deduplicated[i]
            val lower = deduplicated[i + 1]
            val horizOverlap = minOf(upper.basePatchRect.right, lower.basePatchRect.right) -
                maxOf(upper.basePatchRect.left, lower.basePatchRect.left)
            if (horizOverlap > 10f) {
                val verticalOverlap = (upper.basePatchRect.bottom + 4f) - lower.basePatchRect.top
                if (verticalOverlap > 0f && verticalOverlap < 70f) {
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

        val maxAvailWidth = (screenWidth - 28f).toInt().coerceAtLeast(110)
        val availableWidth = maxOf((box.width() * 0.90f), 110f)
            .toInt()
            .coerceAtMost(maxAvailWidth)
        val availableHeight = maxOf((box.height() * 0.90f), 44f)

        val bgColor = block.bubbleBgColor or (0xFF shl 24) // Enforce 100% opaque RGB
        val isLightBg = SpeechBubbleDetector.isLightColor(bgColor)

        val fillPaint = TextPaint(baseFillPaint).apply {
            color = if (bgStyle == STYLE_SEAMLESS_WHITE && !isLightBg) {
                Color.WHITE
            } else {
                Color.rgb(17, 17, 17)
            }
        }

        val scale = textScale
        val minTextSize = (11.5f * scale).coerceAtLeast(10f)
        val maxTextSize = (32f * scale).coerceAtMost(42f)
        var textSize = minOf(maxTextSize, maxOf(16f * scale, box.height() * 0.36f * scale))
        var selectedLayout: StaticLayout? = null

        while (textSize >= minTextSize) {
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
            this.color = if (isLightBg) Color.WHITE else Color.rgb(18, 18, 22)
            this.textSize = textSize
            this.strokeWidth = maxOf(4.5f, textSize * 0.24f)
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
        val baseDrawX = rawDrawX.coerceIn(10f, (screenWidth - layoutWidth - 10f).coerceAtLeast(10f))
        val baseDrawY = centerY - (layoutHeight / 2f)

        // Ensure solid background patch ALWAYS covers 100% of original foreign text + translated text
        val textRect = RectF(
            baseDrawX - 10f,
            baseDrawY - 8f,
            baseDrawX + layoutWidth + 10f,
            baseDrawY + layoutHeight + 8f
        )
        val origRect = RectF(block.boundingBox).apply {
            inset(-10f, -10f)
        }
        val basePatchRect = RectF(origRect).apply { union(textRect) }

        // Keep patch safely inside screen width without ever cutting into original foreign text
        basePatchRect.left = basePatchRect.left.coerceAtLeast(4f)
        basePatchRect.right = basePatchRect.right.coerceAtMost(screenWidth - 4f)

        val cornerRadius = minOf(16f, basePatchRect.height() * 0.25f).coerceAtLeast(8f)

        return PreparedBlock(
            block = block,
            fillLayout = fillLayout,
            strokeLayout = strokeLayout,
            fillPaint = fillPaint,
            strokePaint = strokePaint,
            bgColor = bgColor,
            isLightBg = isLightBg,
            baseDrawX = baseDrawX,
            baseDrawY = baseDrawY,
            basePatchRect = basePatchRect,
            cornerRadius = cornerRadius
        )
    }

    private fun effectiveWidth(): Float {
        return if (width > 0) width.toFloat() else resources.displayMetrics.widthPixels.toFloat()
    }

    private fun effectiveHeight(): Float {
        return if (height > 0) height.toFloat() else resources.displayMetrics.heightPixels.toFloat()
    }
}
