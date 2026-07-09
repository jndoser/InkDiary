package com.longnguyen.inkdiary.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations WHERE date = :date ORDER BY timestamp ASC")
    suspend fun getConversationsByDate(date: String): List<ConversationEntry>

    @Query("SELECT DISTINCT date FROM conversations ORDER BY date DESC")
    suspend fun getUniqueDates(): List<String>

    @Query("SELECT * FROM conversations ORDER BY timestamp DESC")
    suspend fun getAllConversations(): List<ConversationEntry>

    @Insert
    suspend fun insert(entry: ConversationEntry)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM conversations WHERE date = :date")
    suspend fun deleteByDate(date: String)

    @Query("DELETE FROM conversations")
    suspend fun deleteAll()

    @Query("SELECT * FROM daily_summaries WHERE date = :date")
    suspend fun getSummaryByDate(date: String): DailySummary?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSummary(summary: DailySummary)

    @Query("DELETE FROM daily_summaries WHERE date = :date")
    suspend fun deleteSummaryByDate(date: String)
}
