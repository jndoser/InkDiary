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
import com.google.ai.client.generativeai.type.content
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

class MainActivity : AppCompatActivity() {

    private lateinit var inkCanvas: InkCanvasView
    private lateinit var replyView: HandwrittenReplyView
    private lateinit var debugText: TextView
    private val recognizer = HandwritingRecognizer()
    private lateinit var llmService: LLMService
    private lateinit var database: AppDatabase
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
        if (ev.actionMasked == MotionEvent.ACTION_UP) {
            val isFinger = ev.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER
            if (isFinger) {
                tapCount++
                Log.d("MultiTap", "Tap counted: $tapCount")
                handler.removeCallbacks(tapRunnable)
                handler.postDelayed(tapRunnable, 600)
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        inkCanvas = findViewById(R.id.inkCanvas)
        replyView = findViewById(R.id.replyView)
        debugText = findViewById(R.id.debugRecognizedText)
        database = AppDatabase.getDatabase(this)

        debugText.visibility = if (Config.isDebugEnabled(this)) View.VISIBLE else View.GONE

        setupLLM()

        findViewById<View>(R.id.btnHistory).setOnClickListener {
            val intent = android.content.Intent(this, HistoryActivity::class.java)
            startActivity(intent)
        }

        // Ensure replyView is hidden initially
        replyView.visibility = View.GONE

        // Step 5: Clear and Full Refresh on pen-down
        inkCanvas.setOnTouchDownListener {
            Log.d("MainActivity", "Touch Down detected")
            if (replyView.visibility == View.VISIBLE) {
                Log.d("MainActivity", "Clearing previous session to start new one")
                replyView.clear()
                replyView.visibility = View.GONE
                inkCanvas.clearAll()
                try {
                    EpdController.setViewDefaultUpdateMode(inkCanvas, UpdateMode.DU)
                } catch (e: Exception) { }
                runOnUiThread {
                    debugText.text = "Ready. Write something and stop for 2 seconds."
                }
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
            if (!inkCanvas.hasContent()) return@setOnPauseListener
            
            debugText.text = "Recognizing..."
            val ink = inkCanvas.exportInk()

            recognizer.recognize(
                ink,
                onResult = { text ->
                    if (text.isBlank()) {
                        runOnUiThread { 
                            debugText.text = "(No text recognized)"
                            replyView.visibility = View.VISIBLE
                            replyView.setTextAndAnimate("(Could not recognize handwriting)")
                        }
                        return@recognize
                    }

                    Log.d("MainActivity", "Recognized text: $text")
                    val llmName = getActiveLLMName()
                    runOnUiThread { debugText.text = "Asking $llmName..." }

                    if (!isOnline()) {
                        runOnUiThread {
                            debugText.text = "Offline. Please connect to WiFi."
                            replyView.visibility = View.VISIBLE
                            replyView.setTextAndAnimate("Offline. Please connect to WiFi to ask $llmName.")
                        }
                        return@recognize
                    }

                    lifecycleScope.launch {
                        val today = getTodayDate()
                        val conversationDao = database.conversationDao()
                        val historyEntries = withContext(Dispatchers.IO) {
                            conversationDao.getConversationsByDate(today)
                        }
                        
                        val history = historyEntries.map { entry ->
                            ChatMessage(entry.role, entry.content)
                        }

                        val response = llmService.generateResponse(text, history)

                        if (response != null) {
                            withContext(Dispatchers.IO) {
                                conversationDao.insert(ConversationEntry(date = today, role = "user", content = text))
                                conversationDao.insert(ConversationEntry(date = today, role = "model", content = response))
                            }
                            runOnUiThread {
                                inkCanvas.clearAll()
                                debugText.text = "AI: $response"
                                replyView.visibility = View.VISIBLE
                                replyView.setTextAndAnimate(response)
                            }
                        } else {
                            runOnUiThread {
                                debugText.text = "Error calling $llmName"
                                replyView.visibility = View.VISIBLE
                                replyView.setTextAndAnimate("Error calling $llmName. Please try again.")
                            }
                        }
                    }
                },
                onError = { e ->
                    runOnUiThread { debugText.text = "Recognition error: ${e.message}" }
                }
            )
        }
    }

    override fun onDestroy() {
        recognizer.close()
        super.onDestroy()
    }
}
