package com.kiransaj.diyamonitor.core

enum class Severity { INFO, WARNING, URGENT }

enum class AlertType(val severity: Severity, val title: String, val repeatWhileActive: Boolean = false) {
    FLAME_OUT(Severity.URGENT, "🔥❌ URGENT: flame appears to be OUT", repeatWhileActive = true),
    DIMMING(Severity.WARNING, "⚠️ Flame is dimming"),
    LOW_OIL(Severity.WARNING, "🛢️ Oil may be running low"),
    SOOT(Severity.WARNING, "⚠️ Possible soot / trim the wick"),
    CAMERA_STALLED(Severity.URGENT, "📷 Camera stopped delivering frames"),
    BATTERY_LOW(Severity.WARNING, "🔋 Phone battery low"),
    CHARGER_DISCONNECTED(Severity.WARNING, "🔌 Phone is not charging"),
    BATTERY_HOT(Severity.WARNING, "🌡️ Phone battery is hot"),
}

/**
 * Remembers when each alert type last fired so the same alert is not repeated
 * more often than the cooldown.
 */
class CooldownGate(var cooldownMs: Long) {
    private val lastFired = mutableMapOf<AlertType, Long>()

    fun canFire(type: AlertType, nowMs: Long): Boolean {
        val last = lastFired[type] ?: return true
        return nowMs - last >= cooldownMs
    }

    fun markFired(type: AlertType, nowMs: Long) {
        lastFired[type] = nowMs
    }

    fun tryFire(type: AlertType, nowMs: Long): Boolean {
        if (!canFire(type, nowMs)) return false
        markFired(type, nowMs)
        return true
    }
}
