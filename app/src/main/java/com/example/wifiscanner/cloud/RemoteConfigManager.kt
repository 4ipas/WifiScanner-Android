package com.example.wifiscanner.cloud

import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager
import com.example.wifiscanner.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object RemoteConfigManager {
    private const val TAG = "RemoteConfigManager"
    private const val PREF_CONFIG_JSON = "remote_config_json"
    
    suspend fun fetchConfig(context: Context) {
        withContext(Dispatchers.IO) {
            try {
                val url = URL(BuildConfig.FEATURE_TOGGLE_URL)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.setRequestProperty("X-API-Key", BuildConfig.CONFIG_API_KEY)
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                
                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val prefs = PreferenceManager.getDefaultSharedPreferences(context)
                    prefs.edit().putString(PREF_CONFIG_JSON, response).apply()
                    Log.d(TAG, "Config fetched successfully: $response")
                    com.example.wifiscanner.utils.DiagnosticLogger.forceLog("REMOTE_CONFIG", "success, response=$response")
                } else {
                    Log.e(TAG, "Failed to fetch config: HTTP ${connection.responseCode}")
                    com.example.wifiscanner.utils.DiagnosticLogger.forceLog("REMOTE_CONFIG", "error HTTP ${connection.responseCode}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching remote config", e)
                com.example.wifiscanner.utils.DiagnosticLogger.forceLog("REMOTE_CONFIG", "exception: ${e.javaClass.simpleName} - ${e.message}")
            }
        }
    }

    fun isFeatureEnabled(context: Context, key: String, defaultValue: Boolean): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val jsonStr = prefs.getString(PREF_CONFIG_JSON, null)
        
        if (jsonStr != null) {
            try {
                val jsonObj = JSONObject(jsonStr)
                if (jsonObj.has("toggles")) {
                    val toggles = jsonObj.getJSONObject("toggles")
                    if (toggles.has(key)) {
                        return toggles.getBoolean(key)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing remote config JSON", e)
            }
        }
        
        return defaultValue
    }
}
