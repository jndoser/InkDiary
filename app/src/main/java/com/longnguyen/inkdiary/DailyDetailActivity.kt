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

    private fun generateSummary() {
        val date = selectedDate ?: return
        
        lifecycleScope.launch(Dispatchers.IO) {
            val dao = database.conversationDao()
            val conversations = dao.getConversationsByDate(date)
            if (conversations.isEmpty()) return@launch

            val existingSummary = dao.getSummaryByDate(date)
            
            if (existingSummary != null && existingSummary.lastConversationCount == conversations.size) {
                Log.d("DailyDetail", "Showing existing summary")
                withContext(Dispatchers.Main) {
                    showSummaryScreen(date, existingSummary.summary)
                }
                return@launch
            }

            // Need new summary
            val llmName = if (Config.getPreferredLLM(this@DailyDetailActivity) == Config.LLM_GEMINI) "Gemini" else "SambaNova"
            withContext(Dispatchers.Main) {
                Toast.makeText(this@DailyDetailActivity, "Generating summary ($llmName)...", Toast.LENGTH_SHORT).show()
            }

            val fullText = conversations.joinToString("\n") { 
                "${if (it.role == "user") "User" else "AI"}: ${it.content}"
            }

            val prompt = "Please summarize the following conversation from my diary for the day $date. Be concise and empathetic. Respond with just the summary in 3-5 sentences.\n\n$fullText"
            
            val response = llmService.generateResponse(prompt)

            if (response != null) {
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
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@DailyDetailActivity, "Failed to generate summary ($llmName). Check your API quota or connection.", Toast.LENGTH_LONG).show()
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
