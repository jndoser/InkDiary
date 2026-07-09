package com.longnguyen.inkdiary.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "conversations")
data class ConversationEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: String, // Format: yyyy-MM-dd
    val role: String, // "user" or "model"
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)
