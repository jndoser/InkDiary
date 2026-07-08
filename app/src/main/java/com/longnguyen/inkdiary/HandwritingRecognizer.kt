package com.longnguyen.inkdiary

import android.util.Log
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.DigitalInkRecognizer
import com.google.mlkit.vision.digitalink.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.Ink

private const val TAG = "HandwritingRecognizer"

/**
 * Step 2: raw strokes -> Vietnamese text, fully offline after the first
 * model download. No network call happens per-recognition — only once,
 * up front, to fetch the "vi" model (a few MB).
 */
class HandwritingRecognizer {

    private var recognizer: DigitalInkRecognizer? = null
    private val modelManager = RemoteModelManager.getInstance()
    private var model: DigitalInkRecognitionModel? = null

    /**
     * Downloads the Vietnamese recognition model if not already present.
     * Call this once, e.g. in onCreate. Requires WiFi the first time;
     * after that the model is cached on-device and works fully offline.
     */
    fun setup(onReady: () -> Unit, onError: (Exception) -> Unit) {
        val modelIdentifier = try {
            DigitalInkRecognitionModelIdentifier.fromLanguageTag("vi")
        } catch (e: Exception) {
            onError(IllegalStateException("Không tìm được model ML Kit cho tag ngôn ngữ 'vi'", e))
            return
        }
        if (modelIdentifier == null) {
            onError(IllegalStateException("ML Kit không có model Digital Ink cho tiếng Việt trên máy này"))
            return
        }

        val builtModel = DigitalInkRecognitionModel.builder(modelIdentifier).build()
        model = builtModel

        val downloadConditions = DownloadConditions.Builder()
            .requireWifi()
            .build()

        modelManager.download(builtModel, downloadConditions)
            .addOnSuccessListener {
                recognizer = DigitalInkRecognition.getClient(
                    DigitalInkRecognizerOptions.builder(builtModel).build()
                )
                Log.d(TAG, "Model tiếng Việt sẵn sàng (offline từ giờ trở đi)")
                onReady()
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Tải model thất bại — cần WiFi cho lần đầu tiên", e)
                onError(e)
            }
    }

    /** Runs recognition on the ink captured so far. Fully offline. */
    fun recognize(ink: Ink, onResult: (String) -> Unit, onError: (Exception) -> Unit) {
        val r = recognizer
        if (r == null) {
            onError(IllegalStateException("Recognizer chưa sẵn sàng — gọi setup() trước và đợi onReady"))
            return
        }
        r.recognize(ink)
            .addOnSuccessListener { result ->
                // candidates are ranked by confidence; take the top guess for now.
                val text = result.candidates.firstOrNull()?.text.orEmpty()
                Log.d(TAG, "Recognized: \"$text\" (${result.candidates.size} candidates)")
                onResult(text)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Recognition thất bại", e)
                onError(e)
            }
    }

    fun close() {
        recognizer?.close()
        recognizer = null
    }
}
