package com.example.wifiscanner.models

data class ScanSession(
    val id: Int,
    val locationName: String,
    val startTime: String,
    val endTime: String?,
    val snapshotCount: Int,
    val recordCount: Int,
    /** Имя CSV-файла для ручного режима (wifi_scan_*.csv). Null для task-mode сессий. */
    val csvFileName: String? = null,
    /** true = запущено из вкладки «Запись», false = из «Заданий» */
    val isManualScan: Boolean = false
)
