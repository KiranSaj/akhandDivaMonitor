package com.kiransaj.diyamonitor.core

/** Compares release versions such as "v1.2.0" or "1.10.3". Pre-release suffixes ("-dev.5") are ignored. */
object AppVersion {

    /** Numeric parts of [version], or null if it is not a version at all. */
    fun parse(version: String): List<Int>? {
        val core = version.trim().removePrefix("v").substringBefore('-').substringBefore('+')
        if (core.isEmpty()) return null
        val parts = core.split('.').map { it.toIntOrNull() ?: return null }
        return parts
    }

    /** True if [candidate] is a strictly newer version than [current]. Unparseable candidates are never newer. */
    fun isNewer(candidate: String, current: String): Boolean {
        val c = parse(candidate) ?: return false
        val cur = parse(current) ?: return true
        for (i in 0 until maxOf(c.size, cur.size)) {
            val a = c.getOrElse(i) { 0 }
            val b = cur.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }
}
