package com.example.wifiscanner.utils

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit

/**
 * v5.3.0: Помощник для работы с OEM-специфичными ограничениями батареи.
 *
 * На устройствах Transsion (TECNO/Infinix/Itel) HiOS агрессивно активирует Doze Mode
 * за 4 минуты при выключенном экране, замораживая coroutine scanning loop.
 *
 * Два уровня защиты:
 * 1. REQUEST_IGNORE_BATTERY_OPTIMIZATIONS — стандартный Android API (системный in-app диалог)
 * 2. Автозапуск в Phone Master — OEM-специфичная настройка (требует перехода в настройки)
 */
object OemBatteryHelper {

    private const val PREFS_NAME = "oem_battery_prefs"
    private const val KEY_GUIDE_SHOWN = "oem_guide_shown"

    // ── Детекция производителя ───────────────────────────────────────────

    fun isTranssionDevice(): Boolean {
        val m = Build.MANUFACTURER.lowercase()
        return m.contains("tecno") || m.contains("infinix") || m.contains("itel") || m.contains("transsion")
    }

    // ── Battery optimization whitelist (стандартный Android) ─────────────

    /**
     * Проверяет, исключено ли приложение из оптимизации батареи (Doze).
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Показывает системный диалог «Разрешить приложению работать без ограничений батареи?»
     * Пользователь нажимает «Да» прямо в приложении — не нужно переходить в настройки.
     *
     * @return true если диалог был показан, false если уже в whitelist или ошибка
     */
    fun requestBatteryWhitelist(context: Context): Boolean {
        if (isIgnoringBatteryOptimizations(context)) {
            DiagnosticLogger.log("BATTERY_WHITELIST", "already_whitelisted")
            return false
        }

        return try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            DiagnosticLogger.log("BATTERY_WHITELIST", "dialog_shown")
            true
        } catch (e: Exception) {
            DiagnosticLogger.log("BATTERY_WHITELIST", "failed: ${e.message}")
            false
        }
    }

    // ── OEM Autostart (Transsion Phone Master) ──────────────────────────

    /**
     * Нужно ли показывать OEM guide (однократно, только на Transsion).
     */
    fun shouldShowGuide(context: Context): Boolean {
        if (!isTranssionDevice()) return false
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return !prefs.getBoolean(KEY_GUIDE_SHOWN, false)
    }

    /**
     * Отметить, что guide был показан.
     */
    fun markGuideShown(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_GUIDE_SHOWN, true)
        }
    }

    /**
     * Попытка открыть экран автозапуска в Phone Master (Transsion).
     * Возвращает true если удалось открыть.
     */
    fun openAutoStartSettings(context: Context): Boolean {
        // Известные intent'ы для Phone Master (TECNO/Infinix/Itel)
        val autoStartIntents = listOf(
            // Phone Master — основной менеджер Transsion
            Intent().apply {
                component = ComponentName(
                    "com.transsion.phonemaster",
                    "com.cyin.himgr.autostart.AutoStartActivity"
                )
            },
            // Альтернативный путь Phone Master
            Intent().apply {
                component = ComponentName(
                    "com.transsion.phonemaster",
                    "com.transsion.phonemaster.autostart.AutoStartActivity"
                )
            }
        )

        for (intent in autoStartIntents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (context.packageManager.resolveActivity(intent, 0) != null) {
                    context.startActivity(intent)
                    DiagnosticLogger.log("OEM_AUTOSTART", "opened: ${intent.component}")
                    return true
                }
            } catch (_: Exception) { }
        }

        // Fallback: открыть страницу приложения в системных настройках
        return try {
            val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(fallback)
            DiagnosticLogger.log("OEM_AUTOSTART", "fallback_to_app_settings")
            true
        } catch (e: Exception) {
            DiagnosticLogger.log("OEM_AUTOSTART", "failed: ${e.message}")
            false
        }
    }

    /**
     * Показать AlertDialog с инструкцией для Transsion-устройств.
     * Вызывать из Fragment/Activity (нужен Context с темой).
     */
    fun showTranssionGuide(context: Context, onDismiss: (() -> Unit)? = null) {
        val manufacturer = Build.MANUFACTURER
        AlertDialog.Builder(context)
            .setTitle("Настройка фоновой работы")
            .setMessage(
                "На вашем устройстве $manufacturer для стабильного сбора данных " +
                "в фоне необходимо включить автозапуск приложения.\n\n" +
                "1. Нажмите «Открыть настройки»\n" +
                "2. Найдите WifiScanner в списке\n" +
                "3. Включите автозапуск\n\n" +
                "Это нужно сделать один раз."
            )
            .setPositiveButton("Открыть настройки") { dialog, _ ->
                openAutoStartSettings(context)
                markGuideShown(context)
                dialog.dismiss()
                onDismiss?.invoke()
            }
            .setNegativeButton("Позже") { dialog, _ ->
                markGuideShown(context)
                dialog.dismiss()
                onDismiss?.invoke()
            }
            .setCancelable(false)
            .show()
    }
}
