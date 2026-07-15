package com.longnguyen.inkdiary

import android.content.Context
import android.util.Log

object Config {
    private const val PREFS_NAME = "ink_diary_prefs"
    private const val KEY_GEMINI_API = "gemini_api_key"
    private const val KEY_SAMBANOVA_API = "sambanova_api_key"
    private const val KEY_PREFERRED_LLM = "preferred_llm"

    const val LLM_GEMINI = "gemini"
    const val LLM_SAMBANOVA = "sambanova"
    const val LLM_ON_DEVICE = "on_device"
    private const val KEY_DEBUG_MODE = "debug_mode"
    private const val KEY_RECOGNITION_LANGUAGE = "recognition_language"

    const val LANG_ENGLISH = "en"
    const val LANG_VIETNAMESE = "vi"

    fun getGeminiApiKey(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_GEMINI_API, "") ?: ""
        val result = if (saved.isNotBlank()) saved else BuildConfig.GEMINI_API_KEY
        Log.d("Config", "getGeminiApiKey: result=${if(result.length > 5) result.take(5) + "..." else result} (saved=$saved, build=${BuildConfig.GEMINI_API_KEY})")
        return result
    }

    fun saveGeminiApiKey(context: Context, key: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val trimmed = key.trim()
        Log.d("Config", "saveGeminiApiKey: '$trimmed'")
        // Only save if it's different from the BuildConfig key to keep prefs clean
        if (trimmed == BuildConfig.GEMINI_API_KEY) {
            prefs.edit().remove(KEY_GEMINI_API).apply()
        } else {
            prefs.edit().putString(KEY_GEMINI_API, trimmed).apply()
        }
    }

    fun getSambaNovaApiKey(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_SAMBANOVA_API, "") ?: ""
        val result = if (saved.isNotBlank()) saved else BuildConfig.SAMBANOVA_API_KEY
        Log.d("Config", "getSambaNovaApiKey: result=${if(result.length > 5) result.take(5) + "..." else result} (saved=$saved, build=${BuildConfig.SAMBANOVA_API_KEY})")
        return result
    }

    fun saveSambaNovaApiKey(context: Context, key: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val trimmed = key.trim()
        Log.d("Config", "saveSambaNovaApiKey: '$trimmed'")
        if (trimmed == BuildConfig.SAMBANOVA_API_KEY) {
            prefs.edit().remove(KEY_SAMBANOVA_API).apply()
        } else {
            prefs.edit().putString(KEY_SAMBANOVA_API, trimmed).apply()
        }
    }

    fun getPreferredLLM(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val preferred = prefs.getString(KEY_PREFERRED_LLM, LLM_GEMINI) ?: LLM_GEMINI
        
        // Fallback if preferred is not available
        if (preferred == LLM_ON_DEVICE) return preferred // On-device doesn't need API keys
        if (preferred == LLM_GEMINI && getGeminiApiKey(context).isBlank()) {
            if (getSambaNovaApiKey(context).isNotBlank()) return LLM_SAMBANOVA
        }
        if (preferred == LLM_SAMBANOVA && getSambaNovaApiKey(context).isBlank()) {
            if (getGeminiApiKey(context).isNotBlank()) return LLM_GEMINI
        }
        
        return preferred
    }

    fun setPreferredLLM(context: Context, llm: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_PREFERRED_LLM, llm).apply()
    }

    fun isDebugEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_DEBUG_MODE, true) // Default to true so you don't lose it initially
    }

    fun setDebugEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_DEBUG_MODE, enabled).apply()
    }

    fun getRecognitionLanguage(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_RECOGNITION_LANGUAGE, LANG_ENGLISH) ?: LANG_ENGLISH
    }

    fun setRecognitionLanguage(context: Context, lang: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_RECOGNITION_LANGUAGE, lang).apply()
    }
}
