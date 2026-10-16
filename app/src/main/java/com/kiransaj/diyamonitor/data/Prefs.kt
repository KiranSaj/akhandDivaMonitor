package com.kiransaj.diyamonitor.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.kiransaj.diyamonitor.core.Baseline
import com.kiransaj.diyamonitor.core.DetectionConfig
import com.kiransaj.diyamonitor.core.NormRect

/** All persistent settings, backed by SharedPreferences. */
class Prefs(context: Context) {
    private val sp: SharedPreferences = context.applicationContext.getSharedPreferences("diya", Context.MODE_PRIVATE)

    var botToken: String
        get() = sp.getString("botToken", "")!!
        set(v) = sp.edit { putString("botToken", v.trim()) }

    var chatId: String
        get() = sp.getString("chatId", "")!!
        set(v) = sp.edit { putString("chatId", v.trim()) }

    val telegramConfigured: Boolean get() = botToken.isNotBlank() && chatId.isNotBlank()

    // --- Sensitivity (percentages) ---
    var outPct by intPref("outPct", 10)
    var dimPct by intPref("dimPct", 60)
    var oversizePct by intPref("oversizePct", 160)
    /** 1 (least sensitive) .. 5 (most sensitive). */
    var flickerSensitivity by intPref("flickerSensitivity", 3)
    var lowOilDeclinePct by intPref("lowOilDeclinePct", 30)
    var brightThreshold by intPref("brightThreshold", 200)

    // --- Timing ---
    var checkIntervalSec by intPref("checkIntervalSec", 2)
    var cooldownMin by intPref("cooldownMin", 15)
    var heartbeatEnabled by boolPref("heartbeatEnabled", true)
    var heartbeatHours by intPref("heartbeatHours", 2)
    var phoneHealthAlerts by boolPref("phoneHealthAlerts", true)
    /** Quiet test mode: silent Telegram notifications, [TEST] prefix, timings x0.1. */
    var testMode by boolPref("testMode", false)

    // --- Monitoring state ---
    /** True while the user wants monitoring running; used to detect unexpected stops. */
    var monitoringActive by boolPref("monitoringActive", false)

    var roi: NormRect
        get() = if (!sp.contains("roiL")) NormRect.DEFAULT else NormRect(
            sp.getFloat("roiL", 0f), sp.getFloat("roiT", 0f), sp.getFloat("roiR", 1f), sp.getFloat("roiB", 1f),
        )
        set(r) = sp.edit {
            putFloat("roiL", r.left); putFloat("roiT", r.top); putFloat("roiR", r.right); putFloat("roiB", r.bottom)
        }

    var baseline: Baseline?
        get() = if (!sp.contains("blArea")) null else Baseline(
            areaPct = sp.getFloat("blArea", 0f).toDouble(),
            brightness = sp.getFloat("blBright", 0f).toDouble(),
            aboveLuma = sp.getFloat("blAbove", 0f).toDouble(),
            flickerCv = sp.getFloat("blFlicker", 0f).toDouble(),
            samples = sp.getInt("blSamples", 0),
        )
        set(b) = sp.edit {
            if (b == null) {
                remove("blArea"); remove("blBright"); remove("blAbove"); remove("blFlicker"); remove("blSamples")
            } else {
                putFloat("blArea", b.areaPct.toFloat())
                putFloat("blBright", b.brightness.toFloat())
                putFloat("blAbove", b.aboveLuma.toFloat())
                putFloat("blFlicker", b.flickerCv.toFloat())
                putInt("blSamples", b.samples)
            }
        }

    fun detectionConfig(): DetectionConfig {
        val base = DetectionConfig(
            outFraction = outPct / 100.0,
            dimFraction = dimPct / 100.0,
            oversizeFraction = oversizePct / 100.0,
            // Sensitivity 1 -> alert at 5x normal flicker, 5 -> at 1.5x.
            flickerFactor = doubleArrayOf(5.0, 4.0, 3.0, 2.0, 1.5)[(flickerSensitivity - 1).coerceIn(0, 4)],
            flickerMinCv = doubleArrayOf(0.25, 0.2, 0.15, 0.12, 0.1)[(flickerSensitivity - 1).coerceIn(0, 4)],
            lowOilDecline = lowOilDeclinePct / 100.0,
            cooldownMs = cooldownMin * 60_000L,
        )
        return if (testMode) base.timeScaled(0.1) else base
    }

    private fun intPref(key: String, default: Int) = object : kotlin.properties.ReadWriteProperty<Any?, Int> {
        override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>) = sp.getInt(key, default)
        override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: Int) = sp.edit { putInt(key, value) }
    }

    private fun boolPref(key: String, default: Boolean) = object : kotlin.properties.ReadWriteProperty<Any?, Boolean> {
        override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>) = sp.getBoolean(key, default)
        override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: Boolean) = sp.edit { putBoolean(key, value) }
    }
}
