package com.longnguyen.inkdiary

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.View
import androidx.core.content.res.ResourcesCompat
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
        typeface = ResourcesCompat.getFont(context, R.font.kalam_regular) ?: Typeface.create("cursive", Typeface.NORMAL)
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
                // Soft settle only — NEVER root-level GC here.
                // GC while any residual remains re-flashes the previous AI answer on Boox.
                try {
                    EpdController.setViewDefaultUpdateMode(this@HandwrittenReplyView, UpdateMode.DU)
                    invalidate()
                    Log.d("HandwrittenReplyView", "Animation complete: DU settle")
                } catch (e: Exception) {
                    Log.e("HandwrittenReplyView", "settle failed: ${e.message}")
                }
            }
        }
    }

    init {
        // Always opaque white so e-ink residual cannot show through empty frames.
        setBackgroundColor(Color.WHITE)
        try {
            // Enable high-performance updates for this view
            EpdController.enablePost(this, 1)
        } catch (e: Exception) {}
    }

    fun setTextAndAnimate(text: String) {
        // Stop any running animations safely
        animationHandler.removeCallbacksAndMessages(null)

        setBackgroundColor(Color.WHITE)

        this.fullText = text
        this.displayedText = ""
        this.wordsList = text.split(" ").filter { it.isNotEmpty() }
        this.currentWordIndex = 0
        this.lines.clear()
        this.alpha = 1f // Keep opacity at 100% permanently to prevent E-ink fade glitches
        visibility = View.VISIBLE

        if (width > 0) {
            animationHandler.post(typewriterRunnable)
        } else {
            invalidate()
        }
    }

    /**
     * Blank to white without GC. GC re-exposes residual previous-answer pixels on Boox.
     */
    fun coverWithWhite() {
        animationHandler.removeCallbacksAndMessages(null)
        fullText = ""
        displayedText = ""
        wordsList = emptyList()
        currentWordIndex = 0
        lines.clear()
        setBackgroundColor(Color.WHITE)
        visibility = View.VISIBLE
        try {
            EpdController.setViewDefaultUpdateMode(this, UpdateMode.DU)
        } catch (e: Exception) {}
        invalidate()
    }

    /**
     * Show text with typewriter. Does NOT GC first — the pen surface white-wipe
     * is responsible for killing residual. A GC here was flashing the previous answer.
     */
    fun showTextClean(text: String, startDelayMs: Long = 40L) {
        coverWithWhite()
        animationHandler.postDelayed({
            if (visibility != View.VISIBLE) return@postDelayed
            setTextAndAnimate(text)
        }, startDelayMs)
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

        // Explicit white fill so residual black pixels are overwritten on e-ink.
        canvas.drawColor(Color.WHITE)

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
        coverWithWhite()
    }

    /** Fully stop animations and blank content without scheduling further GC work. */
    fun resetContent() {
        animationHandler.removeCallbacksAndMessages(null)
        fullText = ""
        displayedText = ""
        wordsList = emptyList()
        currentWordIndex = 0
        lines.clear()
        setBackgroundColor(Color.WHITE)
    }

    fun hasContent(): Boolean = fullText.isNotEmpty() || displayedText.isNotEmpty()
}
