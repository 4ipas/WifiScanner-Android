package com.example.wifiscanner.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.wifiscanner.R
import com.example.wifiscanner.WifiScanService
import com.example.wifiscanner.models.ScanSession
import com.example.wifiscanner.repository.WifiRepository
import com.example.wifiscanner.utils.ActionSheetItem
import com.example.wifiscanner.utils.CsvLogger
import com.example.wifiscanner.utils.PermissionHelper
import com.example.wifiscanner.utils.UIHelper
import com.example.wifiscanner.viewmodel.WifiViewModel
import kotlinx.coroutines.launch
import java.io.File
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

    private lateinit var sensorDashboard: View
    private lateinit var tvSensorSteps: TextView
    private lateinit var tvSensorImu: TextView
    private lateinit var tvSensorGps: TextView

    // Секция завершённых ручных сканов
    private lateinit var llCompletedSection: LinearLayout
    private lateinit var tvCompletedHeader: TextView
    private lateinit var llCompletedItems: LinearLayout
    private var completedExpanded = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (!allGranted) {
            showPermissionDeniedDialog()
        } else {
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

        sensorDashboard = view.findViewById(R.id.sensorDashboard)
        tvSensorSteps = view.findViewById(R.id.tvSensorSteps)
        tvSensorImu = view.findViewById(R.id.tvSensorImu)
        tvSensorGps = view.findViewById(R.id.tvSensorGps)

        llCompletedSection = view.findViewById(R.id.llCompletedSection)
        tvCompletedHeader = view.findViewById(R.id.tvCompletedHeader)
        llCompletedItems = view.findViewById(R.id.llCompletedItems)

        tvCompletedHeader.setOnClickListener {
            completedExpanded = !completedExpanded
            llCompletedItems.visibility = if (completedExpanded) View.VISIBLE else View.GONE
        }
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
                // Обновлять секцию завершённых при изменении истории
                launch {
                    viewModel.sessionHistory.collect { sessions ->
                        updateCompletedSection(sessions.filter { it.isManualScan && it.endTime != null })
                    }
                }
                launch {
                    viewModel.sensorSnapshot.collect { snapshot ->
                        if (snapshot != null) {
                            val stepsText = if (snapshot.stepsTotal != null) {
                                "${snapshot.stepsTotal} (+${snapshot.stepsDelta ?: 0})"
                            } else {
                                "нет датчика"
                            }
                            tvSensorSteps.text = stepsText
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

    // ─── Секция завершённых ─────────────────────────────────────────

    private fun updateCompletedSection(completedSessions: List<ScanSession>) {
        if (completedSessions.isEmpty()) {
            llCompletedSection.visibility = View.GONE
            return
        }

        llCompletedSection.visibility = View.VISIBLE
        tvCompletedHeader.text = "📋 Завершено: ${completedSessions.size}"

        llCompletedItems.removeAllViews()
        for (session in completedSessions) {
            val itemView = LayoutInflater.from(requireContext())
                .inflate(R.layout.item_completed_task, llCompletedItems, false)

            val tvName = itemView.findViewById<TextView>(R.id.tvCompletedName)
            val tvDate = itemView.findViewById<TextView>(R.id.tvCompletedDate)

            val name = session.locationName.ifBlank { "Без названия" }
            tvName.text = "$name  (${session.snapshotCount}⊙ ${session.recordCount}✎)"
            tvDate.text = "${session.startTime} - ${session.endTime ?: ""}"

            itemView.setOnLongClickListener {
                showCompletedSessionMenu(session)
                true
            }

            llCompletedItems.addView(itemView)
        }

        llCompletedItems.visibility = if (completedExpanded) View.VISIBLE else View.GONE
    }

    private fun showCompletedSessionMenu(session: ScanSession) {
        val items = mutableListOf<ActionSheetItem>()

        items.add(ActionSheetItem("Отправить результат сканирования") {
            shareSession(session)
        })

        items.add(ActionSheetItem("Удалить", isDanger = true) {
            deleteSession(session)
        })

        UIHelper.showActionSheet(requireContext(), items)
    }

    private fun shareSession(session: ScanSession) {
        val csvFile = getSessionCsvFile(session)
        if (csvFile == null || !csvFile.exists()) {
            Toast.makeText(requireContext(), "CSV-файл не найден", Toast.LENGTH_SHORT).show()
            return
        }

        val uri = FileProvider.getUriForFile(
            requireContext(),
            "${requireContext().packageName}.fileprovider",
            csvFile
        )

        val shareIntent = Intent().apply {
            action = Intent.ACTION_SEND
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Wi-Fi скан: ${session.locationName}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, "Отправить результат"))
    }

    private fun deleteSession(session: ScanSession) {
        val csvFile = getSessionCsvFile(session)
        if (csvFile != null && csvFile.exists()) {
            csvFile.delete()
        }
        // Удалить из истории
        val updated = WifiRepository.sessionHistory.value.toMutableList()
        updated.removeAll { it.id == session.id }
        // Обновить через reflection (StateFlow private)
        val field = WifiRepository.javaClass.getDeclaredField("_sessionHistory")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val flow = field.get(WifiRepository) as kotlinx.coroutines.flow.MutableStateFlow<List<ScanSession>>
        flow.value = updated

        Toast.makeText(requireContext(), "Результат удалён", Toast.LENGTH_SHORT).show()
    }

    private fun getSessionCsvFile(session: ScanSession): File? {
        val fileName = session.csvFileName ?: return null
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "MyWifiScans")
        return File(dir, fileName)
    }

    // ─── Сканирование ───────────────────────────────────────────────

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

        // Пометить как ручной скан
        WifiRepository.currentSessionIsManual = true
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
