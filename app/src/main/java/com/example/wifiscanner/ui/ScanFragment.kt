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

    // v3.0.0: Sensor Dashboard
    private lateinit var sensorDashboard: View
    private lateinit var tvSensorSteps: TextView
    private lateinit var tvSensorImu: TextView
    private lateinit var tvSensorGps: TextView
    
    private val historyAdapter = ScanSessionAdapter()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (!allGranted) {
            showPermissionDeniedDialog()
        } else {
            // Permissions granted, we can start the service now
            executeStartService()
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

        // v3.0.0: Sensor Dashboard
        sensorDashboard = view.findViewById(R.id.sensorDashboard)
        tvSensorSteps = view.findViewById(R.id.tvSensorSteps)
        tvSensorImu = view.findViewById(R.id.tvSensorImu)
        tvSensorGps = view.findViewById(R.id.tvSensorGps)
        
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
                            sensorDashboard.visibility = View.VISIBLE
                        } else {
                            btnScan.setText(R.string.start_scan)
                            tvStatus.text = "Остановлено"
                            sensorDashboard.visibility = View.GONE
                        }
                    }
                }
                launch {
                    viewModel.totalSnapshots.collect { count ->
                        tvSnapshots.text = getString(R.string.snapshots_count, count)
                    }
                }
                launch {
                    viewModel.totalRecords.collect { count ->
                        tvCount.text = getString(R.string.records_count, count)
                    }
                }
                launch {
                    viewModel.sessionHistory.collect { sessions ->
                        historyAdapter.submitList(sessions)
                    }
                }
                // v3.0.0: Наблюдение за данными датчиков
                launch {
                    viewModel.sensorSnapshot.collect { snapshot ->
                        if (snapshot != null) {
                            // Шаги
                            val stepsText = if (snapshot.stepsTotal != null) {
                                "${snapshot.stepsTotal} (+${snapshot.stepsDelta ?: 0})"
                            } else {
                                "нет датчика"
                            }
                            tvSensorSteps.text = stepsText

                            // IMU: Азимут + Ориентация
                            val azText = snapshot.azimuth?.let {
                                String.format(Locale.US, "%.0f°", it)
                            } ?: "—"
                            tvSensorImu.text = "$azText\n${snapshot.deviceOrientation}"
                        }
                    }
                }
                launch {
                    viewModel.lastLocation.collect { location ->
                        tvSensorGps.text = if (location != null) {
                            String.format(Locale.US, "%.7f\n%.7f", location.first, location.second)
                        } else {
                            "Поиск..."
                        }
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

        val permissions = PermissionHelper.getBackgroundPermissions()
        val notGranted = permissions.filter {
            ActivityCompat.checkSelfPermission(requireContext(), it) != PackageManager.PERMISSION_GRANTED
        }
        
        if (notGranted.isNotEmpty()) {
            // Explain why we need background location before requesting it
            AlertDialog.Builder(requireContext())
                .setTitle("Работа в фоновом режиме")
                .setMessage("Для непрерывной записи маршрута приложению необходимо разрешение на работу в фоне.\n\nВ следующем диалоге выберите «Разрешать всегда» (Allow all the time).")
                .setPositiveButton("Понятно") { _, _ ->
                     permissionLauncher.launch(notGranted.toTypedArray())
                }
                .setNegativeButton("Отмена", null)
                .show()
            return
        }
        
        executeStartService()
    }

    private fun executeStartService() {
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
            .setTitle("Отсутствует разрешение")
            .setMessage("Без разрешения «Разрешать всегда» (Background Location) фоновое сканирование сетей не запустится или прервется при выключении экрана.\n\nПожалуйста, перейдите в настройки приложения -> Разрешения -> Местоположение -> и выберите «Разрешать всегда».")
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
