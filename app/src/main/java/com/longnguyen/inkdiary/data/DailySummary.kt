package com.longnguyen.inkdiary.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "daily_summaries")
data class DailySummary(
    @PrimaryKey val date: String, // Format: yyyy-MM-dd
    val summary: String,
    val lastConversationCount: Int,
    val timestamp: Long = System.currentTimeMillis()
)
