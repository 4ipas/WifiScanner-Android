package com.example.wifiscanner.utils

import android.os.Environment
import android.util.Log
import com.example.wifiscanner.models.WifiScanResult
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CsvLogger {
    private const val TAG = "CsvLogger"
    private const val DIR_NAME = "MyWifiScans"
    private const val HEADER = "LocationName;Timestamp;MAC;RSSI;SSID;Frequency;RecordNumber;Channel;NetworkTimestamp;Latitude;Longitude;StepsDelta;StepsTotal;Azimuth;AzimuthConfidence;DeviceOrientation\n"
    private const val TASKS_HEADER = "Timestamp;NodeId;Address;Entrance;Floor;LocationName;SSID;MAC;RSSI;Frequency;RecordNumber;Channel;NetworkTimestamp;Latitude;Longitude;StepsDelta;StepsTotal;Azimuth;AzimuthConfidence;DeviceOrientation\n"

    @Synchronized
    fun logResults(fileName: String, results: List<WifiScanResult>) {
        if (results.isEmpty()) return
        
        // Use standard Documents directory
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), DIR_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }

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
                               "${result.recordNumber};" +
                               formatNewColumns(result)
                    writer.append(line)
                }
            }
            Log.d(TAG, "Wrote ${results.size} results to ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "FAILED to write CSV: ${file.absolutePath}", e)
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

    /** Удаляет CSV-файл задания целиком (по имени файла). */
    fun deleteTaskCsvFile(fileName: String) {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), DIR_NAME)
        val file = File(dir, fileName)
        if (file.exists()) {
            file.delete()
            Log.d(TAG, "Deleted task CSV: $fileName")
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
                               "${result.recordNumber};" +
                               formatNewColumns(result)
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

    /**
     * Форматирует 9 новых столбцов v3.0.0 в строку CSV.
     * Nullable поля записываются как пустые строки.
     * GPS: 6 знаков после запятой. Azimuth: 1 знак.
     */
    private fun formatNewColumns(result: WifiScanResult): String {
        val lat = result.latitude?.let { String.format(java.util.Locale.US, "%.7f", it) } ?: ""
        val lon = result.longitude?.let { String.format(java.util.Locale.US, "%.7f", it) } ?: ""
        val stepsDelta = result.stepsDelta?.toString() ?: ""
        val stepsTotal = result.stepsTotal?.toString() ?: ""
        val azimuth = result.azimuth?.let { String.format("%.1f", it) } ?: ""
        val azimuthConf = result.azimuthConfidence?.let { String.format("%.1f", it) } ?: ""
        val orientation = result.deviceOrientation ?: ""

        return "${result.channel};" +
               "${result.networkTimestamp};" +
               "$lat;$lon;" +
               "$stepsDelta;$stepsTotal;" +
               "$azimuth;$azimuthConf;" +
               "$orientation\n"
    }
}
