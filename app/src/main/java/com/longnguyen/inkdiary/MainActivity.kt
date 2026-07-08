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
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.api.device.epd.UpdateMode
import kotlinx.coroutines.launch
import java.lang.reflect.Method

class MainActivity : AppCompatActivity() {

    private lateinit var inkCanvas: InkCanvasView
    private lateinit var replyView: HandwrittenReplyView
    private lateinit var debugText: TextView
    private val recognizer = HandwritingRecognizer()
    private val geminiService = GeminiService(Config.GEMINI_API_KEY)

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
        bypassHiddenApiRestrictions()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        inkCanvas = findViewById(R.id.inkCanvas)
        replyView = findViewById(R.id.replyView)
        debugText = findViewById(R.id.debugRecognizedText)

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
                    debugText.text = "Model san sang. Viet gi do roi dung but 3 giay."
                }
            }
        }

        // One-time model download. First run needs WiFi; after that it's cached
        // and recognize() works fully offline.
        debugText.text = "Dang tai model tieng Viet..."
        recognizer.setup(
            onReady = {
                runOnUiThread {
                    debugText.text = "Model san sang. Viet gi do roi dung but 3 giay."
                }
            },
            onError = { e ->
                runOnUiThread {
                    debugText.text = "Loi tai model: ${e.message}"
                    Toast.makeText(this, "Can WiFi cho lan dau tai model", Toast.LENGTH_LONG).show()
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
            debugText.text = "Dang nhan dien..."

            recognizer.recognize(
                ink,
                onResult = { text ->
                    if (text.isBlank()) {
                        runOnUiThread { 
                            debugText.text = "(khong nhan ra chu nao)"
                            replyView.visibility = android.view.View.VISIBLE
                            replyView.setTextAndAnimate("(Khong nhan dang duoc chu viet)")
                        }
                        return@recognize
                    }

                    Log.d("MainActivity", "Recognized text: $text")
                    runOnUiThread {
                        debugText.text = "Nhan dien: \"$text\". Dang hoi Gemini..."
                    }

                    // Step 3: Send the recognized text to Gemini (check online first)
                    if (!isOnline()) {
                        Log.w("MainActivity", "Device is offline")
                        runOnUiThread {
                            debugText.text = "Offline: Khong the gui den Gemini. Vui lòng bật WiFi."
                            replyView.visibility = android.view.View.VISIBLE
                            replyView.setTextAndAnimate("Dang offline. Vui long bat WiFi de hoi Gemini.")
                        }
                        return@recognize
                    }

                    Log.d("MainActivity", "Calling Gemini service...")
                    val startTime = System.currentTimeMillis()
                    lifecycleScope.launch {
                        val response = geminiService.generateResponse(text)
                        val duration = System.currentTimeMillis() - startTime
                        Log.d("MainActivity", "Gemini call finished in ${duration}ms, response: ${response?.take(20)}...")
                        runOnUiThread {
                            // Step 4: Clear user writing before showing response
                            inkCanvas.clearAll()
                            
                            if (response != null) {
                                debugText.text = "Gemini (${duration}ms): $response"
                                
                                // Perform GC refresh to clean ghosting, but only once we're showing the reply
                                try {
                                    EpdController.refreshScreen(inkCanvas, UpdateMode.GC)
                                } catch (e: Exception) {}

                                // Show and animate the reply
                                replyView.visibility = android.view.View.VISIBLE
                                replyView.setTextAndAnimate(response)
                            } else {
                                debugText.text = "Loi khi goi Gemini (Xem Logcat)"
                                replyView.visibility = android.view.View.VISIBLE
                                replyView.setTextAndAnimate("Loi khi goi Gemini. Vui long thu lai.")
                            }
                        }
                    }
                },
                onError = { e ->
                    runOnUiThread {
                        debugText.text = "Loi nhan dien: ${e.message}"
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
