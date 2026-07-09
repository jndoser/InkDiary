package com.longnguyen.inkdiary

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.longnguyen.inkdiary.data.AppDatabase
import com.longnguyen.inkdiary.data.ConversationEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DailyDetailActivity : AppCompatActivity() {

    private lateinit var rvDetail: RecyclerView
    private lateinit var database: AppDatabase
    private lateinit var adapter: DailyDetailAdapter
    private var selectedDate: String? = null

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

        rvDetail.layoutManager = LinearLayoutManager(this)
        adapter = DailyDetailAdapter()
        rvDetail.adapter = adapter

        findViewById<View>(R.id.btnDeleteDay).setOnClickListener {
            deleteDay()
        }

        loadConversation()
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
            holder.tvRole.text = if (entry.role == "user") "Me:" else "Gemini:"
            holder.tvContent.text = entry.content
        }

        override fun getItemCount() = entries.size

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvRole: TextView = view.findViewById(R.id.tvDate) // Reusing ID from layout
            val tvContent: TextView = view.findViewById(R.id.tvContent)
        }
    }
}
