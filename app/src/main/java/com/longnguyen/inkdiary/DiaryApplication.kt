package com.longnguyen.inkdiary

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import org.lsposed.hiddenapibypass.HiddenApiBypass

class DiaryApplication : Application() {

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        // Bypass MUST happen as early as possible in the lifecycle
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val success = HiddenApiBypass.addHiddenApiExemptions("L")
            Log.d("DiaryApplication", "Hidden API Bypass success: $success")
        }
    }

    override fun onCreate() {
        super.onCreate()
    }
}
