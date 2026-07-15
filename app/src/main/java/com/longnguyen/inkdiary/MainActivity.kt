package com.longnguyen.inkdiary

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.util.Log
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.longnguyen.inkdiary.data.AppDatabase
import com.longnguyen.inkdiary.data.ConversationEntry
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.api.device.epd.UpdateMode
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ScrollView
import android.widget.Spinner
import android.os.Handler
import android.os.Looper
import android.graphics.PixelFormat
import android.graphics.Color
import android.widget.FrameLayout

class MainActivity : AppCompatActivity() {

    private lateinit var rootLayout: FrameLayout
    private lateinit var inkCanvas: InkCanvasView
    private lateinit var replyView: HandwrittenReplyView
    private lateinit var debugText: TextView
    private val recognizer = HandwritingRecognizer()
    private lateinit var llmService: LLMService
    private lateinit var database: AppDatabase
    private var currentSessionId = 0
    private var tapCount = 0
    private val handler = Handler(Looper.getMainLooper())
    private val tapRunnable = Runnable {
        Log.d("MultiTap", "Executing action for tap count: $tapCount")
        when (tapCount) {
            3 -> showApiKeyDialog()
            4 -> toggleDebugVisibility()
        }
        tapCount = 0
    }

    private val hideViewsRunnable = Runnable {
        replyView.resetContent()
        replyView.visibility = View.GONE
        if (Config.isDebugEnabled(this@MainActivity)) {
            debugText.visibility = View.VISIBLE
            debugText.text = "Ready"
            debugText.setBackgroundColor(Color.parseColor("#333333"))
            try {
                EpdController.setViewDefaultUpdateMode(debugText, UpdateMode.DU)
                debugText.invalidate()
            } catch (e: Exception) {}
            Log.d("InkDiaryDebug", "debugText reset to Ready (visible) after delay")
        } else {
            debugText.visibility = View.GONE
            Log.d("InkDiaryDebug", "debugText set to GONE after delay")
        }
    }

    // Invalidates in-flight white-wipe → reveal callbacks when the user starts a new note.
    private var replyPresentGeneration = 0
    private var wipeInFlight = false
    private var pendingAfterWipeMessage: String? = null
    /** Uptime ms of the last opaque white surface wipe (0 = never). */
    private var lastWhiteWipeAtMs = 0L
    /** True while the pen surface is still an opaque white cover (not yet opened for reply). */
    private var whiteCoverActive = false
    /** How long after a white wipe before we start typewriter text. */
    private val whiteWipeSettleMs = 280L

    /** Cancel pending hide/show reply work and prepare a clean reply surface. */
    private fun cancelPendingReplyUi() {
        handler.removeCallbacks(hideViewsRunnable)
        replyPresentGeneration++
        wipeInFlight = false
        pendingAfterWipeMessage = null
        inkCanvas.stopReplyAnimation()
        replyView.resetContent()
    }

    private fun markWhiteWiped() {
        lastWhiteWipeAtMs = android.os.SystemClock.uptimeMillis()
        whiteCoverActive = true
    }

    /**
     * Draw AI text on the TOP pen surface. Pen overlay stays off.
     * Never GC and never open transparent — both re-show the previous answer on Boox.
     */
    private fun showReplyMessage(sessionId: Int, message: String, delayMs: Long = 80L) {
        if (sessionId != currentSessionId) return
        handler.removeCallbacks(hideViewsRunnable)
        isShowingAiContent = true
        if (wipeInFlight) {
            pendingAfterWipeMessage = message
            return
        }
        replyView.visibility = View.GONE
        inkCanvas.showReplyOnSurface(message, startDelayMs = delayMs)
        whiteCoverActive = true
    }

    /**
     * After white wipe (pen overlay OFF), draw [message] on the pen surface.
     * No GC. No transparent open. No pen-overlay toggle.
     */
    private fun presentReplyAfterWipe(sessionId: Int, message: String) {
        if (sessionId != currentSessionId) return
        handler.removeCallbacks(hideViewsRunnable)
        isShowingAiContent = true
        pendingAfterWipeMessage = message

        if (wipeInFlight) return

        val gen = ++replyPresentGeneration
        wipeInFlight = true

        val now = android.os.SystemClock.uptimeMillis()

        if (!whiteCoverActive) {
            // Pen OFF + solid white. Never re-enable pen here (would flash previous AI).
            inkCanvas.wipeToWhite(useFullGc = false, suppressAnimationForGc = false, reenablePen = false)
            markWhiteWiped()
        }
        replyView.visibility = View.GONE

        val settleBase = if (lastWhiteWipeAtMs > 0L) lastWhiteWipeAtMs else now
        val delay = (whiteWipeSettleMs - (now - settleBase)).coerceAtLeast(80L)

        handler.postDelayed({
            wipeInFlight = false
            if (sessionId != currentSessionId || gen != replyPresentGeneration) return@postDelayed
            val msg = pendingAfterWipeMessage ?: message
            pendingAfterWipeMessage = null
            replyView.visibility = View.GONE
            inkCanvas.showReplyOnSurface(msg, startDelayMs = 80L)
            whiteCoverActive = true
            Log.d("InkDiaryDebug", "presentReplyAfterWipe: surface text only, pen overlay off")
        }, delay)
    }

    private var recognizerIsReady = false

    // True whenever the AI response area is active (thinking, streaming, done, or error)
    // Used to decide whether stylus-down should trigger a clear for new input
    private var isShowingAiContent = false

    private fun getTodayDate(): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
    }

    private fun getActiveLLMName(): String {
        return when (Config.getPreferredLLM(this)) {
            Config.LLM_GEMINI -> "Gemini"
            Config.LLM_SAMBANOVA -> "SambaNova"
            Config.LLM_ON_DEVICE -> "On-Device"
            else -> "Unknown"
        }
    }

    private fun isOnDeviceLLM(): Boolean {
        return Config.getPreferredLLM(this) == Config.LLM_ON_DEVICE
    }

    private fun getActiveLanguageLabel(): String {
        return if (Config.getRecognitionLanguage(this) == Config.LANG_VIETNAMESE) "VI" else "EN"
    }

    private fun isOnline(): Boolean {
        val connectivityManager = getSystemService(ConnectivityManager::class.java)
        val currentNetwork = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(currentNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun setupLLM() {
        llmService = LLMFactory.getService(this)

        // Apply saved language preference to the recognizer
        val savedLang = Config.getRecognitionLanguage(this)
        recognizer.setLanguage(savedLang)

        val preferred = Config.getPreferredLLM(this)
        val geminiKey = Config.getGeminiApiKey(this)
        val sambaKey = Config.getSambaNovaApiKey(this)
        val lang = getActiveLanguageLabel()

        if (preferred == Config.LLM_ON_DEVICE) {
            if (!ModelDownloadManager.isModelDownloaded(this)) {
                debugText.text = "Downloading offline AI model (0%). Please wait..."
                lifecycleScope.launch {
                    val result = ModelDownloadManager.downloadModel(this@MainActivity) { downloaded, total ->
                        val percent = if (total > 0) (downloaded * 100 / total).toInt() else 0
                        runOnUiThread {
                            debugText.text = "Downloading offline AI model ($percent%). Please wait..."
                        }
                    }
                    if (result.isSuccess) {
                        runOnUiThread { debugText.text = "Loading on-device model..." }
                        (llmService as? OnDeviceLLMService)?.loadModel(
                            onLoaded = {
                                runOnUiThread {
                                    if (recognizerIsReady) {
                                        debugText.text = "Ready (On-Device, $lang). Write something."
                                    }
                                }
                            },
                            onError = { e ->
                                runOnUiThread { debugText.text = "Error loading model: ${e.message}" }
                            }
                        )
                    } else {
                        runOnUiThread {
                            debugText.text =
                                "Download failed: ${result.exceptionOrNull()?.message}"
                        }
                    }
                }
            } else {
                debugText.text = "Loading on-device model..."
                (llmService as? OnDeviceLLMService)?.loadModel(
                    onLoaded = {
                        runOnUiThread {
                            if (recognizerIsReady) {
                                debugText.text = "Ready (On-Device, $lang). Write something."
                            }
                        }
                    },
                    onError = { e ->
                        runOnUiThread { debugText.text = "Error loading model: ${e.message}" }
                    }
                )
            }
        } else if (geminiKey.isBlank() && sambaKey.isBlank()) {
            debugText.text = "GUIDE: API Key is missing. Tap 3 times (finger) to enter API Keys."
        } else {
            val active = if (preferred == Config.LLM_GEMINI) "Gemini" else "SambaNova"
            if (recognizerIsReady) {
                debugText.text = "Ready ($active, $lang). Write something."
            }
        }
    }

    private fun showApiKeyDialog() {
        val context = this
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }

        val geminiInput = EditText(context).apply {
            hint = "Gemini API Key"
            setText(Config.getGeminiApiKey(context))
        }

        val sambaInput = EditText(context).apply {
            hint = "SambaNova API Key"
            setText(Config.getSambaNovaApiKey(context))
        }

        val currentLLM = Config.getPreferredLLM(context)

        val geminiLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply { text = "Gemini API Key:" })
            addView(geminiInput)
            visibility = if (currentLLM == Config.LLM_GEMINI) View.VISIBLE else View.GONE
        }

        val sambaLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply { text = "SambaNova API Key:" })
            addView(sambaInput)
            visibility = if (currentLLM == Config.LLM_SAMBANOVA) View.VISIBLE else View.GONE
        }

        val options = arrayOf("Gemini", "SambaNova", "On-Device (Offline)")
        val spinner = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, options)

            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                    geminiLayout.visibility = if (position == 0) View.VISIBLE else View.GONE
                    sambaLayout.visibility = if (position == 1) View.VISIBLE else View.GONE
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }

            setSelection(when (currentLLM) {
                Config.LLM_GEMINI -> 0
                Config.LLM_SAMBANOVA -> 1
                else -> 2
            })
        }

        // Language selector
        val currentLang = Config.getRecognitionLanguage(context)
        val langOptions = arrayOf("English", "Tiếng Việt")
        val langSpinner = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, langOptions)
            setSelection(if (currentLang == Config.LANG_VIETNAMESE) 1 else 0)
        }

        layout.addView(TextView(context).apply { text = "Preferred LLM:" })
        layout.addView(spinner)
        layout.addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(1, 20) })
        layout.addView(geminiLayout)
        layout.addView(sambaLayout)
        layout.addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(1, 20) })
        layout.addView(TextView(context).apply { text = "Handwriting Language:" })
        layout.addView(langSpinner)

        val scrollView = ScrollView(context).apply { addView(layout) }

        AlertDialog.Builder(context)
            .setTitle("AI Configuration")
            .setView(scrollView)
            .setPositiveButton("Save") { _, _ ->
                Config.saveGeminiApiKey(context, geminiInput.text.toString())
                Config.saveSambaNovaApiKey(context, sambaInput.text.toString())

                val preferred = when (spinner.selectedItemPosition) {
                    0 -> Config.LLM_GEMINI
                    1 -> Config.LLM_SAMBANOVA
                    else -> Config.LLM_ON_DEVICE
                }
                // Re-selecting on-device clears a previous native-crash lockout.
                if (preferred == Config.LLM_ON_DEVICE) {
                    Config.clearOnDeviceUnsafe(context)
                }
                Config.setPreferredLLM(context, preferred)

                val selectedLang = if (langSpinner.selectedItemPosition == 1) Config.LANG_VIETNAMESE else Config.LANG_ENGLISH
                Config.setRecognitionLanguage(context, selectedLang)

                setupLLM()
                Toast.makeText(context, "Settings saved", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toggleDebugVisibility() {
        val isCurrentlyVisible = debugText.visibility == View.VISIBLE
        val newState = !isCurrentlyVisible
        debugText.visibility = if (newState) View.VISIBLE else View.GONE
        Config.setDebugEnabled(this, newState)
        Toast.makeText(this, if (newState) "Debug Mode On" else "Debug Mode Off", Toast.LENGTH_SHORT).show()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // Finger tap detection (3 taps = API key dialog, 4 taps = debug toggle)
        if (ev.actionMasked == MotionEvent.ACTION_UP && ev.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER) {
            tapCount++
            Log.d("MultiTap", "Tap counted: $tapCount")
            handler.removeCallbacks(tapRunnable)
            handler.postDelayed(tapRunnable, 600)
        }
        return super.dispatchTouchEvent(ev)
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Match the background for ZOrderOnTop transparency
        window.setBackgroundDrawableResource(android.R.color.white)

        setContentView(R.layout.activity_main)

        rootLayout = findViewById(R.id.rootLayout)
        inkCanvas = findViewById(R.id.inkCanvas)
        replyView = findViewById(R.id.replyView)
        debugText = findViewById(R.id.debugRecognizedText)
        database = AppDatabase.getDatabase(this)

        debugText.visibility = if (Config.isDebugEnabled(this)) View.VISIBLE else View.GONE
        debugText.setTextColor(Color.WHITE)
        debugText.text = "Initializing..."

        // If the previous process died mid native-load, recover to cloud for this session.
        // User can re-enable On-Device from settings (that clears the lockout).
        if (Config.recoverFromOnDeviceCrashIfNeeded(this)) {
            Toast.makeText(
                this,
                "On-device AI crashed last launch. Switched to cloud AI. You can re-enable On-Device in settings.",
                Toast.LENGTH_LONG
            ).show()
        } else {
            // Clear lockouts set by an earlier workaround; native lib packaging is fixed now.
            Config.clearOnDeviceUnsafe(this)
        }

        Log.d("InkDiaryDebug", "BuildConfig Gemini: ${BuildConfig.GEMINI_API_KEY}")
        Log.d("InkDiaryDebug", "BuildConfig Samba: ${BuildConfig.SAMBANOVA_API_KEY}")

        try {
            EpdController.setViewDefaultUpdateMode(debugText, UpdateMode.GC)
        } catch (e: Exception) {}

        findViewById<View>(R.id.btnHistory).setOnClickListener {
            val intent = android.content.Intent(this, HistoryActivity::class.java)
            startActivity(intent)
        }

        // Reply text is drawn on the pen SurfaceView only. Keep this under-layer GONE
        // so it can never contribute previous-answer pixels on e-ink.
        replyView.resetContent()
        replyView.visibility = View.GONE

        // Stylus nib down → clear the AI response so the user can write fresh.
        // NOTE: All Onyx RawInputCallback methods run on the MAIN thread on Boox (confirmed
        // via logcat: PID==TID). So the listener is called on the main thread — no post{}
        // needed, but runOnUiThread{} is kept as a safe guard (it's a no-op on main thread).
        inkCanvas.setOnTouchDownListener {
            // Also check canvas reply text — isShowingAiContent can desync after edge cases.
            val hasActiveContent = isShowingAiContent || inkCanvas.hasReplyContent()

            Log.d(
                "InkDiaryDebug",
                "setOnTouchDownListener: hasActiveContent=$hasActiveContent " +
                    "(flag=$isShowingAiContent, reply=${inkCanvas.hasReplyContent()})"
            )

            if (hasActiveContent) {
                Log.d("InkDiaryDebug", "Clearing AI response and status.")
                isShowingAiContent = false
                currentSessionId++

                // Cancel any pending visibility hide / reply animations
                cancelPendingReplyUi()

                // Physically erase previous AI answer from the e-ink panel, then
                // re-enable a clean pen for the new note. (Do not call
                // setInteractionEnabled in a way that turns raw drawing back on
                // before the wipe — that kept the previous answer on screen.)
                inkCanvas.setInteractionEnabled(true)
                inkCanvas.dismissReplyForNewWriting()
                replyView.resetContent()
                replyView.visibility = View.GONE
                markWhiteWiped()

                // Clear data content
                debugText.text = ""
                debugText.setBackgroundColor(Color.TRANSPARENT)
                try {
                    EpdController.setViewDefaultUpdateMode(debugText, UpdateMode.DU)
                    debugText.invalidate()
                } catch (e: Exception) {}

                handler.postDelayed(hideViewsRunnable, 900)
            }
        }

        // One-time model download
        debugText.text = "Downloading language models..."
        recognizer.setup(
            onReady = {
                runOnUiThread {
                    recognizerIsReady = true
                    setupLLM()
                }
            },
            onError = { e ->
                runOnUiThread {
                    debugText.text = "Model download error: ${e.message}"
                    Toast.makeText(this, "WiFi required for first-time model download", Toast.LENGTH_LONG).show()
                }
            }
        )

        // Ink recognition flow
        inkCanvas.setOnPauseListener {
            Log.d("MainActivity", "Pause detected, checking content...")
            if (!inkCanvas.hasContent()) {
                Log.d("MainActivity", "No content to recognize")
                return@setOnPauseListener
            }

            // Export strokes BEFORE wiping the surface.
            val sessionId = ++currentSessionId
            val strokePoints = inkCanvas.exportStrokePoints()
            val canvasW = inkCanvas.width.toFloat().coerceAtLeast(1f)
            val canvasH = inkCanvas.height.toFloat().coerceAtLeast(1f)

            runOnUiThread {
                handler.removeCallbacks(hideViewsRunnable)
                isShowingAiContent = true
                debugText.visibility = View.VISIBLE
                debugText.text = "Recognizing..."
                // Pen overlay OFF + solid white. Keep pen OFF through Thinking/reply
                // (re-enabling the overlay was replaying the previous AI frame on Boox).
                inkCanvas.wipeToWhite(
                    useFullGc = false,
                    suppressAnimationForGc = false,
                    reenablePen = false
                )
                replyView.visibility = View.GONE
                markWhiteWiped()
                Log.d("InkDiaryDebug", "setOnPauseListener: pen off + white wipe")
            }

            Log.d(
                "MainActivity",
                "Calling ML Kit recognizer... strokes=${strokePoints.size}, " +
                    "canvas=${canvasW.toInt()}x${canvasH.toInt()}, lang=${recognizer.currentLanguage}"
            )
            recognizer.recognize(
                strokes = strokePoints,
                canvasWidth = canvasW,
                canvasHeight = canvasH,
                onResult = { text ->
                    if (sessionId != currentSessionId) {
                        Log.d("MainActivity", "Ignoring stale recognition result")
                        return@recognize
                    }
                    Log.d("MainActivity", "ML Kit result: '$text'")

                    // 1. Immediately update UI before ANY network/processing happens
                    runOnUiThread {
                        if (sessionId != currentSessionId) return@runOnUiThread
                        if (text.isBlank()) {
                            debugText.text = "No text recognized"
                        } else {
                            debugText.text = "Recognized: \"$text\""
                        }
                        debugText.setBackgroundColor(Color.parseColor("#333333"))

                        try {
                            EpdController.setViewDefaultUpdateMode(debugText, UpdateMode.DU)
                            debugText.invalidate()
                        } catch (e: Exception) {}
                    }

                    if (text.isBlank()) {
                        runOnUiThread {
                            if (sessionId != currentSessionId) return@runOnUiThread
                            // Surface already white from pause; settle + reveal message.
                            presentReplyAfterWipe(sessionId, "(Could not recognize handwriting)")
                        }
                        return@recognize
                    }

                    val llmName = getActiveLLMName()
                    Log.d("MainActivity", "Recognized text: '$text'. Using $llmName")

                    lifecycleScope.launch {
                        // Surface was wiped white at pause; reveal "Thinking..." after settle.
                        runOnUiThread {
                            if (sessionId != currentSessionId) return@runOnUiThread
                            debugText.text = "Recognized: \"$text\"\n(Thinking...)"
                            presentReplyAfterWipe(sessionId, "Thinking...")
                        }

                        // Cloud APIs need internet; on-device LLM works fully offline.
                        if (!isOnDeviceLLM() && !withContext(Dispatchers.IO) { isOnline() }) {
                            runOnUiThread {
                                if (sessionId != currentSessionId) return@runOnUiThread
                                debugText.text = "Recognized: \"$text\"\n(Offline)"
                                showReplyMessage(
                                    sessionId,
                                    "I'm offline. Please check your WiFi connection, or switch to On-Device (Offline) AI."
                                )
                            }
                            return@launch
                        }

                        val today = getTodayDate()
                        val conversationDao = database.conversationDao()
                        val historyEntries = withContext(Dispatchers.IO) {
                            conversationDao.getConversationsByDate(today)
                        }

                        val history = historyEntries.map { entry -> ChatMessage(entry.role, entry.content) }

                        val response = llmService.generateResponse(text, history) ?: "API_ERROR: No response"

                        if (sessionId != currentSessionId) {
                            Log.d("MainActivity", "Ignoring stale AI response")
                            return@launch
                        }

                        if (!response.startsWith("API_ERROR:")) {
                            // Success path
                            withContext(Dispatchers.IO) {
                                conversationDao.insert(ConversationEntry(date = today, role = "user", content = text))
                                conversationDao.insert(ConversationEntry(date = today, role = "model", content = response))
                            }
                            runOnUiThread {
                                // Guard: user may have started new writing while AI was thinking
                                if (sessionId != currentSessionId) {
                                    Log.d("MainActivity", "Discarding stale AI response (user already started new session)")
                                    return@runOnUiThread
                                }
                                Log.d("MainActivity", "Updating UI with AI response")

                                // Do NOT bounce/re-enable the pen overlay here — that recommits
                                // the previous AI framebuffer on Boox (full answer flash).

                                debugText.text = "Recognized: \"$text\"" // Keep the recognized text clean
                                showReplyMessage(sessionId, response, delayMs = 100L)
                                Log.d("InkDiaryDebug", "UI Success: set isShowingAiContent=true")
                                try {
                                    EpdController.setViewDefaultUpdateMode(debugText, UpdateMode.DU)
                                    debugText.invalidate()
                                } catch (e: Exception) {}
                            }
                        } else {
                            // Error path
                            val userFriendlyError = response.removePrefix("API_ERROR: ")
                            Log.e("MainActivity", "AI Error: $userFriendlyError")
                            runOnUiThread {
                                // Guard: user may have started new writing while AI was thinking
                                if (sessionId != currentSessionId) {
                                    Log.d("MainActivity", "Discarding stale AI error (user already started new session)")
                                    return@runOnUiThread
                                }

                                debugText.setBackgroundColor(Color.RED)
                                debugText.text = "Recognized: \"$text\"\nERROR: $userFriendlyError"
                                showReplyMessage(sessionId, "I encountered an error: $userFriendlyError")
                                Log.d("InkDiaryDebug", "UI Error: set isShowingAiContent=true")

                                handler.postDelayed({
                                    try {
                                        EpdController.setViewDefaultUpdateMode(debugText, UpdateMode.GC)
                                        debugText.invalidate()
                                    } catch (e: Exception) {}
                                }, 500)
                            }
                        }
                    }
                },
                onError = { e ->
                    runOnUiThread {
                        if (sessionId != currentSessionId) return@runOnUiThread
                        debugText.text = "Recognition error: ${e.message}"
                    }
                }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        // After returning from History / sleep, re-prime the Onyx pen pipeline
        // so the first stylus stroke is not cold.
        if (::inkCanvas.isInitialized) {
            inkCanvas.post { inkCanvas.warmUpPenPipeline() }
        }
    }

    override fun onDestroy() {
        recognizer.close()
        super.onDestroy()
    }
}
