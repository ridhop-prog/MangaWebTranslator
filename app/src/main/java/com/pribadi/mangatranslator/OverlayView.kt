package com.pribadi.mangatranslator

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
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

    private val blocks = mutableListOf<TranslatedBlock>()
    var onDismiss: (() -> Unit)? = null

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(245, 255, 255, 255)
        style = Paint.Style.FILL
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }

    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        isAntiAlias = true
    }

    private val padding = 4f
    private val cornerRadius = 8f

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

        for (block in blocks) {
            val box = block.boundingBox
            val text = block.translatedText.trim()
            if (text.isEmpty()) continue

            val initialRectF = RectF(
                box.left.toFloat() - padding,
                box.top.toFloat() - padding,
                box.right.toFloat() + padding,
                box.bottom.toFloat() + padding
            )

            val availableWidth = (initialRectF.width() - (padding * 2)).toInt()
            val availableHeight = initialRectF.height() - (padding * 2)

            if (availableWidth <= 0 || availableHeight <= 0) continue

            // Auto-size font size: start from 36f, decrease down to minimum 16f
            var textSize = 36f
            var selectedLayout: StaticLayout? = null

            while (textSize >= 16f) {
                textPaint.textSize = textSize
                val layout = StaticLayout.Builder.obtain(
                    text,
                    0,
                    text.length,
                    textPaint,
                    availableWidth
                )
                    .setAlignment(Layout.Alignment.ALIGN_CENTER)
                    .setIncludePad(false)
                    .build()

                selectedLayout = layout
                if (layout.height <= availableHeight) {
                    break
                }
                textSize -= 2f
            }

            selectedLayout?.let { layout ->
                // Ensure the background box expands if layout height exceeds available height
                val boxBottom = maxOf(initialRectF.bottom, initialRectF.top + layout.height + (padding * 2))
                val finalRectF = RectF(initialRectF.left, initialRectF.top, initialRectF.right, boxBottom)

                // Draw solid rounded white background
                canvas.drawRoundRect(finalRectF, cornerRadius, cornerRadius, bgPaint)
                // Draw thin black border
                canvas.drawRoundRect(finalRectF, cornerRadius, cornerRadius, borderPaint)

                // Center vertically inside bounding box
                val currentHeight = finalRectF.height() - (padding * 2)
                val verticalOffset = finalRectF.top + padding + maxOf(0f, (currentHeight - layout.height) / 2f)
                val horizontalOffset = finalRectF.left + padding

                canvas.save()
                canvas.translate(horizontalOffset, verticalOffset)
                layout.draw(canvas)
                canvas.restore()
            }
        }
    }
}
