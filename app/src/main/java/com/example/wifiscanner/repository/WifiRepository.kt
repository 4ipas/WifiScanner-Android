package com.example.wifiscanner.repository

import com.example.wifiscanner.models.NodeDTO
import com.example.wifiscanner.models.ScanSession
import com.example.wifiscanner.models.WifiScanResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Stack

object WifiRepository {
    var rootNode: NodeDTO? = null
    val navStack = Stack<NodeDTO>()
    var currentTaskCsvFilename: String = "wifi_tasks_default.csv"
    var activeScanNode: NodeDTO? = null
    var pendingScanNode: NodeDTO? = null

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
                recordCount = _totalRecords.value
            )
            val updatedHistory = _sessionHistory.value.toMutableList()
            updatedHistory.add(0, session) // Add to top of list
            _sessionHistory.value = updatedHistory
            setScanning(false)
        }
    }

    fun setScanning(scanning: Boolean) {
        _isScanning.value = scanning
    }
}
