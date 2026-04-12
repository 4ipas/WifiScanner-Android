package com.example.wifiscanner.repository

import com.example.wifiscanner.models.NodeDTO
import com.example.wifiscanner.models.ScanSession
import com.example.wifiscanner.models.WifiScanResult
import com.example.wifiscanner.utils.SensorSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Stack

object WifiRepository {
    /** Список всех загруженных заданий (домов). Множественные задания одновременно. */
    val rootNodes = mutableListOf<NodeDTO>()
    val navStack = Stack<NodeDTO>()
    var activeScanNode: NodeDTO? = null
    var pendingScanNode: NodeDTO? = null

    /** Per-entrance CSV filenames: entranceNodeId → filename */
    val entranceCsvFilenames = mutableMapOf<String, String>()

    /** CSV filename активного скана (для cancel/reset без навигации) */
    var activeScanCsvFilename: String? = null

    private val _scanResults = MutableStateFlow<List<WifiScanResult>>(emptyList())
    val scanResults: StateFlow<List<WifiScanResult>> = _scanResults.asStateFlow()

    private val _totalRecords = MutableStateFlow(0)
    val totalRecords: StateFlow<Int> = _totalRecords.asStateFlow()
    
    private val _totalSnapshots = MutableStateFlow(0)
    val totalSnapshots: StateFlow<Int> = _totalSnapshots.asStateFlow()
    
    private val _sessionHistory = MutableStateFlow<List<ScanSession>>(emptyList())
    val sessionHistory: StateFlow<List<ScanSession>> = _sessionHistory.asStateFlow()
    
    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _sensorSnapshot = MutableStateFlow<SensorSnapshot?>(null)
    val sensorSnapshot: StateFlow<SensorSnapshot?> = _sensorSnapshot.asStateFlow()

    private val _lastLocation = MutableStateFlow<Pair<Double, Double>?>(null)
    val lastLocation: StateFlow<Pair<Double, Double>?> = _lastLocation.asStateFlow()

    private var currentSessionId = 0
    private var currentSessionStartTime = ""
    private var currentLocationName = ""

    fun updateResults(results: List<WifiScanResult>) {
        _scanResults.value = results
    }

    fun incrementRecords(count: Int) {
        _totalRecords.value += count
    }
    
    fun incrementSnapshot() {
        _totalSnapshots.value += 1
    }
    
    /** CSV-файл текущей сессии (для manual scans) */
    var currentSessionCsvFileName: String? = null
    /** true = ручной скан (вкладка Запись), false = из заданий */
    var currentSessionIsManual: Boolean = false

    fun startNewSession(locationName: String, startTime: String) {
        _totalRecords.value = 0
        _totalSnapshots.value = 0
        currentSessionId++
        currentLocationName = locationName
        currentSessionStartTime = startTime
        setScanning(true)
    }

    fun stopSession(endTime: String) {
        if (_isScanning.value) {
            val session = ScanSession(
                id = currentSessionId,
                locationName = currentLocationName,
                startTime = currentSessionStartTime,
                endTime = endTime,
                snapshotCount = _totalSnapshots.value,
                recordCount = _totalRecords.value,
                csvFileName = currentSessionCsvFileName,
                isManualScan = currentSessionIsManual
            )
            val updatedHistory = _sessionHistory.value.toMutableList()
            updatedHistory.add(0, session)
            _sessionHistory.value = updatedHistory
            setScanning(false)
        }
    }

    fun setScanning(scanning: Boolean) {
        _isScanning.value = scanning
    }

    fun updateSensorSnapshot(snapshot: SensorSnapshot) {
        _sensorSnapshot.value = snapshot
    }

    fun updateLocation(latitude: Double, longitude: Double) {
        _lastLocation.value = Pair(latitude, longitude)
    }

    /**
     * Ленивое создание имени CSV-файла для подъезда.
     * Формат: АдресДома_НомерПодъезда_YYYYMMDD_HHmm.csv
     */
    fun getCsvFilenameForEntrance(entranceId: String, addressName: String, entranceName: String): String {
        return entranceCsvFilenames.getOrPut(entranceId) {
            val safeAddress = addressName.replace(Regex("[^\\wа-яА-ЯёЁ0-9]"), "_")
            val safeEntrance = entranceName.replace(Regex("[^\\wа-яА-ЯёЁ0-9]"), "_")
            val df = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault())
            "${safeAddress}_${safeEntrance}_${df.format(Date())}.csv"
        }
    }
}
