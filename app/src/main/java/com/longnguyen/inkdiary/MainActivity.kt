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
        Log.d("InkDiaryDebug", "replyView set to GONE after delay")
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

        if (preferred == Config.LLM_ON_DEVICE) {
            val lang = getActiveLanguageLabel()
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
                                    if (recognizerIsReady) debugText.text = "Ready (On-Device, $lang). Write something."
                                }
                            },
                            onError = { e ->
                                runOnUiThread { debugText.text = "Error loading model: ${e.message}" }
                            }
                        )
                    } else {
                        runOnUiThread { debugText.text = "Download failed: ${result.exceptionOrNull()?.message}" }
                    }
                }
            } else {
                debugText.text = "Loading on-device model..."
                (llmService as? OnDeviceLLMService)?.loadModel(
                    onLoaded = {
                        runOnUiThread {
                            if (recognizerIsReady) debugText.text = "Ready (On-Device, $lang). Write something."
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
            val lang = getActiveLanguageLabel()
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
        
        Log.d("InkDiaryDebug", "BuildConfig Gemini: ${BuildConfig.GEMINI_API_KEY}")
        Log.d("InkDiaryDebug", "BuildConfig Samba: ${BuildConfig.SAMBANOVA_API_KEY}")

        try {
            EpdController.setViewDefaultUpdateMode(debugText, UpdateMode.GC)
        } catch (e: Exception) {}

        findViewById<View>(R.id.btnHistory).setOnClickListener {
            val intent = android.content.Intent(this, HistoryActivity::class.java)
            startActivity(intent)
        }

        // Ensure replyView is hidden initially
        replyView.visibility = View.GONE

    // Stylus nib down → clear the AI response so the user can write fresh.
        // NOTE: All Onyx RawInputCallback methods run on the MAIN thread on Boox (confirmed
        // via logcat: PID==TID). So the listener is called on the main thread — no post{}
        // needed, but runOnUiThread{} is kept as a safe guard (it's a no-op on main thread).
        //
        // We use clearAll() (not clearForNewSession()) because clearAll() calls
        // touchHelper.setRawDrawingEnabled(false/true) which resets the Onyx raw drawing
        // overlay — essential for clearing ghost pixels from the e-ink screen.
        inkCanvas.setOnTouchDownListener {
            val hasActiveContent = isShowingAiContent

            Log.d("InkDiaryDebug", "setOnTouchDownListener: hasActiveContent=$hasActiveContent")

            if (hasActiveContent) {
                Log.d("InkDiaryDebug", "Clearing AI response and status.")
                isShowingAiContent = false
                currentSessionId++
                
                // Cancel any pending visibility hide animations
                handler.removeCallbacks(hideViewsRunnable)

                // 1. Keep views VISIBLE so Android actually calls onDraw().
                // If set to INVISIBLE or GONE immediately, Android skips drawing,
                // and the Onyx E-ink controller NEVER physically clears the screen!
                
                // Clear replyView content (blanks text, sets GC mode internally, invalidates)
                // This triggers a GC refresh on the HandwrittenReplyView.
                replyView.clear()
                
                // Clear data content
                debugText.text = ""
                debugText.setBackgroundColor(Color.TRANSPARENT)
                try {
                    EpdController.setViewDefaultUpdateMode(debugText, UpdateMode.GC)
                    debugText.invalidate()
                } catch (e: Exception) {}
                
                // 2. Re-enable interaction
                inkCanvas.setInteractionEnabled(true)

                // 3. Clear the ink overlay AFTER a short delay (50ms) to avoid
                // conflicting GC refreshes on the e-ink controller. The replyView.clear()
                // above triggers its own GC; if clearAll() fires simultaneously, one GC
                // can cancel the other, leaving ghost text on the e-ink screen.
                handler.postDelayed({
                    inkCanvas.clearAll(suppressAnimationForGc = true)
                }, 50)

                // 4. Final hide (GONE) after 700ms (50ms delay + 650ms for GC refresh)
                handler.postDelayed(hideViewsRunnable, 700)
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

            runOnUiThread {
                debugText.visibility = View.VISIBLE
                debugText.text = "Recognizing..."
                isShowingAiContent = true
                Log.d("InkDiaryDebug", "setOnPauseListener: set isShowingAiContent=true")
            }
            val sessionId = ++currentSessionId
            val strokePoints = inkCanvas.exportStrokePoints()
            val canvasW = inkCanvas.width.toFloat().coerceAtLeast(1f)
            val canvasH = inkCanvas.height.toFloat().coerceAtLeast(1f)

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
                        if (text.isBlank()) {
                            debugText.text = "No text recognized"
                        } else {
                            debugText.text = "Recognized: \"$text\""
                        }
                        debugText.setBackgroundColor(Color.parseColor("#333333"))

                        // 2. Clear the ink immediately so the user knows it's accepted
                        inkCanvas.clearAll()

                        try {
                            EpdController.setViewDefaultUpdateMode(debugText, UpdateMode.DU)
                            debugText.invalidate()
                        } catch(e:Exception){}
                    }

                    if (text.isBlank()) {
                        runOnUiThread {
                            if (sessionId != currentSessionId) return@runOnUiThread
                            replyView.visibility = View.VISIBLE
                            handler.postDelayed({
                                if (sessionId == currentSessionId) {
                                    replyView.setTextAndAnimate("(Could not recognize handwriting)")
                                }
                            }, 300)
                            isShowingAiContent = true
                        }
                        return@recognize
                    }

                    val llmName = getActiveLLMName()
                    Log.d("MainActivity", "Recognized text: '$text'. Using $llmName")

                    lifecycleScope.launch {
                        // Show status that we are now calling the API
                        runOnUiThread {
                            if (sessionId != currentSessionId) return@runOnUiThread
                            debugText.text = "Recognized: \"$text\"\n(Thinking...)"
                            // Show thinking message in the main area
                            replyView.visibility = View.VISIBLE
                            handler.postDelayed({
                                if (sessionId == currentSessionId) {
                                    replyView.setTextAndAnimate("Thinking...")
                                }
                            }, 300)
                            isShowingAiContent = true
                        }

                        // Cloud APIs need internet; on-device LLM works fully offline.
                        if (!isOnDeviceLLM() && !withContext(Dispatchers.IO) { isOnline() }) {
                            runOnUiThread {
                                if (sessionId != currentSessionId) return@runOnUiThread
                                debugText.text = "Recognized: \"$text\"\n(Offline)"
                                replyView.visibility = View.VISIBLE
                                handler.postDelayed({
                                    if (sessionId == currentSessionId) {
                                        replyView.setTextAndAnimate("I'm offline. Please check your WiFi connection, or switch to On-Device (Offline) AI.")
                                    }
                                }, 300)
                                isShowingAiContent = true
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

                                // Bounce the Onyx touch layer to prevent stylus input from freezing
                                inkCanvas.bounceTouchHelper()

                                debugText.text = "Recognized: \"$text\"" // Keep the recognized text clean
                                replyView.visibility = View.VISIBLE
                                
                                // Delay starting the animation slightly (300ms) to ensure the 
                                // inkCanvas.clearAll() GC refresh has physically completed 
                                // on the screen before the typewriter DU mode starts.
                                handler.postDelayed({
                                    if (sessionId == currentSessionId) {
                                        replyView.setTextAndAnimate(response)
                                    }
                                }, 300)

                                isShowingAiContent = true
                                Log.d("InkDiaryDebug", "UI Success: set isShowingAiContent=true")
                                try { EpdController.setViewDefaultUpdateMode(debugText, UpdateMode.GC); debugText.invalidate() } catch(e:Exception){}
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

                                // Bounce the Onyx touch layer to prevent stylus input from freezing
                                inkCanvas.bounceTouchHelper()

                                debugText.setBackgroundColor(Color.RED)
                                debugText.text = "Recognized: \"$text\"\nERROR: $userFriendlyError"
                                replyView.visibility = View.VISIBLE
                                handler.postDelayed({
                                    if (sessionId == currentSessionId) {
                                        replyView.setTextAndAnimate("I encountered an error: $userFriendlyError")
                                    }
                                }, 300)
                                isShowingAiContent = true
                                Log.d("InkDiaryDebug", "UI Error: set isShowingAiContent=true")

                                handler.postDelayed({
                                    try {
                                        EpdController.setViewDefaultUpdateMode(debugText, UpdateMode.GC)
                                        debugText.invalidate()
                                    } catch(e:Exception){}
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

    override fun onDestroy() {
        recognizer.close()
        super.onDestroy()
    }
}
