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

    private val pauseHandler = Handler(Looper.getMainLooper())
    private val pauseDelayMs = 2000L
    private var onPauseListener: (() -> Unit)? = null
    private var onTouchDownListener: (() -> Unit)? = null
    private var isInteractionEnabled = true
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
                if (!isInteractionEnabled) return
                lastProcessedTimestamp = -1L
                getLocationOnScreen(viewLocation)
                // Fire synchronously — this is the primary nib-down hook on Boox/Onyx
                onTouchDownListener?.invoke()
                try { 
                    EpdDeviceManager.enterAnimationUpdate(true)
                    enableNativeEraser()
                    touchHelper?.setStrokeStyle(0)
                    touchHelper?.setStrokeWidth(4f)
                } catch (e: Exception) {}
                Log.d("InkCanvasView", "Drawing started")
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
                post { 
                    // Clear raw drawing overlay
                    try {
                        touchHelper?.setRawDrawingEnabled(false)
                        touchHelper?.setRawDrawingEnabled(true)
                        enableNativeEraser() 
                    } catch (e: Exception) {}

                    // Use DU mode to prevent full screen flash (flicking)
                    drawSurface(UpdateMode.DU)
                    pauseHandler.removeCallbacks(pauseRunnable)
                    pauseHandler.postDelayed(pauseRunnable, pauseDelayMs)
                    Log.d("InkCanvasView", "Erasing ended, pause timer started")
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
        if (t > 0 && lastProcessedTimestamp > 0 && t <= lastProcessedTimestamp) return
        lastProcessedTimestamp = t

        val x = rawX - viewLocation[0]
        val y = rawY - viewLocation[1]

        if (x < -100 || x > width + 100 || y < -100 || y > height + 100) return

        if (currentStroke == null) {
            currentStroke = Stroke().also { strokes.add(it) }
            // Fallback: also fire synchronously here in case onBeginRawDrawing didn't fire
            onTouchDownListener?.invoke()
            Log.d("InkCanvasView", "Starting new stroke at ($x, $y)")
        }

        currentStroke?.points?.add(InkPoint(x, y, p, t))
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isInteractionEnabled) return false

        val toolType = event.getToolType(0)

        // For stylus nib: fire the listener on ACTION_DOWN directly (no Onyx SDK involved)
        if (toolType == MotionEvent.TOOL_TYPE_STYLUS && event.actionMasked == MotionEvent.ACTION_DOWN) {
            pauseHandler.removeCallbacks(pauseRunnable)
            getLocationOnScreen(viewLocation)
            onTouchDownListener?.invoke()
        }

        if (touchHelper != null && touchHelper!!.onTouchEvent(event)) {
            return true
        }

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
        try { EpdController.setViewDefaultUpdateMode(this, mode) } catch (e: Exception) {}
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

    fun clearAll() {
        Log.d("InkCanvasView", "clearAll called")
        pauseHandler.removeCallbacks(pauseRunnable)
        strokes.clear()
        currentStroke = null
        canvasBitmap?.eraseColor(Color.TRANSPARENT)
        
        // Force clear the Onyx hardware raw input overlay.
        // Toggling this is necessary to remove "raw" ink pixels instantly.
        try {
            touchHelper?.setRawDrawingEnabled(false)
            touchHelper?.setRawDrawingEnabled(true)
        } catch (e: Exception) {
            Log.e("InkCanvasView", "Failed to toggle raw drawing: ${e.message}")
        }

        // Trigger a Global Clear (GC) refresh to remove ghosting and UI elements.
        // We do this via the SurfaceHolder to ensure it syncs with our transparent bitmap.
        drawSurface(UpdateMode.GC)
    }

    fun clearForNewSession() {
        pauseHandler.removeCallbacks(pauseRunnable)
        strokes.clear()
        // If there's an active stroke being built, keep it but move it to the new empty strokes list
        currentStroke?.let { strokes.add(it) }
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
}
