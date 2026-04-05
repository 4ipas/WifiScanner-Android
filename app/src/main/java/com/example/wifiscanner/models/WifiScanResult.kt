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
    val recordNumber: Long,
    // --- v3.0.0: Новые поля (добавляются в конец CSV) ---
    val channel: Int = -1,
    val networkTimestamp: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
    val stepsDelta: Int? = null,
    val stepsTotal: Int? = null,
    val azimuth: Float? = null,
    val azimuthConfidence: Float? = null,
    val deviceOrientation: String? = null
)
