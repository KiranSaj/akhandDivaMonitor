package com.kiransaj.diyamonitor

import android.app.Application
import com.kiransaj.diyamonitor.data.Prefs
import com.kiransaj.diyamonitor.telegram.TelegramOutbox

class DiyaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // Tell the user before the process dies. monitoringActive stays true so the
            // service resumes if Android restarts it.
            runCatching {
                if (Prefs(this).monitoringActive) {
                    TelegramOutbox.get(this).sendNow(
                        "💥 Diya Monitor crashed: ${error.javaClass.simpleName}: ${error.message}\n" +
                            "Monitoring has stopped. Android may restart it automatically; otherwise open the app and press Start."
                    )
                }
            }
            previous?.uncaughtException(thread, error)
        }
    }
}
