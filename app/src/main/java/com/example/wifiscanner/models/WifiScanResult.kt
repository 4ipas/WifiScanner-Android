package com.example.wifiscanner.models

data class WifiScanResult(
    val nodeId: String? = null,
    val address: String? = null,
    val entrance: String? = null,
    val floor: String? = null,
    val locationName: String?,
    val timestamp: String,
    val mac: String,
    val rssi: Int,
    val ssid: String?,
    val frequency: Int,
    val recordNumber: Long
)
