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
    /** Set with commit() right before native model load; cleared after success. */
    private const val KEY_ON_DEVICE_LOAD_IN_PROGRESS = "on_device_load_in_progress"
    /** Sticky flag after a native crash during on-device load. */
    private const val KEY_ON_DEVICE_UNSAFE = "on_device_unsafe"

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

        // If a previous native load hard-crashed mid-init, stay on cloud until the user
        // re-selects on-device (which clears the unsafe flag).
        if (preferred == LLM_ON_DEVICE) {
            if (isOnDeviceUnsafe(context) || isOnDeviceLoadInProgress(context)) {
                return fallbackCloudProvider(context)
            }
            return preferred
        }
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

    fun isOnDeviceUnsafe(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ON_DEVICE_UNSAFE, false)
    }

    fun isOnDeviceLoadInProgress(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ON_DEVICE_LOAD_IN_PROGRESS, false)
    }

    /**
     * Call with commit() immediately before native llama load so a SIGILL can be
     * detected on the next cold start.
     */
    fun markOnDeviceLoadStarting(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ON_DEVICE_LOAD_IN_PROGRESS, true)
            .commit()
    }

    fun markOnDeviceLoadFinished(context: Context, success: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(KEY_ON_DEVICE_LOAD_IN_PROGRESS, false)
            .apply {
                if (!success) putBoolean(KEY_ON_DEVICE_UNSAFE, true)
            }
            .commit()
    }

    fun clearOnDeviceUnsafe(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ON_DEVICE_UNSAFE, false)
            .putBoolean(KEY_ON_DEVICE_LOAD_IN_PROGRESS, false)
            .apply()
    }

    /**
     * If the previous process died mid native-load, mark on-device unsafe and
     * switch preferred provider to a cloud backend. Returns true if recovery ran.
     */
    fun recoverFromOnDeviceCrashIfNeeded(context: Context): Boolean {
        val diedDuringLoad = isOnDeviceLoadInProgress(context)
        if (!diedDuringLoad) return false

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val preferred = prefs.getString(KEY_PREFERRED_LLM, LLM_GEMINI) ?: LLM_GEMINI
        val fallback = fallbackCloudProvider(context)

        prefs.edit()
            .putBoolean(KEY_ON_DEVICE_LOAD_IN_PROGRESS, false)
            .putBoolean(KEY_ON_DEVICE_UNSAFE, true)
            .apply {
                if (preferred == LLM_ON_DEVICE) {
                    putString(KEY_PREFERRED_LLM, fallback)
                }
            }
            .commit()

        Log.w(
            "Config",
            "On-device LLM crashed during native load. Marked unsafe and fell back to $fallback."
        )
        return true
    }

    /** Raw stored preference, ignoring unsafe fallbacks. */
    fun getRawPreferredLLM(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PREFERRED_LLM, LLM_GEMINI) ?: LLM_GEMINI
    }

    /**
     * Force preferred provider off on-device. Used when the user is stuck in a
     * crash loop from a build that loaded llama at startup.
     */
    fun forceCloudProvider(context: Context, markUnsafe: Boolean = false) {
        val fallback = fallbackCloudProvider(context)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PREFERRED_LLM, fallback)
            .putBoolean(KEY_ON_DEVICE_LOAD_IN_PROGRESS, false)
            .apply {
                if (markUnsafe) putBoolean(KEY_ON_DEVICE_UNSAFE, true)
            }
            .commit()
        Log.w("Config", "Forced preferred LLM to $fallback (markUnsafe=$markUnsafe)")
    }

    private fun fallbackCloudProvider(context: Context): String {
        if (getGeminiApiKey(context).isNotBlank()) return LLM_GEMINI
        if (getSambaNovaApiKey(context).isNotBlank()) return LLM_SAMBANOVA
        return LLM_GEMINI
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
