package com.longnguyen.inkdiary

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.longnguyen.inkdiary.data.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HistoryActivity : AppCompatActivity() {

    private lateinit var rvHistory: RecyclerView
    private lateinit var database: AppDatabase
    private lateinit var adapter: DateSummaryAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        rvHistory = findViewById(R.id.rvHistory)
        database = AppDatabase.getDatabase(this)

        rvHistory.layoutManager = LinearLayoutManager(this)
        adapter = DateSummaryAdapter(
            onDateClick = { date -> openDailyDetail(date) },
            onDateLongClick = { date -> deleteDay(date) }
        )
        rvHistory.adapter = adapter

        findViewById<View>(R.id.btnClearAll).setOnClickListener {
            clearAllHistory()
        }

        loadDates()
    }

    override fun onResume() {
        super.onResume()
        loadDates()
    }

    private fun loadDates() {
        lifecycleScope.launch(Dispatchers.IO) {
            val dates = database.conversationDao().getUniqueDates()
            withContext(Dispatchers.Main) {
                adapter.submitList(dates)
            }
        }
    }

    private fun openDailyDetail(date: String) {
        val intent = Intent(this, DailyDetailActivity::class.java).apply {
            putExtra("EXTRA_DATE", date)
        }
        startActivity(intent)
    }

    private fun deleteDay(date: String) {
        AlertDialog.Builder(this)
            .setTitle("Delete Day")
            .setMessage("Delete all entries for $date?")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    database.conversationDao().deleteByDate(date)
                    loadDates()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun clearAllHistory() {
        AlertDialog.Builder(this)
            .setTitle("Clear All History")
            .setMessage("Are you sure you want to delete all history?")
            .setPositiveButton("Clear All") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    database.conversationDao().deleteAll()
                    loadDates()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    class DateSummaryAdapter(
        private val onDateClick: (String) -> Unit,
        private val onDateLongClick: (String) -> Unit
    ) : RecyclerView.Adapter<DateSummaryAdapter.ViewHolder>() {

        private var dates: List<String> = emptyList()

        fun submitList(newList: List<String>) {
            dates = newList
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(android.R.layout.simple_list_item_1, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val date = dates[position]
            holder.tvDate.text = date
            holder.tvDate.setTextColor(android.graphics.Color.BLACK)
            holder.tvDate.setPadding(32, 32, 32, 32)
            holder.itemView.setOnClickListener { onDateClick(date) }
            holder.itemView.setOnLongClickListener {
                onDateLongClick(date)
                true
            }
        }

        override fun getItemCount() = dates.size

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvDate: TextView = view.findViewById(android.R.id.text1)
        }
    }
}
