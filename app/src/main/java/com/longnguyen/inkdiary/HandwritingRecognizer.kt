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
 * Step 2: raw strokes -> text, fully offline after the first
 * model download. Supports both English ("en") and Vietnamese ("vi").
 * No network call happens per-recognition — only once,
 * up front, to fetch each model (a few MB each).
 */
class HandwritingRecognizer {

    private val modelManager = RemoteModelManager.getInstance()

    // One recognizer per language
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

        // --- Download English model ---
        downloadModel("en",
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

        // --- Download Vietnamese model ---
        downloadModel("vi",
            onSuccess = { recognizer ->
                viRecognizer = recognizer
                viReady = true
                Log.d(TAG, "Vietnamese model ready (offline from now on)")
                checkBothReady()
            },
            onFailure = { e ->
                // Vietnamese model failure is non-fatal; we still have English
                Log.w(TAG, "Vietnamese model download failed — VI recognition unavailable", e)
                viReady = true // Mark as "done" so we don't block forever
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
            onFailure(IllegalStateException("Could not find ML Kit model for language tag '$languageTag'", e))
            return
        }
        if (modelIdentifier == null) {
            onFailure(IllegalStateException("ML Kit does not have a Digital Ink model for '$languageTag'"))
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
            .addOnFailureListener { e ->
                onFailure(e)
            }
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

    /** Runs recognition on the ink captured so far, using the currently active language. Fully offline. */
    fun recognize(ink: Ink, onResult: (String) -> Unit, onError: (Exception) -> Unit) {
        val r = when (currentLanguage) {
            "vi" -> viRecognizer ?: enRecognizer
            else -> enRecognizer
        }
        if (r == null) {
            onError(IllegalStateException("Recognizer not ready — call setup() first and wait for onReady"))
            return
        }
        r.recognize(ink)
            .addOnSuccessListener { result ->
                // candidates are ranked by confidence; take the top guess for now.
                val text = result.candidates.firstOrNull()?.text.orEmpty()
                Log.d(TAG, "Recognized ($currentLanguage): \"$text\" (${result.candidates.size} candidates)")
                onResult(text)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Recognition failed ($currentLanguage)", e)
                onError(e)
            }
    }

    fun close() {
        enRecognizer?.close()
        enRecognizer = null
        viRecognizer?.close()
        viRecognizer = null
    }
}
