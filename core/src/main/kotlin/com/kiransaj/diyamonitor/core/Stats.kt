package com.kiransaj.diyamonitor.core

import kotlin.math.sqrt

internal object Stats {
    fun mean(values: List<Double>): Double = if (values.isEmpty()) 0.0 else values.sum() / values.size

    fun stdDev(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val m = mean(values)
        return sqrt(values.sumOf { (it - m) * (it - m) } / (values.size - 1))
    }

    /** Least-squares fit y = intercept + slope * x. Returns null if x has no spread. */
    fun linearRegression(xs: List<Double>, ys: List<Double>): Pair<Double, Double>? {
        if (xs.size < 2 || xs.size != ys.size) return null
        val mx = mean(xs)
        val my = mean(ys)
        var sxx = 0.0
        var sxy = 0.0
        for (i in xs.indices) {
            sxx += (xs[i] - mx) * (xs[i] - mx)
            sxy += (xs[i] - mx) * (ys[i] - my)
        }
        if (sxx == 0.0) return null
        val slope = sxy / sxx
        return Pair(my - slope * mx, slope)
    }
}
