package com.kiransaj.diyamonitor.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Adds "🕒 HH:MM" (when the event happened) as the second line of a message, plus
 * "(delayed N min)" when it is delivered late, e.g. after a Wi-Fi outage.
 */
object MessageStamp {
    /** Deliveries later than this are marked as delayed. */
    const val DELAY_NOTE_AFTER_MS = 2 * 60_000L

    fun stamp(text: String, createdAtMs: Long, nowMs: Long, zone: TimeZone = TimeZone.getDefault()): String {
        val fmt = SimpleDateFormat("HH:mm", Locale.US).apply { timeZone = zone }
        val delayMs = nowMs - createdAtMs
        val line = buildString {
            append("🕒 ").append(fmt.format(Date(createdAtMs)))
            if (delayMs >= DELAY_NOTE_AFTER_MS) append(" (delayed ${delayMs / 60_000} min)")
        }
        val nl = text.indexOf('\n')
        return if (nl < 0) "$text\n$line" else text.substring(0, nl) + "\n" + line + text.substring(nl)
    }
}
