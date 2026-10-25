package com.pribadi.mangatranslator

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        const val STYLE_TRANSPARENT = 0     // No background card, comic outline halo
        const val STYLE_SEAMLESS_WHITE = 1   // Seamless white bubble patch (no border)
    }

    private val blocks = mutableListOf<TranslatedBlock>()
    var onDismiss: (() -> Unit)? = null
    var bgStyle: Int = STYLE_TRANSPARENT

    // White outline stroke paint for high legibility over comic art
    private val textStrokePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        isAntiAlias = true
    }

    // Bold black comic dialogue text paint
    private val textFillPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.FILL
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        isAntiAlias = true
    }

    // Seamless white paint for erasing old text inside light bubbles (without any black border)
    private val seamlessWhitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    fun setTranslatedBlocks(newBlocks: List<TranslatedBlock>) {
        blocks.clear()
        blocks.addAll(newBlocks)
        invalidate()
    }

    fun clear() {
        blocks.clear()
        invalidate()
    }

    fun hasBlocks(): Boolean = blocks.isNotEmpty()

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        if (blocks.isNotEmpty() && onDismiss != null) {
            if (event?.action == MotionEvent.ACTION_UP) {
                clear()
                onDismiss?.invoke()
            }
            return true
        }
        return false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (blocks.isEmpty()) return

        val screenWidth = width.toFloat()
        val screenHeight = height.toFloat()

        for (block in blocks) {
            val text = block.translatedText.trim()
            if (text.isEmpty()) continue

            // Use the detected speech bubble box if available, otherwise fallback to bounding box
            val box = block.bubbleBox ?: block.boundingBox

            val centerX = box.centerX().toFloat()
            val centerY = box.centerY().toFloat()

            // Available dimensions inside the comic speech bubble
            // For narrow vertical bubbles, ensure at least 100px width so Latin words can wrap naturally
            val availableWidth = maxOf((box.width() * 0.88f), 100f)
                .toInt()
                .coerceAtMost((screenWidth - 24f).toInt())

            val availableHeight = maxOf((box.height() * 0.88f), 40f)

            // Dynamic font sizing: start from fitting size down to 12f
            var textSize = minOf(30f, maxOf(16f, box.height() * 0.38f))
            var selectedLayout: StaticLayout? = null

            while (textSize >= 12f) {
                textFillPaint.textSize = textSize
                val layout = StaticLayout.Builder.obtain(
                    text,
                    0,
                    text.length,
                    textFillPaint,
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

            val layout = selectedLayout ?: continue
            val layoutWidth = layout.width.toFloat()
            val layoutHeight = layout.height.toFloat()

            // Calculate draw coordinates perfectly centered inside the speech bubble
            val rawDrawX = centerX - (layoutWidth / 2f)
            val rawDrawY = centerY - (layoutHeight / 2f)

            // Clamp inside screen bounds
            val drawX = rawDrawX.coerceIn(8f, screenWidth - layoutWidth - 8f)
            val drawY = rawDrawY.coerceIn(8f, screenHeight - layoutHeight - 8f)

            // Background rendering
            if (bgStyle == STYLE_SEAMLESS_WHITE) {
                // Erase old foreign text inside the bubble with pure white patch (NO border lines)
                val pad = 6f
                val patchRect = RectF(
                    drawX - pad,
                    drawY - pad,
                    drawX + layoutWidth + pad,
                    drawY + layoutHeight + pad
                )
                canvas.drawRoundRect(patchRect, 10f, 10f, seamlessWhitePaint)
            }
            // For STYLE_TRANSPARENT: zero background drawn! (as requested by user)

            // Render Comic Text:
            // 1. White stroke halo outline (masks underlying lines and provides maximum contrast)
            textStrokePaint.textSize = textSize
            textStrokePaint.strokeWidth = maxOf(4f, textSize * 0.22f)
            val strokeLayout = StaticLayout.Builder.obtain(
                text,
                0,
                text.length,
                textStrokePaint,
                layout.width
            )
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setIncludePad(false)
                .build()

            canvas.save()
            canvas.translate(drawX, drawY)
            strokeLayout.draw(canvas)

            // 2. Crisp bold black text fill
            layout.draw(canvas)
            canvas.restore()
        }
    }
}
