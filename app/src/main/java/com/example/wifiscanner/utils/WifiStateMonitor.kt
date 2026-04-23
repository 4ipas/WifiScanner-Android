package com.example.wifiscanner.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/**
 * v5.1.0: Мониторинг состояния WiFi во время записи/выполнения заданий.
 *
 * Слушает WifiManager.WIFI_STATE_CHANGED_ACTION и при выключении WiFi:
 * 1. Вибрация (800ms)
 * 2. Вызывает callback для показа модального окна в UI
 *
 * Использование:
 *   val monitor = WifiStateMonitor(context) { showWifiDisabledDialog() }
 *   monitor.startMonitoring()   // при старте записи
 *   monitor.stopMonitoring()    // при остановке записи
 */
class WifiStateMonitor(
    private val context: Context,
    private val onWifiDisabled: () -> Unit
) {
    private val TAG = "WifiStateMonitor"
    private var isRegistered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action != WifiManager.WIFI_STATE_CHANGED_ACTION) return

            val state = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN)
            when (state) {
                WifiManager.WIFI_STATE_DISABLED, WifiManager.WIFI_STATE_DISABLING -> {
                    Log.w(TAG, "WiFi disabled during active session!")
                    DiagnosticLogger.log("WIFI_MONITOR", "wifi_disabled_during_session")
                    vibrateWarning()
                    onWifiDisabled()
                }
                WifiManager.WIFI_STATE_ENABLED -> {
                    Log.d(TAG, "WiFi re-enabled")
                    DiagnosticLogger.log("WIFI_MONITOR", "wifi_re_enabled")
                }
            }
        }
    }

    /**
     * Начать мониторинг состояния WiFi.
     * Вызывать при старте записи или выполнения задания.
     */
    fun startMonitoring() {
        if (isRegistered) return
        try {
            val filter = IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
            isRegistered = true
            Log.d(TAG, "WiFi monitoring started")

            // Проверить текущее состояние сразу
            val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
            if (!wifiManager.isWifiEnabled) {
                vibrateWarning()
                onWifiDisabled()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start monitoring", e)
        }
    }

    /**
     * Остановить мониторинг.
     * Вызывать при остановке записи.
     */
    fun stopMonitoring() {
        if (!isRegistered) return
        try {
            context.unregisterReceiver(receiver)
            isRegistered = false
            Log.d(TAG, "WiFi monitoring stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop monitoring", e)
        }
    }

    private fun vibrateWarning() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                val vibrator = vibratorManager.defaultVibrator
                vibrator.vibrate(VibrationEffect.createOneShot(800, VibrationEffect.DEFAULT_AMPLITUDE))
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                vibrator.vibrate(VibrationEffect.createOneShot(800, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                @Suppress("DEPRECATION")
                vibrator.vibrate(800)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Vibration failed", e)
        }
    }
}
