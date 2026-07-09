package com.longnguyen.inkdiary

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class SummaryActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_summary)

        val date = intent.getStringExtra("EXTRA_DATE") ?: ""
        val summaryText = intent.getStringExtra("EXTRA_SUMMARY") ?: ""

        findViewById<TextView>(R.id.tvSummaryHeader).text = "Summary for $date"
        val replyView = findViewById<HandwrittenReplyView>(R.id.summaryReplyView)
        
        replyView.post {
            replyView.setTextAndAnimate(summaryText)
        }
    }
}
