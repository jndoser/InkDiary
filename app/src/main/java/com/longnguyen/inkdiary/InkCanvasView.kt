package com.longnguyen.inkdiary

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import com.google.mlkit.vision.digitalink.Ink
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.api.device.epd.UpdateMode
import com.onyx.android.sdk.pen.RawInputCallback
import com.onyx.android.sdk.pen.TouchHelper
import com.onyx.android.sdk.pen.data.TouchPoint
import com.onyx.android.sdk.pen.data.TouchPointList

/**
 * Step 1 goal: capture raw stylus input (InkSense Plus: pressure + tilt) and
 * redraw it immediately, with NO AI / no ML Kit / no network involved yet.
 */
class InkCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var touchHelper: TouchHelper? = null
    private var touchHelperMethod: java.lang.reflect.Method? = null

    /** One recorded sample from the stylus. */
    private data class InkPoint(
        val x: Float,
        val y: Float,
        val pressure: Float,   // 0f..1f
        val tilt: Float,       // radians
        val timeMs: Long       // event time in ms
    )

    private class Stroke {
        val points = mutableListOf<InkPoint>()
    }

    private val strokes = mutableListOf<Stroke>()
    private var currentStroke: Stroke? = null

    // Base paint; width per-segment is derived from pressure at draw time.
    private val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    // Tunables
    private val minStrokeWidthPx = 2f
    private val maxStrokeWidthPx = 9f

    private var downTimeNanos = 0L
    private val stylusOnly = true
    private var isErasing = false
    private val eraserRadius = 15f

    // --- Pause detection ---
    private val pauseHandler = Handler(Looper.getMainLooper())
    private val pauseDelayMs = 2000L
    private var onPauseListener: (() -> Unit)? = null
    private var onTouchDownListener: (() -> Unit)? = null
    private val pauseRunnable = Runnable { onPauseListener?.invoke() }

    init {
        setupTouchHelper()
    }

    private fun setupTouchHelper() {
        val callback = object : RawInputCallback() {
            override fun onBeginRawDrawing(b: Boolean, touchPoint: TouchPoint) {
                // Log.d("InkCanvasView", "Raw Drawing Started")
            }
            override fun onEndRawDrawing(b: Boolean, touchPoint: TouchPoint) {}
            override fun onRawDrawingTouchPointMoveReceived(touchPoint: TouchPoint) {}
            override fun onRawDrawingTouchPointListReceived(list: TouchPointList) {}
            
            override fun onBeginRawErasing(b: Boolean, touchPoint: TouchPoint) {}
            override fun onEndRawErasing(b: Boolean, touchPoint: TouchPoint) {}
            override fun onRawErasingTouchPointMoveReceived(touchPoint: TouchPoint) {}
            override fun onRawErasingTouchPointListReceived(list: TouchPointList) {}
        }
        
        post {
            touchHelper = TouchHelper.create(this, callback).apply {
                setStrokeWidth(5f)
                setOnyxStrokeStyle("PENCIL")

                // Set the limit rect to the view's bounds on screen
                val location = IntArray(2)
                getLocationOnScreen(location)
                val rect = Rect(location[0], location[1], location[0] + width, location[1] + height)
                setLimitRect(rect, emptyList<Rect>())

                openRawDrawing()
                setRawDrawingEnabled(true)
                setRawDrawingRenderEnabled(true)
                
                // Cache the touch method once to avoid reflection overhead in onTouchEvent
                // Different Boox SDK versions and models use different method names
                val methodsToTry = arrayOf("onNotifyTouch", "onTouch", "onTouchEvent", "notifyTouch")
                for (name in methodsToTry) {
                    try {
                        touchHelperMethod = this.javaClass.getMethod(name, MotionEvent::class.java)
                        Log.d("InkCanvasView", "SUCCESS: Found Boox TouchHelper method: $name")
                        break
                    } catch (e: Exception) { }
                }

                if (touchHelperMethod == null) {
                    Log.e("InkCanvasView", "CRITICAL: Could not find ANY Boox TouchHelper method. Handwriting will have standard Android latency.")
                }

                // Enable E-ink hardware acceleration features
                try {
                    EpdController.enablePost(this@InkCanvasView, 1)
                } catch (e: Exception) {}

                Log.d("InkCanvasView", "TouchHelper initialized with rect: $rect")
            }
        }
    }

    /** Called ~3s after the pen lifts with no new stroke started since. */
    fun setOnPauseListener(listener: () -> Unit) {
        onPauseListener = listener
    }

    /** Called immediately when the pen touches the screen. */
    fun setOnTouchDownListener(listener: () -> Unit) {
        onTouchDownListener = listener
    }

    private fun isEraser(event: MotionEvent): Boolean {
        val toolType = event.getToolType(0)
        return toolType == MotionEvent.TOOL_TYPE_ERASER ||
                (event.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY != 0) ||
                (event.buttonState and MotionEvent.BUTTON_STYLUS_SECONDARY != 0)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Pass to Onyx hardware path for zero-lag visual feedback
        // Use cached method to eliminate reflection lag
        try {
            touchHelperMethod?.invoke(touchHelper, event)
        } catch (e: Exception) {
            // Log once then disable if it fails
            Log.e("InkCanvasView", "TouchHelper invoke failed", e)
            touchHelperMethod = null
        }

        val toolType = event.getToolType(0)
        val isEraserMode = isEraser(event)
        val isStylus = toolType == MotionEvent.TOOL_TYPE_STYLUS
        
        if (stylusOnly && !isStylus && !isEraserMode) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                requestUnbufferedDispatch(event)
                onTouchDownListener?.invoke()
                pauseHandler.removeCallbacks(pauseRunnable)
                
                isErasing = isEraserMode
                
                if (isErasing) {
                    setOnyxStrokeStyle("ERASER")
                    eraseAt(event.x, event.y)
                } else {
                    setOnyxStrokeStyle("PENCIL")
                    downTimeNanos = System.nanoTime()
                    currentStroke = Stroke().also { strokes.add(it) }
                    appendSample(event)
                }
                
                // Ensure DU mode is active for this view
                try {
                    EpdController.setViewDefaultUpdateMode(this, UpdateMode.DU)
                } catch (e: Exception) {}
                
                invalidate()
            }

            MotionEvent.ACTION_MOVE -> {
                if (isErasing) {
                    for (h in 0 until event.historySize) {
                        eraseAt(event.getHistoricalX(h), event.getHistoricalY(h))
                    }
                    eraseAt(event.x, event.y)
                } else {
                    for (h in 0 until event.historySize) {
                        appendSample(event, historyIndex = h)
                    }
                    appendSample(event)
                    invalidate()
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isErasing) {
                    eraseAt(event.x, event.y)
                    isErasing = false
                } else {
                    appendSample(event)
                    currentStroke = null
                    invalidate()
                }
                
                // Safety: cancel any existing pause and restart timer
                pauseHandler.removeCallbacks(pauseRunnable)
                pauseHandler.postDelayed(pauseRunnable, pauseDelayMs)
            }

            else -> return false
        }
        return true
    }

    private fun setOnyxStrokeStyle(styleNamePart: String) {
        try {
            val fields = TouchHelper::class.java.fields
            val field = fields.find { it.name.contains(styleNamePart, ignoreCase = true) }
            if (field != null) {
                val style = field.get(null) as Int
                touchHelper?.setStrokeStyle(style)
            }
        } catch (e: Exception) {}
    }

    private fun eraseAt(x: Float, y: Float) {
        var changed = false
        val iterator = strokes.iterator()
        while (iterator.hasNext()) {
            val stroke = iterator.next()
            // Stroke eraser: if any point in the stroke is near the eraser, remove entire stroke
            for (p in stroke.points) {
                val dx = p.x - x
                val dy = p.y - y
                if (dx * dx + dy * dy < eraserRadius * eraserRadius) {
                    iterator.remove()
                    changed = true
                    break
                }
            }
        }
        if (changed) {
            invalidate()
        }
    }

    private fun appendSample(event: MotionEvent, historyIndex: Int = -1) {
        val stroke = currentStroke ?: return
        val x = if (historyIndex >= 0) event.getHistoricalX(historyIndex) else event.x
        val y = if (historyIndex >= 0) event.getHistoricalY(historyIndex) else event.y
        val pressure = if (historyIndex >= 0) event.getHistoricalPressure(historyIndex) else event.pressure
        val tilt = try {
            if (historyIndex >= 0)
                event.getHistoricalAxisValue(MotionEvent.AXIS_TILT, historyIndex)
            else
                event.getAxisValue(MotionEvent.AXIS_TILT)
        } catch (e: Exception) {
            0f
        }
        val timeMs = if (historyIndex >= 0) event.getHistoricalEventTime(historyIndex) else event.eventTime
        stroke.points.add(InkPoint(x, y, pressure.coerceIn(0f, 1f), tilt, timeMs))

        // First point of a stroke: log down->processed latency for on-device testing.
        if (stroke.points.size == 1) {
            val latencyMs = (System.nanoTime() - downTimeNanos) / 1_000_000.0
            Log.d("InkLatency", "down-to-first-sample: ${"%.2f".format(latencyMs)} ms")
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (stroke in strokes) {
            drawStroke(canvas, stroke)
        }
    }

    private fun drawStroke(canvas: Canvas, stroke: Stroke) {
        val pts = stroke.points
        if (pts.size < 2) {
            // Single tap/dot — draw a small circle so it's still visible.
            pts.firstOrNull()?.let {
                inkPaint.style = Paint.Style.FILL
                canvas.drawCircle(it.x, it.y, widthForPressure(it.pressure) / 2f, inkPaint)
                inkPaint.style = Paint.Style.STROKE
            }
            return
        }
        for (i in 1 until pts.size) {
            val a = pts[i - 1]
            val b = pts[i]
            // Width follows the pressure at the leading edge of the segment —
            // simple approach, good enough to judge "feel" before optimizing.
            inkPaint.strokeWidth = widthForPressure(b.pressure)
            canvas.drawLine(a.x, a.y, b.x, b.y, inkPaint)
        }
    }

    private fun widthForPressure(pressure: Float): Float {
        return minStrokeWidthPx + (maxStrokeWidthPx - minStrokeWidthPx) * pressure
    }

    /** Wipe the canvas — this is the hook step 6 (reset on next pen-down) will call later. */
    fun clearAll() {
        pauseHandler.removeCallbacks(pauseRunnable)
        strokes.clear()
        currentStroke = null
        invalidate()
    }

    /** True if there's at least one stroke worth sending for recognition. */
    fun hasContent(): Boolean = strokes.any { it.points.size > 1 }

    /** Convert everything drawn so far into ML Kit's Ink format for recognition. */
    fun exportInk(): Ink {
        val inkBuilder = Ink.builder()
        for (stroke in strokes) {
            if (stroke.points.isEmpty()) continue
            val strokeBuilder = Ink.Stroke.builder()
            for (p in stroke.points) {
                strokeBuilder.addPoint(Ink.Point.create(p.x, p.y, p.timeMs))
            }
            inkBuilder.addStroke(strokeBuilder.build())
        }
        return inkBuilder.build()
    }
}
