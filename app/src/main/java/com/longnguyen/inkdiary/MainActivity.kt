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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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

class MainActivity : AppCompatActivity() {

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

    private var recognizerIsReady = false

    // True whenever the AI response area is active (thinking, streaming, done, or error)
    // Used to decide whether stylus-down should trigger a clear for new input
    private var isShowingAiContent = false

    private fun getTodayDate(): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
    }

    private fun getActiveLLMName(): String {
        return if (Config.getPreferredLLM(this) == Config.LLM_GEMINI) "Gemini" else "SambaNova"
    }

    private fun isOnline(): Boolean {
        val connectivityManager = getSystemService(ConnectivityManager::class.java)
        val currentNetwork = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(currentNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun setupLLM() {
        llmService = LLMFactory.getService(this)
        
        val preferred = Config.getPreferredLLM(this)
        val geminiKey = Config.getGeminiApiKey(this)
        val sambaKey = Config.getSambaNovaApiKey(this)

        if (geminiKey.isBlank() && sambaKey.isBlank()) {
            debugText.text = "GUIDE: API Key is missing. Tap 3 times (finger) to enter API Keys."
        } else {
            val active = if (preferred == Config.LLM_GEMINI) "Gemini" else "SambaNova"
            if (recognizerIsReady) {
                debugText.text = "Ready (Using $active). Write something."
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

        val options = arrayOf("Gemini", "SambaNova")
        val spinner = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, options)
            
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                    geminiLayout.visibility = if (position == 0) View.VISIBLE else View.GONE
                    sambaLayout.visibility = if (position == 1) View.VISIBLE else View.GONE
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
            
            setSelection(if (currentLLM == Config.LLM_GEMINI) 0 else 1)
        }

        layout.addView(TextView(context).apply { text = "Preferred LLM:" })
        layout.addView(spinner)
        layout.addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(1, 20) })
        layout.addView(geminiLayout)
        layout.addView(sambaLayout)

        val scrollView = ScrollView(context).apply { addView(layout) }

        AlertDialog.Builder(context)
            .setTitle("AI Configuration")
            .setView(scrollView)
            .setPositiveButton("Save") { _, _ ->
                Config.saveGeminiApiKey(context, geminiInput.text.toString())
                Config.saveSambaNovaApiKey(context, sambaInput.text.toString())
                
                val preferred = if (spinner.selectedItemPosition == 0) Config.LLM_GEMINI else Config.LLM_SAMBANOVA
                Config.setPreferredLLM(context, preferred)
                
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

        inkCanvas = findViewById(R.id.inkCanvas)
        replyView = findViewById(R.id.replyView)
        debugText = findViewById(R.id.debugRecognizedText)
        database = AppDatabase.getDatabase(this)

        debugText.visibility = if (Config.isDebugEnabled(this)) View.VISIBLE else View.GONE
        debugText.setTextColor(Color.WHITE)
        debugText.text = "Initializing..."
        
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
            if (isShowingAiContent) {
                Log.d("MainActivity", "Stylus nib down — clearing AI response for new input")
                isShowingAiContent = false
                currentSessionId++

                // 1. Hide the AI response layers IMMEDIATELY in the software UI
                replyView.clear()
                replyView.visibility = View.GONE

                // 2. Clear status bar
                if (Config.isDebugEnabled(this@MainActivity)) {
                    debugText.text = "Ready"
                    debugText.setBackgroundColor(Color.parseColor("#333333"))
                } else {
                    debugText.text = ""
                    debugText.visibility = View.GONE
                }

                // 3. Perform the hardware clear and refresh
                // We use a single call that handles both the buffer clearing and the E-ink flash
                inkCanvas.clearAll()
            }
        }

        // One-time model download
        debugText.text = "Downloading English model..."
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
            }
            val sessionId = ++currentSessionId
            val ink = inkCanvas.exportInk()

            Log.d("MainActivity", "Calling ML Kit recognizer...")
            recognizer.recognize(
                ink,
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
                            replyView.visibility = View.VISIBLE
                            replyView.setTextAndAnimate("(Could not recognize handwriting)")
                            isShowingAiContent = true
                        }
                        return@recognize
                    }

                    val llmName = getActiveLLMName()
                    Log.d("MainActivity", "Recognized text: '$text'. Using $llmName")

                    lifecycleScope.launch {
                        // Show status that we are now calling the API
                        runOnUiThread {
                            debugText.text = "Recognized: \"$text\"\n(Thinking...)"
                            // Show thinking message in the main area
                            replyView.visibility = View.VISIBLE
                            replyView.setTextAndAnimate("Thinking...")
                            isShowingAiContent = true
                        }

                        if (!withContext(Dispatchers.IO) { isOnline() }) {
                            runOnUiThread {
                                if (sessionId != currentSessionId) return@runOnUiThread
                                debugText.text = "Recognized: \"$text\"\n(Offline)"
                                replyView.visibility = View.VISIBLE
                                replyView.setTextAndAnimate("I'm offline. Please check your WiFi connection.")
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
                                Log.d("MainActivity", "Updating UI with AI response")
                                debugText.text = "Recognized: \"$text\"" // Keep the recognized text clean
                                replyView.visibility = View.VISIBLE
                                replyView.setTextAndAnimate(response)
                                isShowingAiContent = true
                                try { EpdController.setViewDefaultUpdateMode(debugText, UpdateMode.GC); debugText.invalidate() } catch(e:Exception){}
                            }
                        } else {
                            // Error path
                            val userFriendlyError = response.removePrefix("API_ERROR: ")
                            Log.e("MainActivity", "AI Error: $userFriendlyError")
                            runOnUiThread {
                                debugText.setBackgroundColor(Color.RED)
                                debugText.text = "Recognized: \"$text\"\nERROR: $userFriendlyError"
                                replyView.visibility = View.VISIBLE
                                replyView.setTextAndAnimate("I encountered an error: $userFriendlyError")
                                isShowingAiContent = true
                                
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
