package com.longnguyen.inkdiary

import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.DigitalInkRecognizer
import com.google.mlkit.vision.digitalink.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.Ink
import com.google.mlkit.vision.digitalink.RecognitionContext
import com.google.mlkit.vision.digitalink.WritingArea
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private const val TAG = "HandwritingRecognizer"

/**
 * Raw strokes -> text, fully offline after the first model download.
 * Supports English ("en") and Vietnamese ("vi").
 *
 * Long notes (especially multi-line Vietnamese) are segmented into lines
 * because ML Kit's digital ink model assumes roughly one line per recognition
 * call when a WritingArea is provided.
 */
class HandwritingRecognizer {

    private val modelManager = RemoteModelManager.getInstance()
    private val recognizeExecutor = Executors.newSingleThreadExecutor()

    private var enRecognizer: DigitalInkRecognizer? = null
    private var viRecognizer: DigitalInkRecognizer? = null

    /** The currently active language tag ("en" or "vi"). */
    var currentLanguage: String = "en"
        private set

    /**
     * Downloads both the English and Vietnamese recognition models.
     * Call this once, e.g. in onCreate. Requires WiFi the first time;
     * after that the models are cached on-device and work fully offline.
     */
    fun setup(onReady: () -> Unit, onError: (Exception) -> Unit) {
        var enReady = false
        var viReady = false

        fun checkBothReady() {
            if (enReady && viReady) {
                Log.d(TAG, "Both EN and VI models ready")
                onReady()
            }
        }

        downloadModel(
            "en",
            onSuccess = { recognizer ->
                enRecognizer = recognizer
                enReady = true
                Log.d(TAG, "English model ready (offline from now on)")
                checkBothReady()
            },
            onFailure = { e ->
                Log.e(TAG, "English model download failed", e)
                onError(e)
            }
        )

        downloadModel(
            "vi",
            onSuccess = { recognizer ->
                viRecognizer = recognizer
                viReady = true
                Log.d(TAG, "Vietnamese model ready (offline from now on)")
                checkBothReady()
            },
            onFailure = { e ->
                // Vietnamese model failure is non-fatal; we still have English
                Log.w(TAG, "Vietnamese model download failed — VI recognition unavailable", e)
                viReady = true
                checkBothReady()
            }
        )
    }

    private fun downloadModel(
        languageTag: String,
        onSuccess: (DigitalInkRecognizer) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        val modelIdentifier = try {
            DigitalInkRecognitionModelIdentifier.fromLanguageTag(languageTag)
        } catch (e: Exception) {
            onFailure(
                IllegalStateException(
                    "Could not find ML Kit model for language tag '$languageTag'",
                    e
                )
            )
            return
        }
        if (modelIdentifier == null) {
            onFailure(
                IllegalStateException("ML Kit does not have a Digital Ink model for '$languageTag'")
            )
            return
        }

        val builtModel = DigitalInkRecognitionModel.builder(modelIdentifier).build()
        val downloadConditions = DownloadConditions.Builder()
            .requireWifi()
            .build()

        modelManager.download(builtModel, downloadConditions)
            .addOnSuccessListener {
                val recognizer = DigitalInkRecognition.getClient(
                    DigitalInkRecognizerOptions.builder(builtModel).build()
                )
                onSuccess(recognizer)
            }
            .addOnFailureListener { e -> onFailure(e) }
    }

    /**
     * Switches the active recognition language.
     * Returns true if the requested language is available.
     */
    fun setLanguage(languageTag: String): Boolean {
        val recognizer = when (languageTag) {
            "en" -> enRecognizer
            "vi" -> viRecognizer
            else -> null
        }
        return if (recognizer != null) {
            currentLanguage = languageTag
            Log.d(TAG, "Switched recognition language to: $languageTag")
            true
        } else {
            Log.w(TAG, "Cannot switch to '$languageTag' — model not available")
            false
        }
    }

    /**
     * Recognize ink from captured strokes.
     *
     * @param strokes Stroke point lists from the canvas (view coordinates, wall-clock ms).
     * @param canvasWidth Physical writing surface width in the same units as stroke coords.
     * @param canvasHeight Physical writing surface height in the same units as stroke coords.
     */
    fun recognize(
        strokes: List<List<StrokePoint>>,
        canvasWidth: Float,
        canvasHeight: Float,
        onResult: (String) -> Unit,
        onError: (Exception) -> Unit
    ) {
        val r = activeRecognizer()
        if (r == null) {
            onError(
                IllegalStateException(
                    "Recognizer not ready — call setup() first and wait for onReady"
                )
            )
            return
        }
        if (strokes.isEmpty() || strokes.all { it.size < 2 }) {
            onResult("")
            return
        }

        val width = canvasWidth.coerceAtLeast(1f)
        val height = canvasHeight.coerceAtLeast(1f)

        recognizeExecutor.execute {
            try {
                val text = recognizeSegmented(r, strokes, width, height)
                Log.d(
                    TAG,
                    "Recognized ($currentLanguage): \"$text\" " +
                        "(${strokes.size} strokes, canvas=${width.toInt()}x${height.toInt()})"
                )
                onResult(text)
            } catch (e: Exception) {
                Log.e(TAG, "Recognition failed ($currentLanguage)", e)
                onError(e)
            }
        }
    }

    /** Backward-compatible single-shot recognition without canvas size (legacy). */
    fun recognize(ink: Ink, onResult: (String) -> Unit, onError: (Exception) -> Unit) {
        val r = activeRecognizer()
        if (r == null) {
            onError(
                IllegalStateException(
                    "Recognizer not ready — call setup() first and wait for onReady"
                )
            )
            return
        }
        r.recognize(ink)
            .addOnSuccessListener { result ->
                val text = result.candidates.firstOrNull()?.text.orEmpty()
                Log.d(
                    TAG,
                    "Recognized ($currentLanguage): \"$text\" (${result.candidates.size} candidates)"
                )
                onResult(text)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Recognition failed ($currentLanguage)", e)
                onError(e)
            }
    }

    private fun activeRecognizer(): DigitalInkRecognizer? {
        return when (currentLanguage) {
            "vi" -> viRecognizer ?: enRecognizer
            else -> enRecognizer
        }
    }

    /**
     * ML Kit assumes roughly one line when WritingArea height ≈ line height.
     * Long multi-line notes (common in Vietnamese diary entries) must be split.
     */
    private fun recognizeSegmented(
        recognizer: DigitalInkRecognizer,
        strokes: List<List<StrokePoint>>,
        canvasWidth: Float,
        canvasHeight: Float
    ): String {
        val lines = segmentIntoLines(strokes)
        Log.d(
            TAG,
            "Segmented ${strokes.size} strokes into ${lines.size} line(s) " +
                "(lang=$currentLanguage)"
        )

        // Estimate a single-line writing area height from actual stroke geometry.
        val lineHeightEstimate = estimateLineHeight(strokes).coerceIn(24f, canvasHeight)
        // Use full canvas width; height = one line so relative letter size is correct.
        val writingArea = WritingArea(canvasWidth, lineHeightEstimate)

        val parts = mutableListOf<String>()
        var preContext = ""

        for ((index, lineStrokes) in lines.withIndex()) {
            // Chunk very long single lines (many characters / strokes).
            val chunks = chunkStrokes(lineStrokes, maxStrokesPerChunk())
            for ((chunkIndex, chunk) in chunks.withIndex()) {
                val ink = buildInk(chunk)
                if (ink.strokes.isEmpty()) continue

                val context = RecognitionContext.builder()
                    .setPreContext(preContext.takeLast(20))
                    .setWritingArea(writingArea)
                    .build()

                val piece = recognizeBlocking(recognizer, ink, context)
                    .trim()
                    .ifEmpty {
                        // Fallback without context if contexted call returned empty
                        recognizeBlocking(recognizer, ink, null).trim()
                    }

                Log.d(
                    TAG,
                    "Line ${index + 1}/${lines.size} chunk ${chunkIndex + 1}/${chunks.size}: " +
                        "\"$piece\" (${chunk.size} strokes, pre=\"$preContext\")"
                )

                if (piece.isNotEmpty()) {
                    parts.add(piece)
                    // Pre-context is prior text (last 20 chars) for the next chunk/line.
                    preContext = (preContext + " " + piece).trim().takeLast(20)
                }
            }
        }

        return joinRecognizedParts(parts)
    }

    private fun maxStrokesPerChunk(): Int {
        // Vietnamese diacritics often add strokes; keep chunks smaller than English.
        return if (currentLanguage == "vi") 18 else 28
    }

    private fun recognizeBlocking(
        recognizer: DigitalInkRecognizer,
        ink: Ink,
        context: RecognitionContext?
    ): String {
        val task = if (context != null) {
            recognizer.recognize(ink, context)
        } else {
            recognizer.recognize(ink)
        }
        val result = Tasks.await(task)
        return result.candidates.firstOrNull()?.text.orEmpty()
    }

    private fun buildInk(strokes: List<List<StrokePoint>>): Ink {
        val builder = Ink.builder()
        for (stroke in strokes) {
            if (stroke.size < 2) continue
            val sBuilder = Ink.Stroke.builder()
            for (p in stroke) {
                sBuilder.addPoint(Ink.Point.create(p.x, p.y, p.t))
            }
            builder.addStroke(sBuilder.build())
        }
        return builder.build()
    }

    /**
     * Group strokes into visual lines using vertical position.
     * Strokes that sit on a similar baseline belong to the same line.
     */
    private fun segmentIntoLines(strokes: List<List<StrokePoint>>): List<List<List<StrokePoint>>> {
        if (strokes.isEmpty()) return emptyList()
        if (strokes.size == 1) return listOf(strokes)

        data class StrokeMeta(
            val points: List<StrokePoint>,
            val minY: Float,
            val maxY: Float,
            val centerY: Float,
            val minX: Float
        )

        val metas = strokes.mapNotNull { pts ->
            if (pts.isEmpty()) return@mapNotNull null
            var minY = Float.MAX_VALUE
            var maxY = Float.MIN_VALUE
            var minX = Float.MAX_VALUE
            var sumY = 0f
            for (p in pts) {
                minY = min(minY, p.y)
                maxY = max(maxY, p.y)
                minX = min(minX, p.x)
                sumY += p.y
            }
            StrokeMeta(pts, minY, maxY, sumY / pts.size, minX)
        }
        if (metas.isEmpty()) return emptyList()

        // Typical character/line height from median stroke height.
        val heights = metas.map { (it.maxY - it.minY).coerceAtLeast(1f) }.sorted()
        val medianStrokeH = heights[heights.size / 2]
        // New line if center jumps down by more than ~half a character height + gap.
        val lineGapThreshold = max(medianStrokeH * 0.75f, 28f)

        val lines = mutableListOf<MutableList<StrokeMeta>>()
        // Process roughly in writing order: time order of first point, which matches stroke list order.
        for (meta in metas) {
            val lastLine = lines.lastOrNull()
            if (lastLine == null) {
                lines.add(mutableListOf(meta))
                continue
            }
            val lineCenter = lastLine.map { it.centerY }.average().toFloat()
            val dy = meta.centerY - lineCenter
            if (dy > lineGapThreshold) {
                // Clearly below previous line → new line
                lines.add(mutableListOf(meta))
            } else if (dy < -lineGapThreshold * 1.4f) {
                // Large jump upward (e.g. accent correction on earlier line, or new block)
                // Prefer attaching to nearest existing line if close; else new line.
                val nearest = lines.minByOrNull { abs(it.map { s -> s.centerY }.average().toFloat() - meta.centerY) }
                val nearestCenter = nearest?.map { it.centerY }?.average()?.toFloat() ?: lineCenter
                if (nearest != null && abs(meta.centerY - nearestCenter) <= lineGapThreshold) {
                    nearest.add(meta)
                } else {
                    lines.add(mutableListOf(meta))
                }
            } else {
                lastLine.add(meta)
            }
        }

        // Within each line, restore left-to-right order for recognition quality.
        return lines.map { line ->
            line.sortedWith(compareBy({ it.minX }, { it.points.firstOrNull()?.t ?: 0L }))
                .map { it.points }
        }
    }

    /** Split a long line into smaller stroke chunks for more reliable recognition. */
    private fun chunkStrokes(
        lineStrokes: List<List<StrokePoint>>,
        maxStrokes: Int
    ): List<List<List<StrokePoint>>> {
        if (lineStrokes.size <= maxStrokes) return listOf(lineStrokes)
        return lineStrokes.chunked(maxStrokes)
    }

    private fun estimateLineHeight(strokes: List<List<StrokePoint>>): Float {
        val heights = strokes.mapNotNull { pts ->
            if (pts.isEmpty()) return@mapNotNull null
            val minY = pts.minOf { it.y }
            val maxY = pts.maxOf { it.y }
            (maxY - minY).takeIf { it > 4f }
        }.sorted()
        if (heights.isEmpty()) return 48f
        // Use a high percentile so diacritics/tall letters still fit in the virtual line box.
        val idx = (heights.size * 0.8).toInt().coerceIn(0, heights.lastIndex)
        return (heights[idx] * 1.8f).coerceAtLeast(36f)
    }

    private fun joinRecognizedParts(parts: List<String>): String {
        if (parts.isEmpty()) return ""
        // ML Kit often returns a leading space when precontext helped detect a word break.
        return parts.joinToString(" ") { it.trim() }
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun close() {
        enRecognizer?.close()
        enRecognizer = null
        viRecognizer?.close()
        viRecognizer = null
        recognizeExecutor.shutdownNow()
    }

    /** A single sample on a stroke, in canvas/view coordinates. */
    data class StrokePoint(val x: Float, val y: Float, val t: Long)
}
