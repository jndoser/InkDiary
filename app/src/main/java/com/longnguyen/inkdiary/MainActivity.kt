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

class MainActivity : AppCompatActivity() {

    private lateinit var inkCanvas: InkCanvasView
    private lateinit var replyView: HandwrittenReplyView
    private lateinit var debugText: TextView
    private val recognizer = HandwritingRecognizer()
    private var geminiService = GeminiService("") // Initialized in setupGemini
    private lateinit var database: AppDatabase
    private lateinit var gestureDetector: GestureDetector
    private var recognizerIsReady = false

    private fun getTodayDate(): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
    }

    private fun isOnline(): Boolean {
        val connectivityManager = getSystemService(ConnectivityManager::class.java)
        val currentNetwork = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(currentNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun setupGemini() {
        val apiKey = Config.getGeminiApiKey(this)
        if (apiKey.isBlank()) {
            debugText.text = "GUIDE: API Key is missing. Tap 3 times on the screen (with finger) to enter your Gemini API Key."
        } else {
            geminiService = GeminiService(apiKey)
            if (recognizerIsReady) {
                debugText.text = "Ready. Write something and stop for 2 seconds."
            }
        }
    }

    private fun showApiKeyDialog() {
        val input = EditText(this).apply {
            hint = "Enter Gemini API Key"
            setText(Config.getGeminiApiKey(this@MainActivity))
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
            addView(input)
        }

        AlertDialog.Builder(this)
            .setTitle("Gemini API Configuration")
            .setMessage("Please enter your Gemini API Key. You can get one from Google AI Studio.")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val newKey = input.text.toString().trim()
                if (newKey.isNotEmpty()) {
                    Config.saveGeminiApiKey(this, newKey)
                    setupGemini()
                    Toast.makeText(this, "API Key saved successfully", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        inkCanvas = findViewById(R.id.inkCanvas)
        replyView = findViewById(R.id.replyView)
        debugText = findViewById(R.id.debugRecognizedText)
        database = AppDatabase.getDatabase(this)

        setupGemini()

        // Triple tap detection using finger
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            private var lastTapTime = 0L
            private var tapCount = 0

            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val currentTime = System.currentTimeMillis()
                val isFinger = e.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER
                
                Log.d("TripleTap", "Tap detected. isFinger: $isFinger, count: ${tapCount + 1}")

                if (!isFinger) {
                    tapCount = 0
                    return false
                }

                if (currentTime - lastTapTime < 800) {
                    tapCount++
                } else {
                    tapCount = 1
                }
                lastTapTime = currentTime
                
                if (tapCount == 3) {
                    Log.d("TripleTap", "TRIPLE TAP SUCCESS")
                    tapCount = 0
                    showApiKeyDialog()
                    return true
                }
                return false
            }
        })

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
                    if (Config.getGeminiApiKey(this@MainActivity).isBlank()) {
                        debugText.text = "GUIDE: API Key is missing. Tap 3 times on the screen (with finger) to enter your Gemini API Key."
                    } else {
                        debugText.text = "Ready. Write something and stop for 2 seconds."
                    }
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
                    runOnUiThread { debugText.text = "Asking Gemini..." }

                    if (!isOnline()) {
                        runOnUiThread {
                            debugText.text = "Offline. Please connect to WiFi."
                            replyView.visibility = View.VISIBLE
                            replyView.setTextAndAnimate("Offline. Please connect to WiFi to ask Gemini.")
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
                            content(entry.role) { text(entry.content) }
                        }

                        val response = geminiService.generateResponse(text, history)
                        if (response != null) {
                            withContext(Dispatchers.IO) {
                                conversationDao.insert(ConversationEntry(date = today, role = "user", content = text))
                                conversationDao.insert(ConversationEntry(date = today, role = "model", content = response))
                            }
                            runOnUiThread {
                                inkCanvas.clearAll()
                                debugText.text = "Gemini: $response"
                                replyView.visibility = View.VISIBLE
                                replyView.setTextAndAnimate(response)
                            }
                        } else {
                            runOnUiThread {
                                debugText.text = "Error calling Gemini"
                                replyView.visibility = View.VISIBLE
                                replyView.setTextAndAnimate("Error calling Gemini. Please try again.")
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
