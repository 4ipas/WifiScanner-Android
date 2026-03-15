package com.example.wifiscanner.models

data class WifiScanResult(
    val locationName: String?,
    val timestamp: String,
    val mac: String,
    val rssi: Int,
    val ssid: String?,
    val frequency: Int,
    val recordNumber: Long
)
