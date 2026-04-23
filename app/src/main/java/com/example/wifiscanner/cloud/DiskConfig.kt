package com.example.wifiscanner.cloud

import android.content.Context
import androidx.preference.PreferenceManager
import com.example.wifiscanner.BuildConfig

object DiskConfig {
    const val TASKS_PATH = "app:/tasks"
    const val TASK_RESULTS_PATH = "app:/task_results"
    const val SCAN_RESULTS_PATH = "app:/scan_results"
    const val DIAG_SCANS_PATH = "app:/scan_results/diag_scans"
    const val DIAG_TASKS_PATH = "app:/task_results/diag_tasks"
    const val GT_RESULTS_PATH = "app:/ground_truth" // [CONTROLLER MODE — TEMPORARY]
    
    // Токен загружается из настроек (приоритет) либо берется из BuildConfig по умолчанию
    fun getToken(context: Context): String {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val override = prefs.getString("pref_yadisk_token", null)
        return if (!override.isNullOrBlank()) override else BuildConfig.YANDEX_DISK_TOKEN
    }
}
