package com.signalshield.app

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private fun rad(d: Double) = d * Math.PI / 180.0

fun distMeters(la1: Double, lo1: Double, la2: Double, lo2: Double): Double {
    val r = 6371000.0
    val dLa = rad(la2 - la1)
    val dLo = rad(lo2 - lo1)
    val x = sin(dLa / 2) * sin(dLa / 2) +
        cos(rad(la1)) * cos(rad(la2)) * sin(dLo / 2) * sin(dLo / 2)
    return 2 * r * asin(sqrt(x))
}

fun bearingTo(la1: Double, lo1: Double, la2: Double, lo2: Double): Double {
    val y = sin(rad(lo2 - lo1)) * cos(rad(la2))
    val x = cos(rad(la1)) * sin(rad(la2)) -
        sin(rad(la1)) * cos(rad(la2)) * cos(rad(lo2 - lo1))
    return (atan2(y, x) * 180.0 / Math.PI + 360.0) % 360.0
}
