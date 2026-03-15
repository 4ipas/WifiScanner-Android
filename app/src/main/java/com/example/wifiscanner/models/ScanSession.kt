package com.example.wifiscanner.models

data class ScanSession(
    val id: Int,
    val locationName: String,
    val startTime: String,
    val endTime: String?,
    val snapshotCount: Int,
    val recordCount: Int
)
