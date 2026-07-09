package com.longnguyen.inkdiary

import android.content.Context

object Config {
    private const val PREFS_NAME = "ink_diary_prefs"
    private const val KEY_GEMINI_API = "gemini_api_key"

    /**
     * Retrieves the API key. Priority: SharedPreferences > BuildConfig (local.properties)
     */
    fun getGeminiApiKey(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedKey = prefs.getString(KEY_GEMINI_API, null)
        return if (!savedKey.isNullOrBlank()) savedKey else BuildConfig.GEMINI_API_KEY
    }

    fun saveGeminiApiKey(context: Context, key: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_GEMINI_API, key.trim()).apply()
    }

    fun hasCustomApiKey(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return !prefs.getString(KEY_GEMINI_API, null).isNullOrBlank()
    }
}
