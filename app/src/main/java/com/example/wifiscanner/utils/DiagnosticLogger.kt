package com.example.wifiscanner.utils

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v4.1.0: Пишет диагностические события в CSV-файл для анализа проблем
 * с фоновым сканированием на разных устройствах.
 *
 * Файл сохраняется в Documents/MyWifiScans/ (та же папка, что и сканы)
 * и может быть передан через существующий механизм «Передать по...».
 *
 * Формат CSV (разделитель ;):
 * Timestamp;ElapsedSec;Event;Detail;ScreenOn;DozeMode;BatteryPct;DeviceModel;AndroidVer
 */
object DiagnosticLogger {
    private const val TAG = "DiagnosticLogger"
    private const val DIR_NAME = "MyWifiScans"
    private const val HEADER = "Timestamp;ElapsedSec;Event;Detail;ScreenOn;DozeMode;BatteryPct;DeviceModel;AndroidVer\n"

    private var fileName: String? = null
    private var serviceStartTimeMs: Long = 0L
    private var context: Context? = null

    /**
     * Инициализация логгера при старте сервиса.
     * Создаёт новый CSV-файл с заголовком.
     */
    fun init(ctx: Context, locationName: String? = null) {
        context = ctx.applicationContext
        serviceStartTimeMs = System.currentTimeMillis()
        val timestamp = SimpleDateFormat("ddMMyyyy_HH-mm-ss", Locale.US).format(Date())
        val safeLoc = locationName?.trim()?.replace(Regex("[^\\p{L}\\p{N}_\\-]"), "_")?.take(50)
        fileName = if (!safeLoc.isNullOrBlank()) {
            "${safeLoc}_diag_$timestamp.csv"
        } else {
            "diag_$timestamp.csv"
        }

        // Создать файл и записать заголовок
        val file = getFile() ?: return
        try {
            FileWriter(file, false).use { writer ->
                writer.append(HEADER)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init diagnostic file", e)
        }
    }

    /**
     * Записать событие. Thread-safe.
     *
     * @param event тип события (SERVICE_START, SCAN_REQUEST, SCAN_RESULT, etc.)
     * @param detail произвольная строка с деталями
     * @param includeDeviceInfo true = записать модель и версию Android (только для SERVICE_START)
     */
    @Synchronized
    fun log(event: String, detail: String = "", includeDeviceInfo: Boolean = false) {
        val file = getFile() ?: return
        val ctx = context ?: return

        try {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            val now = System.currentTimeMillis()
            val elapsedSec = (now - serviceStartTimeMs) / 1000

            val screenOn = isScreenOn(ctx)
            val dozeMode = isDozeMode(ctx)
            val batteryPct = getBatteryPercent(ctx)

            val deviceModel = if (includeDeviceInfo) Build.MODEL.replace(";", ",") else ""
            val androidVer = if (includeDeviceInfo) Build.VERSION.RELEASE else ""

            val safeDetail = detail.replace(";", ",").replace("\n", " ")

            val line = "${sdf.format(Date(now))};$elapsedSec;$event;$safeDetail;$screenOn;$dozeMode;$batteryPct;$deviceModel;$androidVer\n"

            FileWriter(file, true).use { writer ->
                writer.append(line)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write diagnostic event: $event", e)
        }
    }

    /**
     * Возвращает текущее имя файла диагностики (для передачи через Share).
     */
    fun getFileName(): String? = fileName

    /**
     * Сброс при остановке сервиса.
     */
    fun release() {
        context = null
        fileName = null
    }

    // ─── Private helpers ─────────────────────────────────────────────

    private fun getFile(): File? {
        val name = fileName ?: return null
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            DIR_NAME
        )
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return File(dir, name)
    }

    private fun isScreenOn(ctx: Context): Boolean {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
        return pm?.isInteractive ?: true
    }

    private fun isDozeMode(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
        return pm?.isDeviceIdleMode ?: false
    }

    private fun getBatteryPercent(ctx: Context): Int {
        return try {
            val batteryStatus = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            if (scale > 0) (level * 100 / scale) else -1
        } catch (e: Exception) {
            -1
        }
    }
}
