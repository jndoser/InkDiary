package com.longnguyen.inkdiary

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.api.device.epd.UpdateMode

class HandwrittenReplyView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var fullText: String = ""
    private var displayedText: String = ""
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 42f
        // Try to use a handwriting-like system font
        typeface = Typeface.create("cursive", Typeface.NORMAL)
        strokeWidth = 1.1f
        style = Paint.Style.FILL_AND_STROKE
    }

    private var animator: ValueAnimator? = null
    private val lines = mutableListOf<String>()

    fun setTextAndAnimate(text: String) {
        android.util.Log.d("HandwrittenReplyView", "setTextAndAnimate: $text")
        this.fullText = text
        this.displayedText = ""
        lines.clear()
        
        animator?.cancel()

        // Pre-calculate lines for the full text once to avoid O(N^2) measurements during animation
        if (width > 0) {
            prepareLines()
        }

        if (isLaidOut && width > 0) {
            startAnimation()
        } else {
            addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                override fun onLayoutChange(
                    v: View, left: Int, top: Int, right: Int, bottom: Int,
                    oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int
                ) {
                    v.removeOnLayoutChangeListener(this)
                    prepareLines()
                    startAnimation()
                }
            })
        }
    }

    private fun prepareLines() {
        lines.clear()
        val words = fullText.split(" ")
        val padding = 60f
        val maxWidth = width - padding * 2
        if (maxWidth <= 0) return

        var currentLine = StringBuilder()
        var currentLineWidth = 0f

        for (word in words) {
            val wordWithSpace = if (currentLine.isEmpty()) word else " $word"
            val wordWidth = textPaint.measureText(wordWithSpace)
            
            if (currentLineWidth + wordWidth > maxWidth && currentLine.isNotEmpty()) {
                lines.add(currentLine.toString())
                currentLine = StringBuilder(word)
                currentLineWidth = textPaint.measureText(word)
            } else {
                currentLine.append(wordWithSpace)
                currentLineWidth += wordWidth
            }
        }
        if (currentLine.isNotEmpty()) {
            lines.add(currentLine.toString())
        }
    }

    private fun startAnimation() {
        if (fullText.isEmpty()) return
        
        // Use a slightly slower duration for E-ink stability: 60ms per char
        val duration = (fullText.length * 60L).coerceIn(500L, 8000L)

        animator = ValueAnimator.ofInt(0, fullText.length).apply {
            this.duration = duration
            interpolator = LinearInterpolator()
            addUpdateListener { animation ->
                val index = animation.animatedValue as Int
                if (index > displayedText.length) {
                    displayedText = fullText.substring(0, index)
                    // No more calculateLines() here!
                    invalidate()
                }
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (displayedText.isEmpty()) return

        canvas.save()
        canvas.rotate(-0.5f, width / 2f, height / 2f)

        val padding = 60f
        var y = 150f
        
        // Only draw the portion of lines that corresponds to displayedText length
        var charsToDraw = displayedText.length
        
        for (line in lines) {
            if (charsToDraw <= 0) break
            
            val lineText = if (line.length <= charsToDraw) {
                line
            } else {
                line.substring(0, charsToDraw)
            }
            
            canvas.drawText(lineText, padding, y, textPaint)
            y += textPaint.fontSpacing * 1.2f
            charsToDraw -= (line.length + 1) // +1 for the space we split on
        }
        
        canvas.restore()
    }

    fun clear() {
        animator?.cancel()
        fullText = ""
        displayedText = ""
        lines.clear()
        invalidate()
        // Removed EpdController.refreshScreen GC to prevent lag on new input
    }
}
