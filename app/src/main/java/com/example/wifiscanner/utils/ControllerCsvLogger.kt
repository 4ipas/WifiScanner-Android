package com.example.wifiscanner.utils

import android.os.Environment
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * [CONTROLLER MODE — TEMPORARY, REMOVABLE]
 * CSV-логгер для записи чекпоинтов контролёра.
 * Формат: Timestamp;Address;Entrance;Zone;EventType
 */
object ControllerCsvLogger {

    private const val HEADER = "Timestamp;Address;Entrance;Zone;EventType"
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private val filenameDateFormat = SimpleDateFormat("ddMMyyyy_HH-mm-ss", Locale.getDefault())

    private var currentFilename: String? = null
    private var writer: OutputStreamWriter? = null
    private var file: File? = null

    val activeFilename: String? get() = currentFilename

    fun startSession(addressName: String): String {
        val now = Date()
        val safeName = addressName.replace(Regex("[^\\p{L}\\p{N}_\\-]"), "_")
        val filename = "GT_${safeName}_${filenameDateFormat.format(now)}.csv"
        
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            "MyWifiScans"
        )
        if (!dir.exists()) dir.mkdirs()

        file = File(dir, filename)
        val fos = FileOutputStream(file!!, false)
        writer = OutputStreamWriter(fos, Charsets.UTF_8)
        writer!!.write(HEADER + "\n")
        writer!!.flush()
        currentFilename = filename

        // SESSION_START event
        writeEvent("", "", "", "SESSION_START")
        return filename
    }

    fun writeCheckpoint(address: String, entrance: String, zone: String) {
        writeEvent(address, entrance, zone, "CHECKPOINT")
    }

    fun stopSession() {
        writeEvent("", "", "", "SESSION_END")
        try {
            writer?.flush()
            writer?.close()
        } catch (_: Exception) {}
        writer = null
        currentFilename = null
    }

    fun getFile(): File? = file

    private fun writeEvent(address: String, entrance: String, zone: String, eventType: String) {
        val w = writer ?: return
        val timestamp = dateFormat.format(Date())
        w.write("$timestamp;$address;$entrance;$zone;$eventType\n")
        w.flush()
    }
}
