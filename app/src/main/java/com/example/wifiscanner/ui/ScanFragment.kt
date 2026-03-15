package com.example.wifiscanner.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.wifiscanner.R
import com.example.wifiscanner.WifiScanService
import com.example.wifiscanner.adapters.ScanSessionAdapter
import com.example.wifiscanner.repository.WifiRepository
import com.example.wifiscanner.utils.PermissionHelper
import com.example.wifiscanner.viewmodel.WifiViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScanFragment : Fragment() {

    private val viewModel: WifiViewModel by activityViewModels()

    private lateinit var etLocation: EditText
    private lateinit var btnScan: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvSnapshots: TextView
    private lateinit var tvCount: TextView
    private lateinit var rvHistory: RecyclerView
    
    private val historyAdapter = ScanSessionAdapter()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (!allGranted) {
            showPermissionDeniedDialog()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_scan, container, false)
        initViews(view)
        observeViewModel()

        btnScan.setOnClickListener {
            if (viewModel.isScanning.value) {
                stopWifiScanService()
            } else {
                startWifiScanService()
            }
        }
        return view
    }

    private fun initViews(view: View) {
        etLocation = view.findViewById(R.id.etLocation)
        btnScan = view.findViewById(R.id.btnScan)
        tvStatus = view.findViewById(R.id.tvStatus)
        tvSnapshots = view.findViewById(R.id.tvSnapshots)
        tvCount = view.findViewById(R.id.tvCount)
        rvHistory = view.findViewById(R.id.rvHistory)
        
        rvHistory.layoutManager = LinearLayoutManager(requireContext())
        rvHistory.adapter = historyAdapter
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.isScanning.collect { isScanning ->
                        if (isScanning) {
                            btnScan.setText(R.string.stop_scan)
                            tvStatus.text = "Активно"
                        } else {
                            btnScan.setText(R.string.start_scan)
                            tvStatus.text = "Остановлено"
                        }
                    }
                }
                launch {
                    viewModel.totalSnapshots.collect { count ->
                        tvSnapshots.text = "Слепков: $count"
                    }
                }
                launch {
                    viewModel.totalRecords.collect { count ->
                        tvCount.text = "Записей: $count"
                    }
                }
                launch {
                    viewModel.sessionHistory.collect { sessions ->
                        historyAdapter.submitList(sessions)
                    }
                }
            }
        }
    }

    private fun startWifiScanService() {
        if (!checkLocationEnabled()) {
            showLocationDisabledDialog()
            return
        }

        val permissions = PermissionHelper.getRequiredPermissions()
        val notGranted = permissions.filter {
            ActivityCompat.checkSelfPermission(requireContext(), it) != PackageManager.PERMISSION_GRANTED
        }
        if (notGranted.isNotEmpty()) {
            showPermissionDeniedDialog()
            return
        }
        
        val intent = Intent(requireContext(), WifiScanService::class.java)
        val locName = etLocation.text.toString().trim()
        val startTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        
        WifiRepository.startNewSession(locName, startTime)
        
        intent.putExtra(WifiScanService.EXTRA_LOCATION_NAME, locName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            requireContext().startForegroundService(intent)
        } else {
            requireContext().startService(intent)
        }
    }

    private fun stopWifiScanService() {
        val endTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        WifiRepository.stopSession(endTime)
        
        val intent = Intent(requireContext(), WifiScanService::class.java)
        requireContext().stopService(intent)
    }

    private fun checkLocationEnabled(): Boolean {
        val locationManager = requireContext().getSystemService(AppCompatActivity.LOCATION_SERVICE) as LocationManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            locationManager.isLocationEnabled
        } else {
            @Suppress("DEPRECATION")
            (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
             locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
        }
    }

    private fun showPermissionDeniedDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("Необходимы разрешения")
            .setMessage("Для работы сканера Wi-Fi требуются разрешения на местоположение, уведомления и доступ к файлам.\n\nПожалуйста, перейдите в настройки приложения и предоставьте их.")
            .setPositiveButton("В Настройки") { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", requireContext().packageName, null)
                }
                startActivity(intent)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showLocationDisabledDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("Геолокация выключена")
            .setMessage("Для сканирования Wi-Fi сетей в системе Android необходимо, чтобы на устройстве была включена геолокация (GPS).\n\nПожалуйста, включите её.")
            .setPositiveButton("Включить") { _, _ ->
                val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                startActivity(intent)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }
}
