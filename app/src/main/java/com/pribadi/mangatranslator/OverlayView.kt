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
        const val STYLE_SEAMLESS_WHITE = 1   // 100% Solid Pure White bubble covering original manga bubble
    }

    private class PreparedBlock(
        val block: TranslatedBlock,
        val fillLayout: StaticLayout,
        val strokeLayout: StaticLayout,
        val fillPaint: TextPaint,
        val strokePaint: TextPaint,
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

    // Full physical display dimensions matching MediaProjection capture coordinates
    @Volatile
    var fullScreenWidth: Int = 0
    @Volatile
    var fullScreenHeight: Int = 0

    // Reused scratch array to cancel any status-bar/cutout window inset offset to 0.0px error
    private val windowLocation = IntArray(2)

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

    // 100% opaque pure solid white paint to completely cover original manga bubbles
    private val solidWhiteBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        alpha = 255
    }

    // Crisp subtle comic edge around white bubble so it stays sharp over bright panels too
    private val solidBubbleEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(45, 0, 0, 0)
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }

    fun setScreenDimensions(screenW: Int, screenH: Int) {
        if (screenW > 0 && screenH > 0) {
            fullScreenWidth = screenW
            fullScreenHeight = screenH
        }
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
            rebuildPreparedBlocks()
        }
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean = false

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        var snapshot = preparedBlocks
        if (snapshot.isEmpty()) return

        val screenWidth = effectiveWidth()
        val screenHeight = effectiveHeight()

        prevRenderedShiftY = renderedShiftY
        val target = targetShiftY
        val diff = target - renderedShiftY
        if (abs(diff) <= 0.5f) {
            renderedShiftY = target
        } else {
            renderedShiftY += diff * 0.86f
            postInvalidateOnAnimation()
        }

        pruneScrolledPastBlocks(renderedShiftY)
        snapshot = preparedBlocks
        if (snapshot.isEmpty()) return

        // Align Canvas origin EXACTLY to physical display (0, 0) matching MediaProjection!
        // Cancels any OEM status-bar or camera-cutout window offset so white bubbles land 1:1 on manga bubbles.
        getLocationOnScreen(windowLocation)
        val offsetX = -windowLocation[0].toFloat()
        val offsetY = -windowLocation[1].toFloat()

        val topClip = screenHeight * 0.045f
        val bottomClip = screenHeight * 0.945f
        val currentStyle = bgStyle

        canvas.save()
        canvas.translate(offsetX, offsetY)
        canvas.clipRect(0f, topClip, screenWidth, bottomClip)

        for (item in snapshot) {
            val deltaY = renderedShiftY - item.block.captureShiftY
            val curTop = item.basePatchRect.top + deltaY
            val curBottom = item.basePatchRect.bottom + deltaY
            if (curBottom <= topClip || curTop >= bottomClip) continue

            canvas.save()
            canvas.translate(0f, deltaY)

            if (currentStyle == STYLE_SEAMLESS_WHITE) {
                // Strictly 100% OPAQUE PURE SOLID WHITE (#FFFFFFFF) covering the entire original manga bubble!
                solidWhiteBgPaint.color = Color.WHITE
                solidWhiteBgPaint.alpha = 255
                canvas.drawRoundRect(
                    item.basePatchRect,
                    item.cornerRadius,
                    item.cornerRadius,
                    solidWhiteBgPaint
                )
                canvas.drawRoundRect(
                    item.basePatchRect,
                    item.cornerRadius,
                    item.cornerRadius,
                    solidBubbleEdgePaint
                )

                item.fillPaint.color = Color.BLACK
                item.fillPaint.alpha = 255
                canvas.translate(item.baseDrawX, item.baseDrawY)
                item.fillLayout.draw(canvas)
            } else {
                item.strokePaint.alpha = 255
                item.fillPaint.alpha = 255
                canvas.translate(item.baseDrawX, item.baseDrawY)
                item.strokeLayout.draw(canvas)
                item.fillLayout.draw(canvas)
            }

            canvas.restore()
        }

        canvas.restore()
    }

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

        // Separate slightly touching vertical neighbors cleanly
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

        val maxAvailWidth = (screenWidth - 32f).toInt().coerceAtLeast(110)
        val availableWidth = maxOf((box.width() * 0.90f), 115f)
            .toInt()
            .coerceAtMost(maxAvailWidth)
        val availableHeight = maxOf((box.height() * 0.90f), 46f)

        val fillPaint = TextPaint(baseFillPaint).apply {
            color = Color.BLACK
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
            this.color = Color.WHITE
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
        val baseDrawX = rawDrawX.coerceIn(12f, (screenWidth - layoutWidth - 12f).coerceAtLeast(12f))
        val baseDrawY = centerY - (layoutHeight / 2f)

        // Cover 100% of:
        // 1) Original foreign text boundingBox (generous 14px margin on all sides so zero strokes peek out)
        // 2) Detected original manga speech bubble interior (bubbleBox)
        // 3) Translated Indonesian text layout box (padded cleanly)
        val origRect = RectF(block.boundingBox).apply {
            inset(-14f, -14f)
        }
        val textRect = RectF(
            baseDrawX - 12f,
            baseDrawY - 10f,
            baseDrawX + layoutWidth + 12f,
            baseDrawY + layoutHeight + 10f
        )
        val basePatchRect = RectF(origRect).apply {
            union(textRect)
            if (block.bubbleBox != null) {
                val bRect = RectF(block.bubbleBox)
                // Expand inside the detected manga speech bubble so the original bubble is cleanly covered
                if (bRect.width() <= screenWidth * 0.78f && bRect.height() <= effectiveHeight() * 0.45f) {
                    union(bRect)
                }
            }
        }

        basePatchRect.left = basePatchRect.left.coerceAtLeast(4f)
        basePatchRect.right = basePatchRect.right.coerceAtMost(screenWidth - 4f)

        // Re-center Indonesian text nicely inside the final white bubble patch
        val centeredDrawX = (basePatchRect.centerX() - layoutWidth * 0.5f)
            .coerceIn(basePatchRect.left + 6f, (basePatchRect.right - layoutWidth - 6f).coerceAtLeast(basePatchRect.left + 6f))
        val centeredDrawY = (basePatchRect.centerY() - layoutHeight * 0.5f)
            .coerceIn(basePatchRect.top + 4f, (basePatchRect.bottom - layoutHeight - 4f).coerceAtLeast(basePatchRect.top + 4f))

        val cornerRadius = minOf(24f, minOf(basePatchRect.width(), basePatchRect.height()) * 0.30f)
            .coerceAtLeast(10f)

        return PreparedBlock(
            block = block,
            fillLayout = fillLayout,
            strokeLayout = strokeLayout,
            fillPaint = fillPaint,
            strokePaint = strokePaint,
            baseDrawX = centeredDrawX,
            baseDrawY = centeredDrawY,
            basePatchRect = basePatchRect,
            cornerRadius = cornerRadius
        )
    }

    private fun effectiveWidth(): Float {
        return when {
            fullScreenWidth > 0 -> fullScreenWidth.toFloat()
            width > 0 -> width.toFloat()
            else -> resources.displayMetrics.widthPixels.toFloat()
        }
    }

    private fun effectiveHeight(): Float {
        return when {
            fullScreenHeight > 0 -> fullScreenHeight.toFloat()
            height > 0 -> height.toFloat()
            else -> resources.displayMetrics.heightPixels.toFloat()
        }
    }
}
