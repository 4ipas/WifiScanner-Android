package com.example.wifiscanner

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.location.Location
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.preference.PreferenceManager
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.example.wifiscanner.models.WifiScanResult
import com.example.wifiscanner.repository.WifiRepository
import com.example.wifiscanner.utils.CsvLogger
import com.example.wifiscanner.utils.SensorCollector
import com.example.wifiscanner.utils.frequencyToChannel
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class WifiScanService : Service() {

    private lateinit var wifiManager: WifiManager
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var sensorCollector: SensorCollector
    private var serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var locationName: String? = null
    private var requiredScans: Int? = null
    private var isTaskMode: Boolean = false
    private var nodeIdString: String = ""
    private var addressString: String = ""
    private var entranceString: String = ""
    private var floorString: String = ""
    private var taskCsvFilename: String = ""
    private var globalRecordNumber: Long = 0L
    private var lastMaxTimestamp: Long = 0L
    private lateinit var sharedPreferences: SharedPreferences

    // v3.0.0: Кэшированные GPS-координаты
    private var cachedLatitude: Double? = null
    private var cachedLongitude: Double? = null

    // v3.0.0: Имя файла для ручной записи (уникально для каждой сессии)
    private var manualScanFileName: String = ""

    companion object {
        const val CHANNEL_ID = "WifiScanChannel"
        const val EXTRA_LOCATION_NAME = "extra_location_name"
        const val EXTRA_REQUIRED_SCANS = "extra_required_scans"
        const val EXTRA_IS_TASK = "extra_is_task"
        const val EXTRA_NODE_ID = "extra_node_id"
        const val EXTRA_ADDRESS = "extra_address"
        const val EXTRA_ENTRANCE = "extra_entrance"
        const val EXTRA_FLOOR = "extra_floor"
        const val EXTRA_CSV_FILENAME = "extra_csv_filename"
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            // High accuracy location requests force the OS to scan Wi-Fi
            // Our separate polling loop catches these fresh results from the OS cache
            // v3.0.0: Кэшируем последние GPS-координаты
            locationResult.lastLocation?.let { location ->
                cachedLatitude = location.latitude
                cachedLongitude = location.longitude
            }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate() {
        super.onCreate()
        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)

        // v3.0.0: Инициализация сборщика IMU-сенсоров
        sensorCollector = SensorCollector(applicationContext)
        sensorCollector.start()

        createNotificationChannel()

        // v3.0.0: Попробовать получить начальные GPS-координаты
        fetchInitialLocation()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        locationName = intent?.getStringExtra(EXTRA_LOCATION_NAME)
        isTaskMode = intent?.getBooleanExtra(EXTRA_IS_TASK, false) ?: false
        if (isTaskMode) {
            nodeIdString = intent?.getStringExtra(EXTRA_NODE_ID) ?: ""
            addressString = intent?.getStringExtra(EXTRA_ADDRESS) ?: ""
            entranceString = intent?.getStringExtra(EXTRA_ENTRANCE) ?: ""
            floorString = intent?.getStringExtra(EXTRA_FLOOR) ?: ""
            taskCsvFilename = intent?.getStringExtra(EXTRA_CSV_FILENAME) ?: "wifi_tasks_default.csv"
            WifiRepository.currentSessionCsvFileName = taskCsvFilename
            WifiRepository.currentSessionIsManual = false
        } else {
            // Генерируем уникальное имя файла для ручного режима: wifi_scan_ddMMyyyy_HH-mm-ss.csv
            val timestamp = SimpleDateFormat("ddMMyyyy_HH-mm-ss", Locale.US).format(Date())
            manualScanFileName = "wifi_scan_$timestamp.csv"
            WifiRepository.currentSessionCsvFileName = manualScanFileName
            WifiRepository.currentSessionIsManual = true
        }
        
        if (intent?.hasExtra(EXTRA_REQUIRED_SCANS) == true) {
            requiredScans = intent.getIntExtra(EXTRA_REQUIRED_SCANS, 5)
        } else {
            requiredScans = null
        }
        
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

        // На Android 14+ тип HEALTH позволяет работать шагомеру в фоне
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            }
            startForeground(1, notification, type)
        } else {
            startForeground(1, notification)
        }

        WifiRepository.setScanning(true)

        startPassiveLocationScanning()
        startScanningLoop()

        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startPassiveLocationScanning() {
        try {
            val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000L).apply {
                setMinUpdateIntervalMillis(5000L)
                setMaxUpdateDelayMillis(5000L)
            }.build()

            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    /**
     * v3.0.0: Получить начальные GPS-координаты при старте сервиса.
     */
    @SuppressLint("MissingPermission")
    private fun fetchInitialLocation() {
        try {
            fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
                location?.let {
                    cachedLatitude = it.latitude
                    cachedLongitude = it.longitude
                }
            }
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    private fun startScanningLoop() {
        serviceScope.launch {
            // v3.0.0: Охлаждение/Прогрев перед первым сканом (для стабилизации сборщика и ОС)
            val cooldownSeconds = sharedPreferences.getString("pref_scan_cooldown", "5")?.toLongOrNull() ?: 5L
            if (cooldownSeconds > 0) {
                delay(cooldownSeconds * 1000L)
            }

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
            
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            val currentBatchTimeStr = sdf.format(Date())

            // v3.0.0: Снимок сенсоров (один на весь батч — все сети получат одинаковые PDR-данные)
            val sensorSnapshot = sensorCollector.getSnapshot()

            // v3.0.0: Текущее смещение для конвертации boot-time в wall-clock
            val wallClockOffsetMs = System.currentTimeMillis() - (SystemClock.elapsedRealtimeNanos() / 1_000_000)

            // Сначала сортируем по RSSI по убыванию, чтобы самые сильные сети получали первые RecordNumber
            val sortedResults = results.sortedByDescending { it.level }
            val netTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

            val mappedResults = sortedResults.map { result ->
                globalRecordNumber++
                
                // v3.0.0: Конвертация ScanResult.timestamp (мкс boot-time) в мс wall-clock
                val networkWallClockMs = wallClockOffsetMs + (result.timestamp / 1000)
                val networkTimestampStr = netTimeFormat.format(Date(networkWallClockMs))

                WifiScanResult(
                    nodeId = if (isTaskMode) nodeIdString else null,
                    address = if (isTaskMode) addressString else null,
                    entrance = if (isTaskMode) entranceString else null,
                    floor = if (isTaskMode) floorString else null,
                    locationName = locationName,
                    timestamp = currentBatchTimeStr,
                    mac = result.BSSID,
                    rssi = result.level,
                    ssid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        result.wifiSsid?.toString()?.removeSurrounding("\"") ?: ""
                    } else {
                        @Suppress("DEPRECATION")
                        result.SSID
                    },
                    frequency = result.frequency,
                    recordNumber = globalRecordNumber,
                    // v3.0.0: Новые поля
                    channel = frequencyToChannel(result.frequency),
                    networkTimestamp = networkTimestampStr,
                    latitude = cachedLatitude,
                    longitude = cachedLongitude,
                    stepsDelta = sensorSnapshot.stepsDelta,
                    stepsTotal = sensorSnapshot.stepsTotal,
                    azimuth = sensorSnapshot.azimuth,
                    azimuthConfidence = sensorSnapshot.azimuthConfidence,
                    deviceOrientation = sensorSnapshot.deviceOrientation
                )
            }

            if (mappedResults.isNotEmpty()) {
                if (isTaskMode) {
                    CsvLogger.logTasksResults(taskCsvFilename, mappedResults)
                } else {
                    // Используем уникальное имя файла с временной меткой
                    CsvLogger.logResults(manualScanFileName, mappedResults)
                }
            }
            // Update Repository UI
            WifiRepository.incrementRecords(mappedResults.size)
            WifiRepository.incrementSnapshot()
            
            // v3.0.0: Обновить данные датчиков для отображения в UI
            WifiRepository.updateSensorSnapshot(sensorSnapshot)
            cachedLatitude?.let { lat ->
                cachedLongitude?.let { lon ->
                    WifiRepository.updateLocation(lat, lon)
                }
            }

            // Limit viewable results memory
            WifiRepository.updateResults(mappedResults.take(20))
            
            val req = requiredScans
            if (req != null && WifiRepository.totalSnapshots.value >= req) {
                val endTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                WifiRepository.stopSession(endTime)
                stopSelf() // Automatically kill Android Background Service!
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()

        // v3.0.0: Остановить сбор IMU-данных
        sensorCollector.stop()

        try {
            fusedLocationClient.removeLocationUpdates(locationCallback)
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
