package com.example.wifiscanner

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.preference.PreferenceManager
import com.example.wifiscanner.models.WifiScanResult
import com.example.wifiscanner.repository.WifiRepository
import com.example.wifiscanner.utils.CsvLogger
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class WifiScanService : Service() {

    private lateinit var wifiManager: WifiManager
    private lateinit var locationManager: LocationManager
    private var serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var locationName: String? = null
    private var globalRecordNumber: Long = 0L
    private var lastMaxTimestamp: Long = 0L
    private lateinit var sharedPreferences: SharedPreferences

    companion object {
        const val CHANNEL_ID = "WifiScanChannel"
        const val EXTRA_LOCATION_NAME = "extra_location_name"
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            // Location changed triggers OS-level Wi-Fi scans under the hood
            // The BroadcastReceiver will catch the results.
        }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate() {
        super.onCreate()
        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        locationManager = applicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        locationName = intent?.getStringExtra(EXTRA_LOCATION_NAME)
        
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.service_running))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .build()

        startForeground(1, notification)
        WifiRepository.setScanning(true)

        startPassiveLocationScanning()
        startScanningLoop()

        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startPassiveLocationScanning() {
        try {
            // Request frequent location updates to trick the OS into scanning Wi-Fi
            // to improve location accuracy (works with Wi-Fi scanning enabled in OS settings)
            val providers = locationManager.getProviders(true)
            if (providers.contains(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    5000L, // 5 seconds
                    0f,
                    locationListener,
                    Looper.getMainLooper()
                )
            }
            if (providers.contains(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    5000L, // 5 seconds
                    0f,
                    locationListener,
                    Looper.getMainLooper()
                )
            }
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    private fun startScanningLoop() {
        serviceScope.launch {
            while (isActive) {
                if (wifiManager.isWifiEnabled) {
                    @Suppress("DEPRECATION")
                    wifiManager.startScan()
                    
                    delay(2000) // Wait 2s for OS to process our prompt or passive location prompt
                    handleScanResults()
                    
                    val intervalSeconds = sharedPreferences.getString("pref_scan_interval", "5")?.toLongOrNull() ?: 5L
                    val delayMs = (intervalSeconds * 1000) - 2000L // Subtract the 2s we already waited
                    delay(delayMs.coerceAtLeast(1000L))
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(applicationContext, "Wi-Fi is disabled", Toast.LENGTH_SHORT).show()
                    }
                    delay(5000)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleScanResults() {
        serviceScope.launch(Dispatchers.IO) {
            val results = wifiManager.scanResults
            if (results.isEmpty()) return@launch
            
            val currentMaxTimestamp = results.maxOfOrNull { it.timestamp } ?: 0L
            if (currentMaxTimestamp == lastMaxTimestamp) {
                // OS hasn't produced a new actual scan, skip logging to avoid duplicates
                return@launch
            }
            lastMaxTimestamp = currentMaxTimestamp
            
            val timestampStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            
            val mappedResults = results.map { scanResult ->
                globalRecordNumber++
                
                // Safe SSID extraction logic
                val extractedSsid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    try {
                        scanResult.wifiSsid?.toString()?.removeSurrounding("\"") ?: ""
                    } catch (e: Exception) {
                        @Suppress("DEPRECATION")
                        scanResult.SSID
                    }
                } else {
                    @Suppress("DEPRECATION")
                    scanResult.SSID
                }
                
                WifiScanResult(
                    locationName = locationName,
                    timestamp = timestampStr,
                    mac = scanResult.BSSID ?: "",
                    rssi = scanResult.level,
                    ssid = extractedSsid ?: "",
                    frequency = scanResult.frequency,
                    recordNumber = globalRecordNumber
                )
            }

            // Save to CSV
            CsvLogger.logResults(mappedResults)
            
            // Update Repository UI
            WifiRepository.incrementSnapshot()
            WifiRepository.incrementRecords(mappedResults.size)
            
            // Limit viewable results memory
            WifiRepository.updateResults(mappedResults.sortedByDescending { it.rssi }.take(20))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        try {
            locationManager.removeUpdates(locationListener)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        // WifiRepository.setScanning(false) is now handled by ScanFragment.stopWifiScanService
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "WiFi Scan Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }
}
