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
 * Step 2: raw strokes -> English text, fully offline after the first
 * model download. No network call happens per-recognition — only once,
 * up front, to fetch the "en" model (a few MB).
 */
class HandwritingRecognizer {

    private var recognizer: DigitalInkRecognizer? = null
    private val modelManager = RemoteModelManager.getInstance()
    private var model: DigitalInkRecognitionModel? = null

    /**
     * Downloads the English recognition model if not already present.
     * Call this once, e.g. in onCreate. Requires WiFi the first time;
     * after that the model is cached on-device and works fully offline.
     */
    fun setup(onReady: () -> Unit, onError: (Exception) -> Unit) {
        val modelIdentifier = try {
            DigitalInkRecognitionModelIdentifier.fromLanguageTag("en")
        } catch (e: Exception) {
            onError(IllegalStateException("Could not find ML Kit model for language tag 'en'", e))
            return
        }
        if (modelIdentifier == null) {
            onError(IllegalStateException("ML Kit does not have a Digital Ink model for English on this device"))
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
                Log.d(TAG, "English model ready (offline from now on)")
                onReady()
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Model download failed — WiFi required for first time", e)
                onError(e)
            }
    }

    /** Runs recognition on the ink captured so far. Fully offline. */
    fun recognize(ink: Ink, onResult: (String) -> Unit, onError: (Exception) -> Unit) {
        val r = recognizer
        if (r == null) {
            onError(IllegalStateException("Recognizer not ready — call setup() first and wait for onReady"))
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
                Log.e(TAG, "Recognition failed", e)
                onError(e)
            }
    }

    fun close() {
        recognizer?.close()
        recognizer = null
    }
}
