package com.longnguyen.inkdiary

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
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
        typeface = Typeface.create("cursive", Typeface.NORMAL)
        strokeWidth = 1.1f
        style = Paint.Style.FILL_AND_STROKE
    }

    private val lines = mutableListOf<String>()

    // Typewriter Animation variables
    private val animationHandler = Handler(Looper.getMainLooper())
    private var currentWordIndex = 0
    private var wordsList = listOf<String>()

    // Adjust this to speed up or slow down the typewriter speed (e.g., 100ms-150ms per word feels great on E-ink)
    private val wordDelayMs = 120L

    private val typewriterRunnable = object : Runnable {
        override fun run() {
            if (currentWordIndex < wordsList.size) {
                currentWordIndex++
                // Reconstruct text up to the current word count
                displayedText = wordsList.take(currentWordIndex).joinToString(" ")

                prepareLines()

                // Keep the device in a fast refresh mode while printing text
                try {
                    EpdController.setViewDefaultUpdateMode(this@HandwrittenReplyView, UpdateMode.DU)
                } catch (e: Exception) {}

                invalidate()
                animationHandler.postDelayed(this, wordDelayMs)
            } else {
                // Animation completed! Clear artifacts with a clean high-quality flash
                try {
                    EpdController.setViewDefaultUpdateMode(this@HandwrittenReplyView, UpdateMode.GC)
                    invalidate()
                } catch (e: Exception) {}
            }
        }
    }

    fun setTextAndAnimate(text: String) {
        // Stop any running animations safely
        animationHandler.removeCallbacks(typewriterRunnable)

        this.fullText = text
        this.displayedText = ""
        this.wordsList = text.split(" ").filter { it.isNotEmpty() }
        this.currentWordIndex = 0
        this.alpha = 1f // Keep opacity at 100% permanently to prevent E-ink fade glitches

        if (width > 0) {
            animationHandler.post(typewriterRunnable)
        } else {
            invalidate()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (fullText.isNotEmpty() && displayedText.isEmpty()) {
            animationHandler.post(typewriterRunnable)
        }
    }

    private fun prepareLines() {
        lines.clear()
        val viewWidth = width
        if (displayedText.isEmpty() || viewWidth <= 0) return

        val words = displayedText.split(" ")
        val padding = 60f
        val maxWidth = viewWidth - padding * 2

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

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (displayedText.isEmpty() || lines.isEmpty()) return

        canvas.save()
        canvas.rotate(-0.5f, width / 2f, height / 2f)

        val padding = 60f
        var y = 150f

        for (line in lines) {
            canvas.drawText(line, padding, y, textPaint)
            y += textPaint.fontSpacing * 1.2f
        }

        canvas.restore()
    }

    fun clear() {
        animationHandler.removeCallbacks(typewriterRunnable)
        fullText = ""
        displayedText = ""
        lines.clear()
        // No local GC here, we let the main canvas handle the global GC for better synchronization
        invalidate()
    }
}