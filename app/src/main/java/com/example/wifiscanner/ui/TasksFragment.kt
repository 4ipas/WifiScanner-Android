package com.example.wifiscanner.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.wifiscanner.R
import com.example.wifiscanner.WifiScanService
import com.example.wifiscanner.adapters.TaskNodeAdapter
import com.example.wifiscanner.models.NodeDTO
import com.example.wifiscanner.models.ScanTaskDTO
import com.example.wifiscanner.repository.WifiRepository
import com.example.wifiscanner.utils.CsvLogger
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.gson.Gson
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Stack
import java.util.UUID

import android.widget.ImageButton
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.example.wifiscanner.utils.PermissionHelper

class TasksFragment : Fragment() {

    private lateinit var btnBack: ImageButton
    private lateinit var btnLoadTasks: Button
    private lateinit var btnSaveTasks: Button
    private lateinit var tvBreadcrumbs: TextView
    private lateinit var rvTasks: RecyclerView
    private lateinit var btnAddLocation: ImageButton
    private lateinit var fabNextLevel: ExtendedFloatingActionButton
    
    private lateinit var adapter: TaskNodeAdapter
    
    private var rootNode: NodeDTO?
        get() = WifiRepository.rootNode
        set(value) { WifiRepository.rootNode = value }

    private val navStack: Stack<NodeDTO>
        get() = WifiRepository.navStack
    
    private var activeScanNode: NodeDTO?
        get() = WifiRepository.activeScanNode
        set(value) { WifiRepository.activeScanNode = value }

    private var pendingScanNode: NodeDTO?
        get() = WifiRepository.pendingScanNode
        set(value) { WifiRepository.pendingScanNode = value }
    
    private val getJsonLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { loadJsonFromUri(it) }
    }

    private val saveJsonLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { saveJsonToUri(it) }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (!allGranted) {
            showPermissionDeniedDialog()
        } else {
            pendingScanNode?.let { executeStartScanForNode(it) }
            pendingScanNode = null
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_tasks, container, false)
        
        btnBack = view.findViewById(R.id.btnBack)
        btnLoadTasks = view.findViewById(R.id.btnLoadTasks)
        btnSaveTasks = view.findViewById(R.id.btnSaveTasks)
        tvBreadcrumbs = view.findViewById(R.id.tvBreadcrumbs)
        rvTasks = view.findViewById(R.id.rvTasks)
        btnAddLocation = view.findViewById(R.id.btnAddLocation)
        fabNextLevel = view.findViewById(R.id.fabNextLevel)
        
        rvTasks.layoutManager = LinearLayoutManager(requireContext())
        adapter = TaskNodeAdapter(
            onNodeClick = { node -> handleNodeClick(node) },
            onActionStart = { node -> startScanForNode(node) },
            onActionCancel = { node -> cancelScanForNode(node) },
            onActionMenu = { node, anchor -> showNodeMenu(node, anchor) }
        )
        rvTasks.adapter = adapter
        
        btnLoadTasks.setOnClickListener {
            getJsonLauncher.launch("application/json")
        }

        btnSaveTasks.setOnClickListener {
            val dateFormat = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault())
            val filename = "wifi_tasks_result_${dateFormat.format(Date())}.json"
            saveJsonLauncher.launch(filename)
        }
        
        btnBack.setOnClickListener {
            goBack()
        }
        
        tvBreadcrumbs.setOnClickListener {
            goBack()
        }
        
        btnAddLocation.setOnClickListener {
            showAddLocationDialog()
        }

        fabNextLevel.setOnClickListener {
            goToNextLevel()
        }

        observeScanning()
        updateUI() // Restore UI state from repository on fragment recreation
        
        return view
    }
    
    private fun observeScanning() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(WifiRepository.totalSnapshots, WifiRepository.totalRecords) { snapshots, records ->
                    Pair(snapshots, records)
                }.collect { (count, records) ->
                    activeScanNode?.let { node ->
                        val task = node.tasks.firstOrNull()
                        if (task != null) {
                            if (count >= task.requiredScans) {
                                if (!task.status.startsWith("COMPLETED")) {
                                    task.status = "COMPLETED_$records"
                                    stopWifiScanService()
                                    activeScanNode = null
                                    Toast.makeText(requireContext(), "Локация завершена!", Toast.LENGTH_SHORT).show()
                                    adapter.notifyDataSetChanged()
                                    checkLevelCompletion()
                                }
                            } else {
                                task.status = "IN_PROGRESS_${count}_${records}"
                                val idx = adapter.currentList.indexOf(node)
                                if (idx >= 0) adapter.notifyItemChanged(idx)
                            }
                        }
                    }
                }
            }
        }
    }
    
    private fun loadJsonFromUri(uri: android.net.Uri) {
        try {
            val inputStream = requireContext().contentResolver.openInputStream(uri)
            val reader = InputStreamReader(inputStream!!)
            rootNode = Gson().fromJson(reader, NodeDTO::class.java)
            reader.close()
            
            navStack.clear()
            activeScanNode = null
            stopWifiScanService() // safety
            
            val dateFormat = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault())
            WifiRepository.currentTaskCsvFilename = "wifi_tasks_blueprint_${dateFormat.format(Date())}.csv"
            
            updateUI()
            Toast.makeText(requireContext(), "Задания загружены", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(requireContext(), "Ошибка загрузки JSON", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun saveJsonToUri(uri: android.net.Uri) {
        try {
            val outputStream = requireContext().contentResolver.openOutputStream(uri)
            val writer = java.io.OutputStreamWriter(outputStream!!)
            val gson = com.google.gson.GsonBuilder().setPrettyPrinting().create()
            val jsonString = gson.toJson(WifiRepository.rootNode)
            writer.write(jsonString)
            writer.close()
            Toast.makeText(requireContext(), "Файл успешно сохранен!", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(requireContext(), "Ошибка при сохранении", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun handleNodeClick(node: NodeDTO) {
        if (node.nodeType != "LOCATION") {
            navStack.push(node)
            updateUI()
        }
    }
    
    private fun goBack() {
        if (navStack.isNotEmpty()) {
            navStack.pop()
            updateUI()
        }
    }
    
    private fun updateUI() {
        if (rootNode == null) {
            tvBreadcrumbs.text = "Объекты"
            btnBack.visibility = View.GONE
            btnLoadTasks.visibility = View.VISIBLE
            btnSaveTasks.visibility = View.GONE
            adapter.submitList(emptyList())
            btnAddLocation.visibility = View.GONE
            return
        }
        
        if (navStack.isEmpty()) {
            tvBreadcrumbs.text = "Объекты (Корень)"
            btnBack.visibility = View.GONE
            btnLoadTasks.visibility = View.VISIBLE
            btnSaveTasks.visibility = View.VISIBLE
            adapter.submitList(listOf(rootNode!!))
            btnAddLocation.visibility = View.GONE
        } else {
            val current = navStack.peek()
            val path = navStack.joinToString(" > ") { it.name }
            tvBreadcrumbs.text = "Объекты > $path"
            btnBack.visibility = View.VISIBLE
            btnLoadTasks.visibility = View.GONE
            btnSaveTasks.visibility = View.GONE
            
            val newList = ArrayList(current.children)
            adapter.submitList(newList)
            
            if (current.nodeType == "FLOOR") {
                btnAddLocation.visibility = View.VISIBLE
            } else {
                btnAddLocation.visibility = View.GONE
            }
            
            checkLevelCompletion()
        }
    }
    
    private fun getFullLocationName(node: NodeDTO): String {
        val pathNames = navStack.map { it.name }.toMutableList()
        pathNames.add(node.name)
        return pathNames.joinToString("_")
    }
    
    private fun startScanForNode(node: NodeDTO) {
        if (activeScanNode != null) {
            Toast.makeText(requireContext(), "Сначала отмените текущий скан!", Toast.LENGTH_SHORT).show()
            return
        }
        
        if (!checkLocationEnabled()) {
            showLocationDisabledDialog()
            return
        }

        val permissions = PermissionHelper.getBackgroundPermissions()
        val notGranted = permissions.filter {
            ActivityCompat.checkSelfPermission(requireContext(), it) != PackageManager.PERMISSION_GRANTED
        }
        
        if (notGranted.isNotEmpty()) {
            pendingScanNode = node
            AlertDialog.Builder(requireContext())
                .setTitle("Работа в фоновом режиме")
                .setMessage("Для непрерывной записи маршрута приложению необходимо разрешение на работу в фоне.\n\nВ следующем диалоге выберите «Разрешать всегда» (Allow all the time).")
                .setPositiveButton("Понятно") { _, _ ->
                     permissionLauncher.launch(notGranted.toTypedArray())
                }
                .setNegativeButton("Отмена") { _, _ ->
                     pendingScanNode = null
                }
                .show()
            return
        }
        
        executeStartScanForNode(node)
    }

    private fun executeStartScanForNode(node: NodeDTO) {
        val fullName = getFullLocationName(node)
        val startTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        
        // Reset everything AND trigger flows while activeScanNode is still null
        // This prevents 'combine' flow from emitting a dirty state (e.g. 5, 0) to the new active node
        WifiRepository.startNewSession(fullName, startTime)
        
        val task = node.tasks.firstOrNull()
        if (task != null) {
            task.status = "IN_PROGRESS_0"
            adapter.notifyDataSetChanged()
        }
        activeScanNode = node
        
        // Start physical service
        val address = WifiRepository.navStack.getOrNull(0)?.name ?: ""
        val entrance = WifiRepository.navStack.getOrNull(1)?.name ?: ""
        val floor = WifiRepository.navStack.getOrNull(2)?.name ?: ""
        
        val intent = Intent(requireContext(), WifiScanService::class.java)
        intent.putExtra(WifiScanService.EXTRA_LOCATION_NAME, node.name)
        intent.putExtra(WifiScanService.EXTRA_IS_TASK, true)
        intent.putExtra(WifiScanService.EXTRA_NODE_ID, node.id)
        intent.putExtra(WifiScanService.EXTRA_ADDRESS, address)
        intent.putExtra(WifiScanService.EXTRA_ENTRANCE, entrance)
        intent.putExtra(WifiScanService.EXTRA_FLOOR, floor)
        intent.putExtra(WifiScanService.EXTRA_REQUIRED_SCANS, task?.requiredScans ?: 5)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            requireContext().startForegroundService(intent)
        } else {
            requireContext().startService(intent)
        }
    }
    
    private fun stopWifiScanService() {
        if (WifiRepository.isScanning.value) {
            val endTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            WifiRepository.stopSession(endTime)
            val intent = Intent(requireContext(), WifiScanService::class.java)
            requireContext().stopService(intent)
        }
    }
    
    private fun cancelScanForNode(node: NodeDTO) {
        if (activeScanNode?.id == node.id) {
            stopWifiScanService()
            activeScanNode = null
        }
        
        val fullName = getFullLocationName(node)
        CsvLogger.deleteForLocation(fullName)
        CsvLogger.removeLocationFromTasksCsv(WifiRepository.currentTaskCsvFilename, node.id)
        
        val task = node.tasks.firstOrNull()
        if (task != null) {
            task.status = "PENDING"
            adapter.notifyDataSetChanged()
        }
        Toast.makeText(requireContext(), "Запись отменена, данные удалены", Toast.LENGTH_SHORT).show()
    }
    
    private fun showNodeMenu(node: NodeDTO, anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menu.add(0, 1, 0, "Отсутствует (Пропустить)")
        popup.menu.add(0, 2, 0, "Очистить готовое (Сброс)")
        
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> { // Отсутствует
                    val task = node.tasks.firstOrNull()
                    if (task != null) {
                        task.status = "SKIPPED"
                        adapter.notifyItemChanged(adapter.currentList.indexOf(node))
                        checkLevelCompletion()
                    }
                    true
                }
                2 -> { // Очистить
                    // Same as cancel, but regardless of active state
                    if (activeScanNode?.id == node.id) {
                        stopWifiScanService()
                        activeScanNode = null
                    }
                    val fullName = getFullLocationName(node)
                    CsvLogger.deleteForLocation(fullName)
                    CsvLogger.removeLocationFromTasksCsv(WifiRepository.currentTaskCsvFilename, node.id)
                    
                    val task = node.tasks.firstOrNull()
                    if (task != null) {
                        task.status = "PENDING"
                        adapter.notifyDataSetChanged()
                        checkLevelCompletion()
                    }
                    Toast.makeText(requireContext(), "Данные локации стерты", Toast.LENGTH_SHORT).show()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }
    
    private fun showAddLocationDialog() {
        val input = EditText(requireContext())
        input.hint = "Название новой локации"
        
        AlertDialog.Builder(requireContext())
            .setTitle("Добавление локации")
            .setView(input)
            .setPositiveButton("Добавить") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty() && navStack.isNotEmpty()) {
                    val currentParent = navStack.peek()
                    val newTask = ScanTaskDTO(id = UUID.randomUUID().toString(), requiredScans = 5, status = "PENDING")
                    val newNode = NodeDTO(
                        id = UUID.randomUUID().toString(),
                        parentId = currentParent.id,
                        nodeType = "LOCATION",
                        name = name,
                        tasks = listOf(newTask)
                    )
                    
                    val mutableChildren = currentParent.children.toMutableList()
                    mutableChildren.add(newNode)
                    
                    val field = currentParent.javaClass.getDeclaredField("children")
                    field.isAccessible = true
                    field.set(currentParent, mutableChildren)
                    
                    updateUI()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
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

    private fun checkLevelCompletion() {
        if (navStack.isEmpty()) {
            fabNextLevel.visibility = View.GONE
            return
        }
        val current = navStack.peek()
        val isLastLevel = current.children.isNotEmpty() && current.children.all { it.nodeType == "LOCATION" }
        if (isLastLevel) {
            val allCompleted = current.children.all { child -> 
                val status = child.tasks.firstOrNull()?.status ?: "PENDING"
                status.startsWith("COMPLETED") || status == "SKIPPED"
            }
            fabNextLevel.visibility = if (allCompleted) View.VISIBLE else View.GONE
        } else {
            fabNextLevel.visibility = View.GONE
        }
    }

    private fun goToNextLevel() {
        if (navStack.size >= 2) {
            val current = navStack.pop()
            val parent = navStack.peek()
            val currentIndex = parent.children.indexOfFirst { it.id == current.id }
            
            if (currentIndex >= 0 && currentIndex + 1 < parent.children.size) {
                val nextNode = parent.children[currentIndex + 1]
                navStack.push(nextNode)
                updateUI()
            } else {
                updateUI()
                Toast.makeText(requireContext(), "Это последний элемент в списке", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
