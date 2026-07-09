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
    private var isTouchHelperInitialized = false

    // Cache for finished strokes to keep onDraw fast O(1) instead of O(N)
    private var canvasBitmap: android.graphics.Bitmap? = null
    private var bitmapCanvas: android.graphics.Canvas? = null

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
                Log.d("InkCanvasView", "Boox HW: onBeginRawDrawing")
            }
            override fun onEndRawDrawing(b: Boolean, touchPoint: TouchPoint) {
                Log.d("InkCanvasView", "Boox HW: onEndRawDrawing")
            }
            override fun onRawDrawingTouchPointMoveReceived(touchPoint: TouchPoint) {}
            override fun onRawDrawingTouchPointListReceived(list: TouchPointList) {}
            
            override fun onBeginRawErasing(b: Boolean, touchPoint: TouchPoint) {
                Log.d("InkCanvasView", "Boox HW: onBeginRawErasing")
            }
            override fun onEndRawErasing(b: Boolean, touchPoint: TouchPoint) {
                Log.d("InkCanvasView", "Boox HW: onEndRawErasing")
            }
            override fun onRawErasingTouchPointMoveReceived(touchPoint: TouchPoint) {}
            override fun onRawErasingTouchPointListReceived(list: TouchPointList) {}
        }
        
        post {
            // Use the 3-arg create for low-latency mode (true)
            touchHelper = TouchHelper.create(this, true, callback).apply {
                openRawDrawing()
                
                setStrokeWidth(5f)
                setStrokeColor(Color.BLACK)
                setStrokeStyle(1) // 1 is PENCIL
                
                val location = IntArray(2)
                getLocationOnScreen(location)
                val rect = Rect(location[0], location[1], location[0] + width, location[1] + height)
                
                if (!rect.isEmpty) {
                    Log.d("InkCanvasView", "Setting Limit Rect: $rect")
                    val list = ArrayList<Rect>()
                    list.add(rect)
                    setLimitRect(list, ArrayList<Rect>())
                }

                setRawInputReaderEnable(true)
                setRawDrawingEnabled(true)
                setRawDrawingRenderEnabled(true)
                setPostInputEvent(true)
                enableFingerTouch(true)
                setPenUpRefreshEnabled(true)

                try {
                    EpdController.enablePost(this@InkCanvasView, 1)
                } catch (e: Exception) {}
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
        // We know from previous log scanning that TouchHelper doesn't have a manual touch feeding method on this SDK version.
        // It likely handles events automatically via its own internal mechanisms once enabled.

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
                
                // Ensure fast update mode is active for this view
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
                    
                    // Throttled invalidate to reduce E-ink load while writing,
                    // but with a higher frequency (30ms) for smoother ink.
                    if (System.currentTimeMillis() % 30 < 15) {
                        invalidate()
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isErasing) {
                    eraseAt(event.x, event.y)
                    isErasing = false
                } else {
                    appendSample(event)
                    // Commit stroke to background cache
                    currentStroke?.let { stroke ->
                        bitmapCanvas?.let { bc -> drawStroke(bc, stroke) }
                    }
                    currentStroke = null
                }
                
                invalidate()
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
            redrawCache()
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

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            canvasBitmap = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
            bitmapCanvas = android.graphics.Canvas(canvasBitmap!!)
            redrawCache()
        }
    }

    private fun redrawCache() {
        val bc = bitmapCanvas ?: return
        canvasBitmap?.eraseColor(Color.TRANSPARENT)
        for (stroke in strokes) {
            if (stroke != currentStroke) {
                drawStroke(bc, stroke)
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        if (touchHelper != null && !isTouchHelperInitialized && width > 0) {
            val location = IntArray(2)
            getLocationOnScreen(location)
            val rect = Rect(location[0], location[1], location[0] + width, location[1] + height)
            if (!rect.isEmpty) {
                val list = ArrayList<Rect>()
                list.add(rect)
                touchHelper?.setLimitRect(list, ArrayList<Rect>())
                isTouchHelperInitialized = true
            }
        }

        // Draw the background cache (finished strokes)
        canvasBitmap?.let {
            canvas.drawBitmap(it, 0f, 0f, null)
        }

        // Draw only the active stroke currently being written
        currentStroke?.let {
            drawStroke(canvas, it)
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
        canvasBitmap?.eraseColor(Color.TRANSPARENT)
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
