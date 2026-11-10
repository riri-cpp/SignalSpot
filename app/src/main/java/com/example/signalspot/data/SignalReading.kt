@file:Suppress("unused")

package com.example.signalspot.data

import kotlin.math.floor

data class SignalReading(
    val id: String = "",
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val gridKey: String = "",
    val accuracy: Float = 0f,
    val carrier: String = "",
    val networkType: String = "",
    val dbm: Int = 0,
    val level: Int = 0,
    val wifiDbm: Int? = null,
    val timestamp: Long = 0L,
)

const val GRID_SIZE = 0.0002

fun gridKeyFor(lat: Double, lng: Double): String {
    val row = floor(lat / GRID_SIZE).toLong()
    val col = floor(lng / GRID_SIZE).toLong()
    return "${row}_$col"
}