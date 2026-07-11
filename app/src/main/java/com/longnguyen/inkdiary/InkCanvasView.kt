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
    private val strokes = mutableListOf<Stroke>()
    private var currentStroke: Stroke? = null
    private var canvasBitmap: Bitmap? = null
    private var bitmapCanvas: Canvas? = null
    private var lastProcessedTimestamp: Long = -1L

    private val inkPaint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 4f
        isAntiAlias = false
    }

    private data class InkPoint(val x: Float, val y: Float, val pressure: Float, val timeMs: Long)
    private class Stroke { val points = mutableListOf<InkPoint>() }

    private val pauseHandler = Handler(Looper.getMainLooper())
    private val pauseDelayMs = 800L
    private var onPauseListener: (() -> Unit)? = null
    private var onTouchDownListener: (() -> Unit)? = null
    private val pauseRunnable = Runnable { onPauseListener?.invoke() }

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
                lastProcessedTimestamp = -1L
                getLocationOnScreen(viewLocation)
                try { EpdDeviceManager.enterAnimationUpdate(true) } catch (e: Exception) {}
            }

            override fun onEndRawDrawing(b: Boolean, touchPoint: TouchPoint) {
                try { EpdDeviceManager.exitAnimationUpdate(true) } catch (e: Exception) {}
                commitStrokeToBitmap(currentStroke)
                currentStroke = null
                post { 
                    drawSurface(UpdateMode.DU) 
                    pauseHandler.removeCallbacks(pauseRunnable)
                    pauseHandler.postDelayed(pauseRunnable, pauseDelayMs)
                }
            }

            override fun onRawDrawingTouchPointMoveReceived(touchPoint: TouchPoint) {
                collectPoint(touchPoint.x, touchPoint.y, touchPoint.pressure, touchPoint.timestamp)
            }

            override fun onRawDrawingTouchPointListReceived(list: TouchPointList) {
                list.points?.forEach { collectPoint(it.x, it.y, it.pressure, it.timestamp) }
            }

            override fun onBeginRawErasing(b: Boolean, touchPoint: TouchPoint) {}
            override fun onEndRawErasing(b: Boolean, touchPoint: TouchPoint) { post { drawSurface() } }
            override fun onRawErasingTouchPointMoveReceived(touchPoint: TouchPoint) {}
            override fun onRawErasingTouchPointListReceived(list: TouchPointList) {}
        }

        try {
            touchHelper = TouchHelper.create(this, true, callback)
            touchHelper?.apply {
                setRawInputReaderEnable(true)
                updateLimitRect()
                openRawDrawing()
                setRawDrawingEnabled(true)
                setRawDrawingRenderEnabled(true)
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
            onTouchDownListener?.invoke()
        }

        currentStroke?.points?.add(InkPoint(x, y, p, t))
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val isStylus = event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS
        if (!isStylus) return super.onTouchEvent(event)

        val action = event.actionMasked
        if (action == MotionEvent.ACTION_DOWN) {
            pauseHandler.removeCallbacks(pauseRunnable)
            getLocationOnScreen(viewLocation)
            currentStroke = Stroke().also { strokes.add(it) }
            onTouchDownListener?.invoke()
            return true 
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            commitStrokeToBitmap(currentStroke)
            currentStroke = null
            drawSurface(UpdateMode.GC)
            pauseHandler.postDelayed(pauseRunnable, pauseDelayMs)
            return true
        }
        return false 
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

    fun clearAll() {
        pauseHandler.removeCallbacks(pauseRunnable)
        strokes.clear()
        currentStroke = null
        canvasBitmap?.eraseColor(Color.TRANSPARENT)
        
        // Force clear the Onyx raw input layer
        try {
            touchHelper?.setRawDrawingEnabled(false)
            touchHelper?.setRawDrawingEnabled(true)
        } catch (e: Exception) {
            Log.e("InkCanvasView", "Failed to toggle raw drawing: ${e.message}")
        }

        drawSurface(UpdateMode.GC)
    }

    fun hasContent() = strokes.any { it.points.size > 1 }

    fun exportInk(): Ink {
        val builder = Ink.builder()
        for (stroke in strokes) {
            val sBuilder = Ink.Stroke.builder()
            for (p in stroke.points) sBuilder.addPoint(Ink.Point.create(p.x, p.y, p.timeMs))
            builder.addStroke(sBuilder.build())
        }
        return builder.build()
    }
}
