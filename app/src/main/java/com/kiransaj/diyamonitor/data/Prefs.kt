package com.kiransaj.diyamonitor.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.kiransaj.diyamonitor.core.Baseline
import com.kiransaj.diyamonitor.core.DetectionConfig
import com.kiransaj.diyamonitor.core.FlameRegion
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

    // --- App updates ---
    var lastUpdateCheckMs: Long
        get() = sp.getLong("lastUpdateCheckMs", 0L)
        set(v) = sp.edit { putLong("lastUpdateCheckMs", v) }

    /** The newest release version already announced on Telegram, so it is announced only once. */
    var announcedUpdateVersion: String
        get() = sp.getString("announcedUpdateVersion", "")!!
        set(v) = sp.edit { putString("announcedUpdateVersion", v) }

    // --- Flames ---
    /** How many wicks the lamp has (all expected to burn). */
    var expectedFlames by intPref("expectedFlames", 2)
    var exposureLock by boolPref("exposureLock", true)
    /** Exposure compensation applied before locking, in tenths of an EV step (-15 = -1.5 EV). */
    var exposureEvTenths by intPref("exposureEvTenths", -15)

    /** The watched flame boxes, as "left,top,right,bottom,anchorX,anchorY;…". */
    var flames: List<FlameRegion>
        get() = sp.getString("flames", "")!!.split(';').mapNotNull { item ->
            val v = item.split(',').mapNotNull { it.toFloatOrNull() }
            if (v.size == 6) FlameRegion(NormRect(v[0], v[1], v[2], v[3]), v[4], v[5]) else null
        }
        set(list) = sp.edit {
            putString("flames", list.joinToString(";") { f ->
                listOf(f.box.left, f.box.top, f.box.right, f.box.bottom, f.anchorX, f.anchorY).joinToString(",")
            })
        }

    /** Normal readings for each flame, in the same order as [flames]. */
    var flameBaselines: List<Baseline>
        get() = sp.getString("flameBaselines", "")!!.split(';').mapNotNull { item ->
            val v = item.split(',')
            if (v.size != 5) return@mapNotNull null
            val d = v.take(4).map { it.toDoubleOrNull() ?: return@mapNotNull null }
            Baseline(d[0], d[1], d[2], d[3], v[4].toIntOrNull() ?: 0)
        }
        set(list) = sp.edit {
            putString("flameBaselines", list.joinToString(";") { b ->
                "${b.areaPct},${b.brightness},${b.aboveLuma},${b.flickerCv},${b.samples}"
            })
        }

    // --- Screen ---
    /** Minutes after the last touch before the screen may turn off while monitoring (0 = never). */
    var screenOffMinutes by intPref("screenOffMinutes", 2)
    /** Nights on which the camera paused with the screen off; auto-off is disabled from 2. */
    var screenOffFailures by intPref("screenOffFailures", 0)
    val screenAutoOffEnabled: Boolean get() = screenOffMinutes > 0 && screenOffFailures < 2

    /** Baseline of both flames combined, used by the lamp-level alerts. */
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
