package com.example.wifiscanner.utils

import android.os.Environment
import com.example.wifiscanner.models.WifiScanResult
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CsvLogger {
    private const val DIR_NAME = "MyWifiScans"
    private const val HEADER = "LocationName;Timestamp;MAC;RSSI;SSID;Frequency;RecordNumber\n"

    @Synchronized
    fun logResults(results: List<WifiScanResult>) {
        if (results.isEmpty()) return
        
        // Use standard Documents directory
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), DIR_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }

        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val fileName = "wifi_scan_${dateFormat.format(Date())}.csv"
        val file = File(dir, fileName)
        
        val isNewFile = !file.exists()

        try {
            FileWriter(file, true).use { writer ->
                if (isNewFile) {
                    writer.append(HEADER)
                }
                for (result in results) {
                    // Escape semicolons in optional text fields just in case
                    val safeLocation = result.locationName?.replace(";", ",") ?: ""
                    val safeSsid = result.ssid?.replace(";", ",") ?: ""
                    val line = "$safeLocation;" +
                               "${result.timestamp};" +
                               "${result.mac};" +
                               "${result.rssi};" +
                               "$safeSsid;" +
                               "${result.frequency};" +
                               "${result.recordNumber}\n"
                    writer.append(line)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
