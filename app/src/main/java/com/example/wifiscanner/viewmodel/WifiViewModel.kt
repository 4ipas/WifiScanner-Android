package com.example.wifiscanner.viewmodel

import androidx.lifecycle.ViewModel
import com.example.wifiscanner.repository.WifiRepository

class WifiViewModel : ViewModel() {
    val scanResults = WifiRepository.scanResults
    val totalRecords = WifiRepository.totalRecords
    val totalSnapshots = WifiRepository.totalSnapshots
    val sessionHistory = WifiRepository.sessionHistory
    val isScanning = WifiRepository.isScanning
}
