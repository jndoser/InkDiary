package com.longnguyen.inkdiary

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import android.widget.Toast
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
import java.lang.reflect.Method
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var inkCanvas: InkCanvasView
    private lateinit var replyView: HandwrittenReplyView
    private lateinit var debugText: TextView
    private val recognizer = HandwritingRecognizer()
    private val geminiService = GeminiService(Config.GEMINI_API_KEY)
    private lateinit var database: AppDatabase

    private fun getTodayDate(): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
    }

    private fun isOnline(): Boolean {
        val connectivityManager = getSystemService(ConnectivityManager::class.java)
        val currentNetwork = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(currentNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun bypassHiddenApiRestrictions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        try {
            // Use reflection to call the hidden VMRuntime.setHiddenApiExemptions
            val forName = Class::class.java.getDeclaredMethod("forName", String::class.java)
            val getDeclaredMethod = Class::class.java.getDeclaredMethod("getDeclaredMethod", String::class.java, arrayOf<Class<*>>().javaClass)

            val vmRuntimeClass = forName.invoke(null, "dalvik.system.VMRuntime") as Class<*>
            val getRuntimeMethod = getDeclaredMethod.invoke(vmRuntimeClass, "getRuntime", null) as Method
            val setHiddenApiExemptionsMethod = getDeclaredMethod.invoke(vmRuntimeClass, "setHiddenApiExemptions", arrayOf(arrayOf<String>().javaClass)) as Method
            
            val runtime = getRuntimeMethod.invoke(null)
            setHiddenApiExemptionsMethod.invoke(runtime, arrayOf(arrayOf("L")))
            Log.d("MainActivity", "Hidden API bypass successful (Double Reflection)")
        } catch (e: Exception) {
            Log.w("MainActivity", "Hidden API bypass failed", e)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // bypassHiddenApiRestrictions() // Disabled to prevent reflection errors on startup
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        inkCanvas = findViewById(R.id.inkCanvas)
        replyView = findViewById(R.id.replyView)
        debugText = findViewById(R.id.debugRecognizedText)
        database = AppDatabase.getDatabase(this)

        findViewById<android.view.View>(R.id.btnHistory).setOnClickListener {
            val intent = android.content.Intent(this, HistoryActivity::class.java)
            startActivity(intent)
        }

        // Ensure replyView is hidden initially
        replyView.visibility = android.view.View.GONE

        // Step 5: Clear and Full Refresh on pen-down
        inkCanvas.setOnTouchDownListener {
            Log.d("MainActivity", "Touch Down detected")

            // Only clear if an answer is currently shown
            if (replyView.visibility == android.view.View.VISIBLE) {
                Log.d("MainActivity", "Clearing previous session to start new one")
                replyView.clear()
                replyView.visibility = android.view.View.GONE
                inkCanvas.clearAll()
                
                // Set canvas to Direct Update mode (DU) for low-latency handwriting
                try {
                    EpdController.setViewDefaultUpdateMode(inkCanvas, UpdateMode.DU)
                } catch (e: Exception) { }
                
                runOnUiThread {
                    debugText.text = "Ready. Write something and stop for 2 seconds."
                }
            }
        }

        // One-time model download. First run needs WiFi; after that it's cached
        // and recognize() works fully offline.
        debugText.text = "Downloading English model..."
        recognizer.setup(
            onReady = {
                runOnUiThread {
                    debugText.text = "Ready. Write something and stop for 2 seconds."
                }
            },
            onError = { e ->
                runOnUiThread {
                    debugText.text = "Model download error: ${e.message}"
                    Toast.makeText(this, "WiFi required for first-time model download", Toast.LENGTH_LONG).show()
                }
            }
        )

        // Wired to the 3-second pause detector inside InkCanvasView.
        inkCanvas.setOnPauseListener {
            if (!inkCanvas.hasContent()) {
                Log.d("MainActivity", "Pause triggered but no content to recognize")
                return@setOnPauseListener
            }
            
            Log.d("MainActivity", "Pause detected, starting recognition flow")
            val ink = inkCanvas.exportInk()
            
            // Show that we're recognizing
            debugText.text = "Recognizing..."

            recognizer.recognize(
                ink,
                onResult = { text ->
                    if (text.isBlank()) {
                        runOnUiThread { 
                            debugText.text = "(No text recognized)"
                            replyView.visibility = android.view.View.VISIBLE
                            replyView.setTextAndAnimate("(Could not recognize handwriting)")
                        }
                        return@recognize
                    }

                    Log.d("MainActivity", "Recognized text: $text")
                    runOnUiThread {
                        debugText.text = "Recognized: \"$text\". Asking Gemini..."
                    }

                    // Step 3: Send the recognized text to Gemini (check online first)
                    if (!isOnline()) {
                        Log.w("MainActivity", "Device is offline")
                        runOnUiThread {
                            debugText.text = "Offline: Cannot send to Gemini. Please turn on WiFi."
                            replyView.visibility = android.view.View.VISIBLE
                            replyView.setTextAndAnimate("Offline. Please connect to WiFi to ask Gemini.")
                        }
                        return@recognize
                    }

                    Log.d("MainActivity", "Calling Gemini service...")
                    val startTime = System.currentTimeMillis()
                    lifecycleScope.launch {
                        val today = getTodayDate()
                        val conversationDao = database.conversationDao()
                        
                        // Fetch today's history
                        val historyEntries = withContext(Dispatchers.IO) {
                            conversationDao.getConversationsByDate(today)
                        }
                        
                        // Convert to Gemini Content objects
                        val history = historyEntries.map { entry ->
                            content(entry.role) { text(entry.content) }
                        }

                        val response = geminiService.generateResponse(text, history)
                        val duration = System.currentTimeMillis() - startTime
                        Log.d("MainActivity", "Gemini call finished in ${duration}ms, response: ${response?.take(20)}...")
                        
                        if (response != null) {
                            // Save to database
                            withContext(Dispatchers.IO) {
                                conversationDao.insert(ConversationEntry(date = today, role = "user", content = text))
                                conversationDao.insert(ConversationEntry(date = today, role = "model", content = response))
                            }
                            
                            runOnUiThread {
                                // Step 4: Clear user writing before showing response
                                inkCanvas.clearAll()
                                debugText.text = "Gemini (${duration}ms): $response"
                                
                                // Show and animate the reply (it handles its own E-ink DU mode)
                                replyView.visibility = android.view.View.VISIBLE
                                replyView.setTextAndAnimate(response)
                            }
                        } else {
                            runOnUiThread {
                                debugText.text = "Error calling Gemini (Check Logcat)"
                                replyView.visibility = android.view.View.VISIBLE
                                replyView.setTextAndAnimate("Error calling Gemini. Please try again.")
                            }
                        }
                    }
                },
                onError = { e ->
                    runOnUiThread {
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
