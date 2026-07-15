package com.longnguyen.inkdiary

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.longnguyen.inkdiary.data.AppDatabase
import com.longnguyen.inkdiary.data.ConversationEntry
import com.longnguyen.inkdiary.data.DailySummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DailyDetailActivity : AppCompatActivity() {

    private lateinit var rvDetail: RecyclerView
    private lateinit var database: AppDatabase
    private lateinit var adapter: DailyDetailAdapter
    private var selectedDate: String? = null
    private lateinit var llmService: LLMService
    private var isGeneratingSummary = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_daily_detail)

        selectedDate = intent.getStringExtra("EXTRA_DATE")
        if (selectedDate == null) {
            finish()
            return
        }

        findViewById<TextView>(R.id.tvDetailHeader).text = selectedDate
        rvDetail = findViewById(R.id.rvDailyDetail)
        database = AppDatabase.getDatabase(this)
        llmService = LLMFactory.getService(this)

        rvDetail.layoutManager = LinearLayoutManager(this)
        adapter = DailyDetailAdapter()
        rvDetail.adapter = adapter

        findViewById<View>(R.id.btnDeleteDay).setOnClickListener {
            deleteDay()
        }

        findViewById<View>(R.id.btnSummary).setOnClickListener {
            generateSummary()
        }

        loadConversation()
    }

    override fun onDestroy() {
        super.onDestroy()
        (llmService as? OnDeviceLLMService)?.close()
    }

    private fun getActiveLLMName(): String {
        return when (Config.getPreferredLLM(this)) {
            Config.LLM_GEMINI -> "Gemini"
            Config.LLM_SAMBANOVA -> "SambaNova"
            Config.LLM_ON_DEVICE -> "On-Device"
            else -> "Unknown"
        }
    }

    /**
     * For on-device mode: download model if needed, then load it into memory.
     * Cloud modes are a no-op success.
     */
    private suspend fun prepareLlmForSummary(): Result<Unit> {
        if (Config.getPreferredLLM(this) != Config.LLM_ON_DEVICE) {
            return Result.success(Unit)
        }

        val onDevice = llmService as? OnDeviceLLMService
            ?: return Result.failure(Exception("On-device service unavailable"))

        if (!ModelDownloadManager.isModelDownloaded(this)) {
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@DailyDetailActivity,
                    "Downloading offline AI model (first time only)…",
                    Toast.LENGTH_LONG
                ).show()
            }
            val download = ModelDownloadManager.downloadModel(this)
            if (download.isFailure) {
                return Result.failure(
                    download.exceptionOrNull()
                        ?: Exception("Model download failed")
                )
            }
        }

        withContext(Dispatchers.Main) {
            Toast.makeText(
                this@DailyDetailActivity,
                "Loading on-device model…",
                Toast.LENGTH_SHORT
            ).show()
        }
        return onDevice.ensureModelLoaded()
    }

    private fun generateSummary() {
        val date = selectedDate ?: return
        if (isGeneratingSummary) {
            Toast.makeText(this, "Summary already in progress…", Toast.LENGTH_SHORT).show()
            return
        }

        isGeneratingSummary = true
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val dao = database.conversationDao()
                val conversations = dao.getConversationsByDate(date)
                if (conversations.isEmpty()) return@launch

                val existingSummary = dao.getSummaryByDate(date)

                // Don't treat prior API error strings as valid cached summaries
                val cachedIsValid = existingSummary != null &&
                    existingSummary.lastConversationCount == conversations.size &&
                    !existingSummary.summary.startsWith("API_ERROR:")

                if (cachedIsValid) {
                    Log.d("DailyDetail", "Showing existing summary")
                    withContext(Dispatchers.Main) {
                        showSummaryScreen(date, existingSummary!!.summary)
                    }
                    return@launch
                }

                val llmName = getActiveLLMName()
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@DailyDetailActivity,
                        "Preparing summary ($llmName)…",
                        Toast.LENGTH_SHORT
                    ).show()
                }

                val prepare = prepareLlmForSummary()
                if (prepare.isFailure) {
                    val msg = prepare.exceptionOrNull()?.localizedMessage ?: "unknown error"
                    Log.e("DailyDetail", "Failed to prepare LLM: $msg")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@DailyDetailActivity,
                            "Failed to prepare $llmName: $msg",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@DailyDetailActivity,
                        "Generating summary ($llmName)…",
                        Toast.LENGTH_SHORT
                    ).show()
                }

                val fullText = conversations.joinToString("\n") {
                    "${if (it.role == "user") "User" else "AI"}: ${it.content}"
                }

                val prompt =
                    "Please summarize the following conversation from my diary for the day $date. " +
                        "Be concise and empathetic. Respond with just the summary in 3-5 sentences.\n\n$fullText"

                val response = llmService.generateResponse(prompt)

                if (response != null && !response.startsWith("API_ERROR:")) {
                    val newSummary = DailySummary(
                        date = date,
                        summary = response,
                        lastConversationCount = conversations.size
                    )
                    dao.insertSummary(newSummary)
                    withContext(Dispatchers.Main) {
                        showSummaryScreen(date, response)
                    }
                } else {
                    val errorDetail = response
                        ?.removePrefix("API_ERROR: ")
                        ?.ifBlank { null }
                        ?: "No response"
                    Log.e("DailyDetail", "Summary generation failed: $errorDetail")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@DailyDetailActivity,
                            "Failed to generate summary ($llmName): $errorDetail",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } finally {
                withContext(Dispatchers.Main) {
                    isGeneratingSummary = false
                }
            }
        }
    }

    private fun showSummaryScreen(date: String, summary: String) {
        val intent = Intent(this, SummaryActivity::class.java).apply {
            putExtra("EXTRA_DATE", date)
            putExtra("EXTRA_SUMMARY", summary)
        }
        startActivity(intent)
    }

    private fun loadConversation() {
        lifecycleScope.launch(Dispatchers.IO) {
            val conversation = database.conversationDao().getConversationsByDate(selectedDate!!)
            withContext(Dispatchers.Main) {
                adapter.submitList(conversation)
                if (conversation.isEmpty()) {
                    finish() // Close if everything deleted
                }
            }
        }
    }

    private fun deleteDay() {
        AlertDialog.Builder(this)
            .setTitle("Delete Day")
            .setMessage("Delete all entries for $selectedDate?")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    database.conversationDao().deleteByDate(selectedDate!!)
                    database.conversationDao().deleteSummaryByDate(selectedDate!!)
                    withContext(Dispatchers.Main) {
                        finish()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    class DailyDetailAdapter : RecyclerView.Adapter<DailyDetailAdapter.ViewHolder>() {

        private var entries: List<ConversationEntry> = emptyList()

        fun submitList(newList: List<ConversationEntry>) {
            entries = newList
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_history_entry, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val entry = entries[position]
            holder.tvRole.text = if (entry.role == "user") "Me:" else "AI:"
            holder.tvContent.text = entry.content
        }

        override fun getItemCount() = entries.size

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvRole: TextView = view.findViewById(R.id.tvDate) // Reusing ID from layout
            val tvContent: TextView = view.findViewById(R.id.tvContent)
        }
    }
}
