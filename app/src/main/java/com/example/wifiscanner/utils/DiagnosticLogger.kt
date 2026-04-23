package com.example.wifiscanner.utils

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.util.Log
import androidx.preference.PreferenceManager
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
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
     * v5.1.0: Флаг включения логирования (управляется из настроек).
     * Краш-логи пишутся ВСЕГДА, даже если enabled=false.
     */
    var enabled: Boolean = true
        private set

    private var globalInitialized = false

    /**
     * v5.1.0: Глобальная инициализация при старте приложения.
     * Создаёт файл лога, читает настройку enabled, ставит UncaughtExceptionHandler.
     * НЕ перезатирается при последующих вызовах init() из сервиса.
     */
    fun initGlobal(ctx: Context) {
        if (globalInitialized) return
        globalInitialized = true
        context = ctx.applicationContext
        serviceStartTimeMs = System.currentTimeMillis()

        // Читаем настройку
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        enabled = prefs.getBoolean("pref_enable_logging", true)

        val timestamp = SimpleDateFormat("ddMMyyyy_HH-mm-ss", Locale.US).format(Date())
        fileName = "diag_app_$timestamp.csv"

        // Создать файл и записать заголовок
        val file = getFile() ?: return
        try {
            FileWriter(file, false).use { writer ->
                writer.append(HEADER)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init global diagnostic file", e)
        }

        // Перехват необработанных крашей
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val stackTrace = sw.toString().take(500).replace(";", ",").replace("\n", " | ")
                // Краш-лог пишется ВСЕГДА, даже если enabled=false
                forceLog("APP_CRASH", "thread=${thread.name},err=${throwable.message},stack=$stackTrace", includeDeviceInfo = true)
            } catch (_: Exception) { /* не падаем в обработчике */ }
            defaultHandler?.uncaughtException(thread, throwable)
        }

        if (enabled) {
            log("APP_START", "global_init", includeDeviceInfo = true)
        }
    }

    /**
     * Инициализация логгера при старте сервиса.
     * v5.1.0: Если initGlobal уже вызван — дописывает в тот же файл.
     */
    fun init(ctx: Context, locationName: String? = null) {
        context = ctx.applicationContext
        serviceStartTimeMs = System.currentTimeMillis()
        
        // Если initGlobal уже создал файл — дописываем в него
        if (globalInitialized && fileName != null) {
            log("SERVICE_INIT", "location=$locationName")
            return
        }

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
     * v5.1.0: Проверяет флаг enabled. Если выключен — не пишет (кроме крашей).
     */
    @Synchronized
    fun log(event: String, detail: String = "", includeDeviceInfo: Boolean = false) {
        if (!enabled) return
        forceLog(event, detail, includeDeviceInfo)
    }

    /**
     * v5.1.0: Принудительная запись (для крашей — пишет ВСЕГДА).
     */
    @Synchronized
    fun forceLog(event: String, detail: String = "", includeDeviceInfo: Boolean = false) {
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
