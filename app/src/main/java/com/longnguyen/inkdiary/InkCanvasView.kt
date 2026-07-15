package com.longnguyen.inkdiary

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.google.mlkit.vision.digitalink.Ink
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.api.device.epd.UpdateMode
import com.onyx.android.sdk.api.device.EpdDeviceManager
import com.onyx.android.sdk.pen.RawInputCallback
import com.onyx.android.sdk.pen.TouchHelper
import com.onyx.android.sdk.pen.EpdPenManager
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.data.TouchPointList

class InkCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    private var touchHelper: TouchHelper? = null
    private val strokes = java.util.concurrent.CopyOnWriteArrayList<Stroke>()
    @Volatile private var currentStroke: Stroke? = null
    private var canvasBitmap: Bitmap? = null
    private var bitmapCanvas: Canvas? = null
    @Volatile private var lastProcessedTimestamp: Long = -1L

    private val inkPaint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 4f
        isAntiAlias = false
    }

    private data class InkPoint(val x: Float, val y: Float, val pressure: Float, val timeMs: Long)
    private class Stroke { val points = java.util.concurrent.CopyOnWriteArrayList<InkPoint>() }

    // Wall-clock base used so ML Kit gets monotonic millisecond timestamps.
    // Onyx pen timestamps are device-specific and often not wall-clock ms, which
    // hurts long multi-stroke recognition (especially Vietnamese).
    private var wallClockBaseMs: Long = 0L
    private var penTimeBase: Long = -1L

    private val pauseHandler = Handler(Looper.getMainLooper())
    private val pauseDelayMs = 2000L
    private var onPauseListener: (() -> Unit)? = null
    private var onTouchDownListener: (() -> Unit)? = null
    private var isInteractionEnabled = true
    // When clearAll() is triggered from a nib-down event, we need to suppress
    // enterAnimationUpdate() for the next 2 onBeginRawDrawing callbacks:
    //  #1 = the real nib-down callback (currently executing)
    //  #2 = the one fired by setRawDrawingEnabled(false/true) toggle (~48ms later)
    // If enterAnimationUpdate is called in either one, it cancels the GC refresh.
    // Counter starts at 2, each callback decrements it; when it hits 0 we schedule
    // enterAnimationUpdate() after a 200ms delay so GC has time to complete first.
    @Volatile private var gcSuppressCount = 0
    // Tracks whether the last interaction was an erase operation.
    // When true, onBeginRawDrawing will do an extra overlay reset to clear
    // lingering eraser ghost marks from the Onyx raw drawing layer.
    @Volatile private var wasErasing = false

    private val pauseRunnable = Runnable {
        Log.d("InkCanvasView", "pauseRunnable executing...")
        onPauseListener?.invoke() 
    }

    private val viewLocation = IntArray(2)

    init {
        holder.addCallback(this)
        isFocusable = true
        isFocusableInTouchMode = true
        
        // Match high-performance configuration: Top layer for speed
        setZOrderOnTop(true)
        holder.setFormat(PixelFormat.TRANSPARENT)
    }

    private fun setupTouchHelper() {
        val callback = object : RawInputCallback() {
            override fun onBeginRawDrawing(b: Boolean, touchPoint: TouchPoint) {
                // Fire synchronously — this is the primary nib-down hook on Boox/Onyx.
                // We call this BEFORE checking isInteractionEnabled so that we can clear
                // the "Thinking" state and re-enable interaction.
                onTouchDownListener?.invoke()

                if (!isInteractionEnabled) return
                lastProcessedTimestamp = -1L
                getLocationOnScreen(viewLocation)
                try {
                    // If the previous interaction was an erase, force-clear the Onyx raw
                    // drawing overlay to remove lingering eraser ghost marks (the thick
                    // strike-through lines) before starting a new drawing stroke.
                    if (wasErasing) {
                        wasErasing = false
                        Log.d("InkCanvasView", "Transitioning from eraser → draw: resetting overlay")
                        touchHelper?.setRawDrawingEnabled(false)
                        touchHelper?.setRawDrawingEnabled(true)
                    }

                    val suppressCount = gcSuppressCount
                    if (suppressCount > 0) {
                        // A GC clear is in progress. Don't call enterAnimationUpdate now —
                        // it would cancel the GC refresh and ghost pixels would remain.
                        gcSuppressCount = suppressCount - 1
                        if (gcSuppressCount == 0) {
                            // This is the last suppressed callback. Schedule enterAnimationUpdate
                            // after 500ms so the GC has time to physically complete.
                            postDelayed({
                                try { EpdDeviceManager.enterAnimationUpdate(true) } catch (e: Exception) {}
                                Log.d("InkCanvasView", "enterAnimationUpdate fired after GC delay")
                            }, 500)
                        }
                        Log.d("InkCanvasView", "Drawing started (GC suppress #$suppressCount remaining)")
                    } else {
                        EpdDeviceManager.enterAnimationUpdate(true)
                        Log.d("InkCanvasView", "Drawing started")
                    }
                    enableNativeEraser()
                    touchHelper?.setStrokeStyle(0)
                    touchHelper?.setStrokeWidth(4f)

                    // CRITICAL FIX: Re-enable raw drawing RENDER explicitly.
                    // When clearAll() toggles setRawDrawingEnabled(false/true), 
                    // some Onyx firmware versions default to RENDER=FALSE, causing 
                    // the "invisible ink" at the start of the stroke.
                    touchHelper?.setRawDrawingRenderEnabled(true)
                } catch (e: Exception) {}
            }

            override fun onEndRawDrawing(b: Boolean, touchPoint: TouchPoint) {
                try { EpdDeviceManager.exitAnimationUpdate(true) } catch (e: Exception) {}
                commitStrokeToBitmap(currentStroke)
                currentStroke = null
                post { 
                    drawSurface(UpdateMode.DU) 
                    pauseHandler.removeCallbacks(pauseRunnable)
                    pauseHandler.postDelayed(pauseRunnable, pauseDelayMs)
                    Log.d("InkCanvasView", "Drawing ended, pause timer started")
                }
            }

            override fun onRawDrawingTouchPointMoveReceived(touchPoint: TouchPoint) {
                collectPoint(touchPoint.x, touchPoint.y, touchPoint.pressure, touchPoint.timestamp)
            }

            override fun onRawDrawingTouchPointListReceived(list: TouchPointList) {
                list.points?.forEach { collectPoint(it.x, it.y, it.pressure, it.timestamp) }
            }

            override fun onBeginRawErasing(b: Boolean, touchPoint: TouchPoint) {
                // Fire synchronously to clear AI response even if using the eraser
                onTouchDownListener?.invoke()

                pauseHandler.removeCallbacks(pauseRunnable)
                getLocationOnScreen(viewLocation)
                currentStroke = null
                try { 
                    EpdDeviceManager.enterAnimationUpdate(true) 
                    enableNativeEraser()
                    // Set thick marker style for the eraser ghost line
                    touchHelper?.setStrokeStyle(1)
                    touchHelper?.setStrokeWidth(30f)
                } catch (e: Exception) {}
                Log.d("InkCanvasView", "Erasing started at (${touchPoint.x}, ${touchPoint.y})")
            }
            
            override fun onEndRawErasing(b: Boolean, touchPoint: TouchPoint) {
                try { EpdDeviceManager.exitAnimationUpdate(true) } catch (e: Exception) {}
                // Mark that we were erasing so onBeginRawDrawing can do a
                // clean overlay reset before the next drawing stroke.
                wasErasing = true
                post { 
                    // Reset stroke params to drawing mode BEFORE toggling
                    // setRawDrawingEnabled — this ensures the Onyx overlay is
                    // re-initialized with drawing params, not the thick eraser style.
                    try {
                        touchHelper?.setStrokeStyle(0)
                        touchHelper?.setStrokeWidth(4f)
                    } catch (e: Exception) {}

                    // Clear raw drawing overlay (removes the eraser ghost line)
                    try {
                        touchHelper?.setRawDrawingEnabled(false)
                        touchHelper?.setRawDrawingEnabled(true)
                        touchHelper?.setRawDrawingRenderEnabled(true)
                        enableNativeEraser() 
                    } catch (e: Exception) {}

                    // Use DU mode to prevent full screen flash (flicking)
                    drawSurface(UpdateMode.DU)
                    pauseHandler.removeCallbacks(pauseRunnable)
                    pauseHandler.postDelayed(pauseRunnable, pauseDelayMs)
                    Log.d("InkCanvasView", "Erasing ended, stroke params reset, pause timer started")
                }
            }

            override fun onRawErasingTouchPointMoveReceived(touchPoint: TouchPoint) {
                handleErasing(touchPoint)
            }
            
            override fun onRawErasingTouchPointListReceived(list: TouchPointList) {
                list.points?.forEach { handleErasing(it) }
            }
        }

        try {
            touchHelper = TouchHelper.create(this, true, callback)
            touchHelper?.apply {
                setRawInputReaderEnable(true)
                updateLimitRect()
                openRawDrawing()
                setRawDrawingEnabled(true)
                setRawDrawingRenderEnabled(true)
                enableNativeEraser()
                setSingleRegionMode()
                setStrokeWidth(4f)
                setStrokeStyle(0) 
            }
            EpdController.setScreenHandWritingPenState(this, EpdPenManager.PEN_DRAWING)
            try { EpdController.enablePost(this, 1) } catch (e: Exception) {}
        } catch (e: Exception) {
            Log.e("InkDiary", "Onyx setup failed: ${e.message}")
        }
    }

    private fun enableNativeEraser() {
        val helper = touchHelper ?: return
        try {
            val methods = helper.javaClass.methods
            // Try different possible method names for eraser support in different SDK versions
            // Notable uses setEraserRawDrawingEnabled(true, 1)
            methods.find { it.name == "setEraserRawDrawingEnabled" }?.invoke(helper, true, 1)
            methods.find { it.name == "setRawEraserEnable" }?.invoke(helper, true)
            methods.find { it.name == "setRawEraserEnabled" }?.invoke(helper, true)
            methods.find { it.name == "setEraserEnable" }?.invoke(helper, true)
            methods.find { it.name == "setEraserEnabled" }?.invoke(helper, true)
            methods.find { it.name == "setPenButtonEraserEnable" }?.invoke(helper, true)
            methods.find { it.name == "setPenButtonEraserEnabled" }?.invoke(helper, true)
        } catch (e: Exception) {
            Log.w("InkCanvasView", "Failed to enable native eraser: ${e.message}")
        }
    }

    private fun updateLimitRect() {
        val helper = touchHelper ?: return
        val limitRect = Rect(0, 0, width, height)
        try {
            helper.setLimitRect(limitRect, ArrayList<Rect>())
        } catch (e: Exception) {}
    }

    private fun collectPoint(rawX: Float, rawY: Float, p: Float, t: Long) {
        // Deduplicate using raw pen timestamp when available.
        if (t > 0 && lastProcessedTimestamp > 0 && t <= lastProcessedTimestamp) return
        if (t > 0) lastProcessedTimestamp = t

        val x = rawX - viewLocation[0]
        val y = rawY - viewLocation[1]

        if (x < -100 || x > width + 100 || y < -100 || y > height + 100) return

        // Normalize to wall-clock ms for ML Kit (Google samples use System.currentTimeMillis()).
        val timeMs = normalizeTimestamp(t)

        if (currentStroke == null) {
            currentStroke = Stroke().also { strokes.add(it) }
            Log.d("InkCanvasView", "Starting new stroke at ($x, $y)")
        }

        currentStroke?.points?.add(InkPoint(x, y, p, timeMs))
    }

    private fun normalizeTimestamp(penT: Long): Long {
        val now = System.currentTimeMillis()
        if (penT <= 0L) return now
        if (penTimeBase < 0L) {
            penTimeBase = penT
            wallClockBaseMs = now
        }
        // Keep deltas from the pen stream; map onto wall clock for absolute ms.
        val delta = penT - penTimeBase
        return if (delta >= 0L) wallClockBaseMs + delta else now
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val toolType = event.getToolType(0)
        val isStylus = toolType == MotionEvent.TOOL_TYPE_STYLUS

        // Stylus or Finger ACTION_DOWN: Trigger the listener to clear AI response.
        // We do this BEFORE the interaction check so it works even when interaction is disabled.
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            pauseHandler.removeCallbacks(pauseRunnable)
            getLocationOnScreen(viewLocation)
            Log.d("InkCanvasView", "Touch/Stylus DOWN in onTouchEvent (tool=${event.getToolType(0)})")
            onTouchDownListener?.invoke()
        }

        // We MUST pass stylus events to touchHelper so its internal callbacks fire.
        // The hardware drawing itself is blocked inside onBeginRawDrawing by isInteractionEnabled.
        if (touchHelper != null && (isStylus || isInteractionEnabled)) {
            if (touchHelper!!.onTouchEvent(event)) {
                return true
            }
        }

        if (!isInteractionEnabled) return false

        val isEraser = toolType == MotionEvent.TOOL_TYPE_ERASER
        if (isEraser) {
            pauseHandler.removeCallbacks(pauseRunnable)
            performEraseAt(event.x, event.y)
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                drawSurface(UpdateMode.GC)
                pauseHandler.postDelayed(pauseRunnable, pauseDelayMs)
            }
            return true
        }

        return super.onTouchEvent(event)
    }

    private fun commitStrokeToBitmap(stroke: Stroke?) {
        val bc = bitmapCanvas ?: return
        val points = stroke?.points ?: return
        if (points.isEmpty()) return

        if (points.size < 2) {
            val p = points[0]
            bc.drawPoint(p.x, p.y, inkPaint)
        } else {
            var prev = points[0]
            for (i in 1 until points.size) {
                val curr = points[i]
                bc.drawLine(prev.x, prev.y, curr.x, curr.y, inkPaint)
                prev = curr
            }
        }
        drawSurface()
    }

    private fun drawSurface(mode: UpdateMode = UpdateMode.DU) {
        // Set mode BEFORE posting the canvas so the e-ink display uses the correct
        // waveform for THIS frame, not the next one.
        try { EpdController.setViewDefaultUpdateMode(this, mode) } catch (e: Exception) {}

        val canvas = try {
            holder.lockHardwareCanvas()
        } catch (e: Exception) {
            holder.lockCanvas()
        } ?: return

        try {
            // CRITICAL: MUST clear with TRANSPARENT so UI behind is visible
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            canvasBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {}

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (width > 0 && height > 0) {
            canvasBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmapCanvas = Canvas(canvasBitmap!!)
            // CRITICAL: Bitmap must be transparent initially
            bitmapCanvas?.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            setupTouchHelper()
            drawSurface()
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        touchHelper?.closeRawDrawing()
        touchHelper = null
    }

    fun setOnPauseListener(l: () -> Unit) { onPauseListener = l }
    fun setOnTouchDownListener(l: () -> Unit) { onTouchDownListener = l }

    fun setInteractionEnabled(enabled: Boolean) {
        isInteractionEnabled = enabled
        try {
            touchHelper?.setRawDrawingEnabled(enabled)
            touchHelper?.setRawDrawingRenderEnabled(enabled)
        } catch (e: Exception) {
            Log.e("InkCanvasView", "Failed to set interaction enabled: ${e.message}")
        }
    }

    fun bounceTouchHelper() {
        try {
            touchHelper?.setRawDrawingEnabled(false)
            touchHelper?.setRawDrawingEnabled(true)
        } catch (e: Exception) {
            Log.e("InkCanvasView", "Failed to bounce raw drawing: ${e.message}")
        }
    }

    // suppressAnimationForGc = true when called from the nib-down listener (erasing AI response).
    // This sets gcSuppressCount=2 to block enterAnimationUpdate in the next two
    // onBeginRawDrawing callbacks, preventing the GC refresh from being cancelled.
    fun clearAll(suppressAnimationForGc: Boolean = false) {
        Log.d("InkCanvasView", "clearAll called (suppressAnim=$suppressAnimationForGc)")
        
        if (suppressAnimationForGc) {
            // SET THIS FIRST! Before exiting animation or clearing bitmap.
            // We use 2 to handle both the current nib-down and any potential
            // callback from a setInteractionEnabled(true) call in MainActivity.
            gcSuppressCount = 2
        }

        pauseHandler.removeCallbacks(pauseRunnable)
        strokes.clear()
        currentStroke = null
        lastProcessedTimestamp = -1L
        penTimeBase = -1L
        wallClockBaseMs = 0L
        canvasBitmap?.eraseColor(Color.TRANSPARENT)

        // Exit animation mode first so the GC refresh below isn't immediately
        // overridden by enterAnimationUpdate() in onBeginRawDrawing.
        try { EpdDeviceManager.exitAnimationUpdate(true) } catch (e: Exception) {}

        if (!suppressAnimationForGc) {
            // Immediate toggle when NOT in a nib-down event.
            try {
                touchHelper?.setRawDrawingEnabled(false)
                touchHelper?.setRawDrawingEnabled(true)
            } catch (e: Exception) {
                Log.e("InkCanvasView", "Failed to toggle raw drawing: ${e.message}")
            }
        } else {
            // When called from nib-down (suppressed mode), we can't toggle immediately
            // because it causes "invisible ink". Instead, schedule the toggle AFTER
            // the GC refresh has physically completed on the e-ink panel (~600ms).
            // This ensures the Onyx raw drawing overlay is properly reset, which is
            // critical for physically clearing the AI response text from the screen.
            postDelayed({
                try {
                    touchHelper?.setRawDrawingEnabled(false)
                    touchHelper?.setRawDrawingEnabled(true)
                    touchHelper?.setRawDrawingRenderEnabled(true)
                    enableNativeEraser()
                    Log.d("InkCanvasView", "Delayed overlay reset completed after GC")
                } catch (e: Exception) {
                    Log.e("InkCanvasView", "Failed delayed toggle: ${e.message}")
                }
            }, 600)
        }

        drawSurface(UpdateMode.GC)
    }

    fun clearForNewSession() {
        pauseHandler.removeCallbacks(pauseRunnable)
        strokes.clear()
        // If there's an active stroke being built, keep it but move it to the new empty strokes list
        currentStroke?.let { strokes.add(it) }
        lastProcessedTimestamp = -1L
        penTimeBase = -1L
        wallClockBaseMs = 0L
        canvasBitmap?.eraseColor(Color.TRANSPARENT)
        // Use GC to ensure a completely clean slate when transitioning from AI response
        drawSurface(UpdateMode.GC)
    }

    fun hasContent() = strokes.any { it.points.size > 1 }

    private fun handleErasing(tp: TouchPoint?) {
        if (tp == null) return
        // notable: ensure viewLocation is up to date before transforming
        getLocationOnScreen(viewLocation)
        val loc = viewLocation
        if (loc.size < 2) return
        
        // TP coordinates are raw screen coordinates from Onyx SDK
        val x = tp.x - loc[0]
        val y = tp.y - loc[1]
        performEraseAt(x, y)
    }

    private fun performEraseAt(x: Float, y: Float) {
        val eraseRadius = 40f // Reduced from 100f for better precision
        var changed = false
        
        val toRemove = mutableListOf<Stroke>()
        for (stroke in strokes) {
            val pts = stroke.points ?: continue
            for (p in pts) {
                if (Math.hypot((p.x - x).toDouble(), (p.y - y).toDouble()) < eraseRadius) {
                    toRemove.add(stroke)
                    changed = true
                    break
                }
            }
        }
        
        if (changed) {
            strokes.removeAll(toRemove)
            Log.d("InkCanvasView", "Erased ${toRemove.size} strokes. Remaining: ${strokes.size}")
            
            val bitmap = canvasBitmap ?: return
            bitmap.eraseColor(Color.TRANSPARENT)
            val bc = bitmapCanvas ?: return
            
            // Local copy to avoid concurrent modification issues during redraw
            val strokesSnapshot = strokes.toList()
            for (stroke in strokesSnapshot) {
                val points = stroke.points
                if (points == null || points.isEmpty()) continue
                
                if (points.size < 2) {
                    val p = points[0]
                    bc.drawPoint(p.x, p.y, inkPaint)
                } else {
                    try {
                        var prev = points[0]
                        for (i in 1 until points.size) {
                            val curr = points[i]
                            bc.drawLine(prev.x, prev.y, curr.x, curr.y, inkPaint)
                            prev = curr
                        }
                    } catch (e: Exception) {
                        // Ignore concurrent modification issues in points list
                    }
                }
            }
            post { drawSurface(UpdateMode.DU) }
        }
    }

    fun exportInk(): Ink {
        val builder = Ink.builder()
        val currentStrokes = strokes.toList()
        Log.d("InkCanvasView", "Exporting ${currentStrokes.size} strokes for recognition")
        for (stroke in currentStrokes) {
            val sBuilder = Ink.Stroke.builder()
            val pts = stroke.points.toList()
            if (pts.isEmpty()) continue
            for (p in pts) sBuilder.addPoint(Ink.Point.create(p.x, p.y, p.timeMs))
            builder.addStroke(sBuilder.build())
        }
        return builder.build()
    }

    /**
     * Export strokes as point lists for segmented recognition.
     * Coordinates are in this view's space; timestamps are wall-clock ms.
     */
    fun exportStrokePoints(): List<List<HandwritingRecognizer.StrokePoint>> {
        val currentStrokes = strokes.toList()
        Log.d("InkCanvasView", "Exporting ${currentStrokes.size} stroke lists for recognition")
        return currentStrokes.mapNotNull { stroke ->
            val pts = stroke.points.toList()
            if (pts.size < 2) return@mapNotNull null
            pts.map { p -> HandwritingRecognizer.StrokePoint(p.x, p.y, p.timeMs) }
        }
    }

    fun strokeCount(): Int = strokes.size

    fun refreshScreenGc() {
        Log.d("InkCanvasView", "refreshScreenGc called")
        drawSurface(UpdateMode.GC)
    }
}
