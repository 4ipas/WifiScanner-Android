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
    private const val TASKS_HEADER = "Timestamp;NodeId;Address;Entrance;Floor;LocationName;SSID;MAC;RSSI;Frequency;RecordNumber\n"

    @Synchronized
    fun logResults(results: List<WifiScanResult>) {
        if (results.isEmpty()) return
        
        // Use standard Documents directory
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), DIR_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }

        val location = results.first().locationName ?: "unknown"
        val safeLocation = location.replace("/", "_").replace("\\", "_").replace(";", ",")
        val fileName = "wifi_scan_$safeLocation.csv"
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

    fun deleteForLocation(locationName: String) {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), DIR_NAME)
        val safeLocation = locationName.replace("/", "_").replace("\\", "_").replace(";", ",")
        val fileName = "wifi_scan_$safeLocation.csv"
        val file = File(dir, fileName)
        if (file.exists()) {
            file.delete()
        }
    }

    @Synchronized
    fun logTasksResults(fileName: String, results: List<WifiScanResult>) {
        if (results.isEmpty()) return
        
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), DIR_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }

        val file = File(dir, fileName)
        val isNewFile = !file.exists()

        try {
            FileWriter(file, true).use { writer ->
                if (isNewFile) {
                    writer.append(TASKS_HEADER)
                }
                for (result in results) {
                    val safeLocation = result.locationName?.replace(";", ",") ?: ""
                    val safeAddress = result.address?.replace(";", ",") ?: ""
                    val safeEntrance = result.entrance?.replace(";", ",") ?: ""
                    val safeFloor = result.floor?.replace(";", ",") ?: ""
                    val safeSsid = result.ssid?.replace(";", ",") ?: ""
                    val safeNodeId = result.nodeId ?: ""
                    
                    val line = "${result.timestamp};" +
                               "$safeNodeId;" +
                               "$safeAddress;" +
                               "$safeEntrance;" +
                               "$safeFloor;" +
                               "$safeLocation;" +
                               "$safeSsid;" +
                               "${result.mac};" +
                               "${result.rssi};" +
                               "${result.frequency};" +
                               "${result.recordNumber}\n"
                    writer.append(line)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @Synchronized
    fun removeLocationFromTasksCsv(fileName: String, nodeId: String) {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), DIR_NAME)
        val file = File(dir, fileName)
        if (!file.exists()) return
        
        try {
            val lines = file.readLines()
            val filteredLines = lines.filter { line -> 
                if (line.startsWith("Timestamp;")) true // keep header
                else line.split(";").getOrNull(1) != nodeId
            }
            FileWriter(file, false).use { writer ->
                for (line in filteredLines) {
                    writer.append(line).append("\n")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
