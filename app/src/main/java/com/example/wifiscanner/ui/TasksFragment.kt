package com.example.wifiscanner.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
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
import com.example.wifiscanner.models.deepCopyWithReset
import com.example.wifiscanner.repository.WifiRepository
import com.example.wifiscanner.utils.ActionSheetItem
import com.example.wifiscanner.utils.CsvLogger
import com.example.wifiscanner.utils.TaskDownloader
import com.example.wifiscanner.utils.TaskPersistence
import com.example.wifiscanner.utils.UIHelper
import com.example.wifiscanner.cloud.DiskConfig
import com.example.wifiscanner.cloud.YandexDiskClient
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
import androidx.preference.PreferenceManager
import com.example.wifiscanner.utils.PermissionHelper

class TasksFragment : Fragment() {

    private lateinit var btnBack: ImageButton
    private lateinit var btnLoadTasks: Button
    private lateinit var btnSaveTasks: Button
    private lateinit var tvBreadcrumbs: TextView
    private lateinit var rvTasks: RecyclerView
    private lateinit var btnAddLocation: ImageButton
    private lateinit var fabNextLevel: ExtendedFloatingActionButton

    // Completed section (Variant B — визуальная группировка)
    private lateinit var llCompletedSection: LinearLayout
    private lateinit var tvCompletedHeader: TextView
    private lateinit var llCompletedItems: LinearLayout
    private var completedExpanded = false
    
    private lateinit var adapter: TaskNodeAdapter

    private val rootNodes: MutableList<NodeDTO>
        get() = WifiRepository.rootNodes

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

        llCompletedSection = view.findViewById(R.id.llCompletedSection)
        tvCompletedHeader = view.findViewById(R.id.tvCompletedHeader)
        llCompletedItems = view.findViewById(R.id.llCompletedItems)
        
        rvTasks.layoutManager = LinearLayoutManager(requireContext())
        adapter = TaskNodeAdapter(
            onNodeClick = { node -> handleNodeClick(node) },
            onActionStart = { node -> startScanForNode(node) },
            onActionCancel = { node -> cancelScanForNode(node) },
            onActionMenu = { node, anchor -> showNodeMenu(node, anchor) }
        )
        rvTasks.adapter = adapter
        
        btnLoadTasks.setOnClickListener { showLoadTasksMenu() }

        btnSaveTasks.setOnClickListener {
            val dateFormat = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault())
            val filename = "wifi_tasks_result_${dateFormat.format(Date())}.json"
            saveJsonLauncher.launch(filename)
        }
        
        btnBack.setOnClickListener { goBack() }
        tvBreadcrumbs.setOnClickListener { goBack() }
        btnAddLocation.setOnClickListener { showAddLocationDialog() }
        fabNextLevel.setOnClickListener { goToNextLevel() }

        tvCompletedHeader.setOnClickListener {
            completedExpanded = !completedExpanded
            llCompletedItems.visibility = if (completedExpanded) View.VISIBLE else View.GONE
        }

        restoreState()
        observeScanning()
        updateUI()
        
        return view
    }

    // ─── Персистентность ───────────────────────────────────────────

    private fun restoreState() {
        if (rootNodes.isEmpty()) {
            val ctx = requireContext()
            val loaded = TaskPersistence.loadTasks(ctx)
            rootNodes.clear()
            rootNodes.addAll(loaded)
            val csvMap = TaskPersistence.loadCsvMap(ctx)
            WifiRepository.entranceCsvFilenames.clear()
            WifiRepository.entranceCsvFilenames.putAll(csvMap)
        }
    }

    private fun persistState() {
        val ctx = context ?: return
        TaskPersistence.saveTasks(ctx, rootNodes)
        TaskPersistence.saveCsvMap(ctx, WifiRepository.entranceCsvFilenames)
    }

    // ─── Загрузка заданий (ActionSheet) ────────────────────────────

    private fun showLoadTasksMenu() {
        val items = listOf(
            ActionSheetItem("Из памяти телефона") {
                getJsonLauncher.launch("application/json")
            },
            ActionSheetItem("По ссылке") {
                showUrlLoadDialog()
            },
            ActionSheetItem("Обновить с Яндекс Диска") {
                loadTasksFromYandexDisk()
            }
        )
        UIHelper.showActionSheet(requireContext(), items)
    }

    private fun loadTasksFromYandexDisk() {
        val token = DiskConfig.getToken(requireContext())
        if (token.isBlank()) {
            Toast.makeText(requireContext(), "Токен не задан. Настройте Яндекс Диск.", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(requireContext(), "Загрузка списка заданий...", Toast.LENGTH_SHORT).show()
        viewLifecycleOwner.lifecycleScope.launch {
            val result = YandexDiskClient.listFiles(token, DiskConfig.TASKS_PATH)
            if (result.isSuccess) {
                val files = result.getOrNull() ?: emptyList()
                val jsonFiles = files.filter { it.name.endsWith(".json") }
                if (jsonFiles.isEmpty()) {
                    Toast.makeText(requireContext(), "Папка пуста или нет заданий (.json)", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                
                val names = jsonFiles.map { it.name }.toTypedArray()
                AlertDialog.Builder(requireContext())
                    .setTitle("Выберите задание")
                    .setItems(names) { _, which ->
                        val selected = jsonFiles[which]
                        downloadTaskFromYandexDisk(token, selected.path)
                    }
                    .setNegativeButton("Отмена", null)
                    .show()
            } else {
                Toast.makeText(requireContext(), "Ошибка: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun downloadTaskFromYandexDisk(token: String, path: String) {
        Toast.makeText(requireContext(), "Загрузка файла...", Toast.LENGTH_SHORT).show()
        viewLifecycleOwner.lifecycleScope.launch {
            val result = YandexDiskClient.downloadFile(token, path)
            if (result.isSuccess) {
                val json = result.getOrNull() ?: ""
                addTaskFromJson(json)
                Toast.makeText(requireContext(), "Задание подгружено!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), "Ошибка: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showUrlLoadDialog() {
        val input = EditText(requireContext())
        input.hint = "Вставьте ссылку на JSON"
        input.minLines = 3
        input.isSingleLine = false

        AlertDialog.Builder(requireContext())
            .setTitle("Загрузка по ссылке")
            .setMessage("Поддерживаются прямые ссылки и Яндекс.Диск")
            .setView(input)
            .setPositiveButton("Загрузить") { _, _ ->
                val url = input.text.toString().trim()
                if (url.isNotEmpty()) {
                    downloadAndLoadJson(url)
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun downloadAndLoadJson(url: String) {
        Toast.makeText(requireContext(), "Загрузка...", Toast.LENGTH_SHORT).show()
        viewLifecycleOwner.lifecycleScope.launch {
            val result = TaskDownloader.downloadJson(url)
            result.onSuccess { jsonString ->
                addTaskFromJson(jsonString)
                Toast.makeText(requireContext(), "Задание загружено", Toast.LENGTH_SHORT).show()
            }
            result.onFailure { error ->
                Toast.makeText(requireContext(), "Ошибка загрузки: ${error.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    // ─── Наблюдение за сканированием ───────────────────────────────

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
                                    WifiRepository.activeScanCsvFilename = null
                                    Toast.makeText(requireContext(), "Локация завершена!", Toast.LENGTH_SHORT).show()
                                    adapter.notifyDataSetChanged()
                                    checkLevelCompletion()
                                    persistState()
                                    playScanCompleteSound()
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

    private fun playScanCompleteSound() {
        try {
            val audioManager = requireContext().getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
            when (audioManager.ringerMode) {
                android.media.AudioManager.RINGER_MODE_NORMAL -> {
                    val uri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
                    android.media.RingtoneManager.getRingtone(requireContext(), uri)?.play()
                }
                android.media.AudioManager.RINGER_MODE_VIBRATE -> {
                    val vibrator = requireContext().getSystemService(android.content.Context.VIBRATOR_SERVICE) as android.os.Vibrator
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        vibrator.vibrate(android.os.VibrationEffect.createOneShot(400, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                    } else {
                        @Suppress("DEPRECATION")
                        vibrator.vibrate(400)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ─── Загрузка / Сохранение JSON ────────────────────────────────

    private fun loadJsonFromUri(uri: android.net.Uri) {
        try {
            val inputStream = requireContext().contentResolver.openInputStream(uri)
            val reader = InputStreamReader(inputStream!!)
            val jsonString = reader.readText()
            reader.close()
            addTaskFromJson(jsonString)
            Toast.makeText(requireContext(), "Задание загружено", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(requireContext(), "Ошибка загрузки JSON", Toast.LENGTH_SHORT).show()
        }
    }

    /** Добавляет задание в список. Не заменяет существующие. */
    private fun addTaskFromJson(jsonString: String) {
        val newRoot = Gson().fromJson(jsonString, NodeDTO::class.java)
        rootNodes.add(newRoot)
        navStack.clear()
        persistState()
        updateUI()
    }
    
    private fun saveJsonToUri(uri: android.net.Uri) {
        try {
            val outputStream = requireContext().contentResolver.openOutputStream(uri)
            val writer = java.io.OutputStreamWriter(outputStream!!)
            val gson = com.google.gson.GsonBuilder().setPrettyPrinting().create()
            val jsonString = gson.toJson(rootNodes)
            writer.write(jsonString)
            writer.close()
            Toast.makeText(requireContext(), "Файл успешно сохранен!", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(requireContext(), "Ошибка при сохранении", Toast.LENGTH_SHORT).show()
        }
    }

    // ─── Навигация ─────────────────────────────────────────────────
    
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
        if (navStack.isEmpty()) {
            // Корневой уровень: показать все задания
            tvBreadcrumbs.text = "Объекты (Корень)"
            btnBack.visibility = View.GONE
            btnLoadTasks.visibility = View.VISIBLE
            btnSaveTasks.visibility = if (rootNodes.isNotEmpty()) View.VISIBLE else View.GONE
            btnAddLocation.visibility = View.GONE

            // Разделяем на активные и завершённые
            val active = rootNodes.filter { !isFullyCompleted(it) }
            val completed = rootNodes.filter { isFullyCompleted(it) }

            adapter.submitList(active.toList())
            updateCompletedSection(completed)
        } else {
            val current = navStack.peek()
            val path = navStack.joinToString(" > ") { it.name }
            tvBreadcrumbs.text = "Объекты > $path"
            btnBack.visibility = View.VISIBLE
            btnLoadTasks.visibility = View.GONE
            btnSaveTasks.visibility = View.GONE
            
            val newList = ArrayList(current.children)
            adapter.submitList(newList)
            
            btnAddLocation.visibility = if (current.nodeType == "FLOOR") View.VISIBLE else View.GONE
            llCompletedSection.visibility = View.GONE
            
            checkLevelCompletion()
        }
    }

    // ─── Секция завершённых (Variant B — визуальная группировка) ───

    private fun updateCompletedSection(completed: List<NodeDTO>) {
        if (completed.isEmpty()) {
            llCompletedSection.visibility = View.GONE
            return
        }
        
        llCompletedSection.visibility = View.VISIBLE
        tvCompletedHeader.text = "📋 Завершено: ${completed.size}"
        
        llCompletedItems.removeAllViews()
        for (node in completed) {
            val itemView = LayoutInflater.from(requireContext())
                .inflate(R.layout.item_completed_task, llCompletedItems, false)
            
            val tvName = itemView.findViewById<TextView>(R.id.tvCompletedName)
            val tvDate = itemView.findViewById<TextView>(R.id.tvCompletedDate)

            val stats = calcTreeStats(node)
            tvName.text = "${node.name}  (${stats.completed}✓ ${stats.skipped}⊘)"
            tvDate.text = "" // Нет даты завершения — задание просто помечено как полное

            // Клик по завершённому — навигация внутрь
            itemView.setOnClickListener {
                navStack.push(node)
                updateUI()
            }

            // Долгое нажатие — меню (копировать)
            itemView.setOnLongClickListener {
                showAddressMenu(node)
                true
            }

            llCompletedItems.addView(itemView)
        }
        
        llCompletedItems.visibility = if (completedExpanded) View.VISIBLE else View.GONE
    }

    // ─── Сканирование ──────────────────────────────────────────────
    
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
        
        WifiRepository.startNewSession(fullName, startTime)
        
        val task = node.tasks.firstOrNull()
        if (task != null) {
            task.status = "IN_PROGRESS_0"
            adapter.notifyDataSetChanged()
        }
        activeScanNode = node

        // Per-entrance CSV
        val addressName = navStack.getOrNull(0)?.name ?: "unknown"
        val entranceNode = navStack.getOrNull(1)
        val entranceName = entranceNode?.name ?: "unknown"
        val entranceId = entranceNode?.id ?: "unknown"
        val csvFilename = WifiRepository.getCsvFilenameForEntrance(entranceId, addressName, entranceName)
        WifiRepository.activeScanCsvFilename = csvFilename
        
        val address = navStack.getOrNull(0)?.name ?: ""
        val entrance = navStack.getOrNull(1)?.name ?: ""
        val floor = navStack.getOrNull(2)?.name ?: ""
        
        val intent = Intent(requireContext(), WifiScanService::class.java)
        intent.putExtra(WifiScanService.EXTRA_LOCATION_NAME, node.name)
        intent.putExtra(WifiScanService.EXTRA_IS_TASK, true)
        intent.putExtra(WifiScanService.EXTRA_NODE_ID, node.id)
        intent.putExtra(WifiScanService.EXTRA_ADDRESS, address)
        intent.putExtra(WifiScanService.EXTRA_ENTRANCE, entrance)
        intent.putExtra(WifiScanService.EXTRA_FLOOR, floor)
        intent.putExtra(WifiScanService.EXTRA_REQUIRED_SCANS, task?.requiredScans ?: 5)
        intent.putExtra(WifiScanService.EXTRA_CSV_FILENAME, csvFilename)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            requireContext().startForegroundService(intent)
        } else {
            requireContext().startService(intent)
        }
        
        persistState()
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

        val csvFilename = WifiRepository.activeScanCsvFilename ?: deriveCsvFilenameFromNavStack()
        CsvLogger.removeLocationFromTasksCsv(csvFilename, node.id)
        WifiRepository.activeScanCsvFilename = null
        
        val task = node.tasks.firstOrNull()
        if (task != null) {
            task.status = "PENDING"
            adapter.notifyDataSetChanged()
        }
        persistState()
        Toast.makeText(requireContext(), "Запись отменена, данные удалены", Toast.LENGTH_SHORT).show()
    }

    private fun deriveCsvFilenameFromNavStack(): String {
        val addressName = navStack.getOrNull(0)?.name ?: "unknown"
        val entranceNode = navStack.getOrNull(1)
        val entranceName = entranceNode?.name ?: "unknown"
        val entranceId = entranceNode?.id ?: "unknown"
        return WifiRepository.getCsvFilenameForEntrance(entranceId, addressName, entranceName)
    }

    // ─── Меню узлов ────────────────────────────────────────────────
    
    private fun showNodeMenu(node: NodeDTO, anchor: View) {
        when (node.nodeType) {
            "ADDRESS" -> showAddressMenu(node)
            "LOCATION" -> showLocationMenu(node)
        }
    }

    private fun showAddressMenu(node: NodeDTO) {
        val items = mutableListOf<ActionSheetItem>()

        // Отправить — только для завершённых (есть CSV-данные)
        if (isFullyCompleted(node)) {
            items.add(ActionSheetItem("Отправить задание") {
                shareTask(node)
            })
        }

        // Копировать — всегда доступно
        items.add(ActionSheetItem("Копировать задание") {
            copyTask(node)
        })

        // Удалить — только если НЕ завершено
        if (!isFullyCompleted(node)) {
            items.add(ActionSheetItem("Удалить задание", isDanger = true, action = {
                confirmDeleteTask(node)
            }))
        }

        UIHelper.showActionSheet(requireContext(), items)
    }

    private fun showLocationMenu(node: NodeDTO) {
        val items = listOf(
            ActionSheetItem("Отсутствует (Пропустить)") {
                val task = node.tasks.firstOrNull()
                if (task != null) {
                    task.status = "SKIPPED"
                    adapter.notifyItemChanged(adapter.currentList.indexOf(node))
                    checkLevelCompletion()
                    persistState()
                }
            },
            ActionSheetItem("Очистить готовое (Сброс)") {
                if (activeScanNode?.id == node.id) {
                    stopWifiScanService()
                    activeScanNode = null
                }
                val fullName = getFullLocationName(node)
                CsvLogger.deleteForLocation(fullName)
                
                val csvFilename = deriveCsvFilenameFromNavStack()
                CsvLogger.removeLocationFromTasksCsv(csvFilename, node.id)
                
                val task = node.tasks.firstOrNull()
                if (task != null) {
                    task.status = "PENDING"
                    adapter.notifyDataSetChanged()
                    checkLevelCompletion()
                    persistState()
                }
                Toast.makeText(requireContext(), "Данные локации стерты", Toast.LENGTH_SHORT).show()
            }
        )
        UIHelper.showActionSheet(requireContext(), items)
    }

    // ─── Удаление задания ──────────────────────────────────────────

    private fun confirmDeleteTask(node: NodeDTO) {
        AlertDialog.Builder(requireContext())
            .setTitle("Удалить задание?")
            .setMessage("Задание «${node.name}» и все записанные данные (CSV) будут удалены.")
            .setPositiveButton("Удалить") { _, _ ->
                deleteTask(node)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun deleteTask(node: NodeDTO) {
        // Остановить скан, если активен внутри этого задания
        if (activeScanNode != null && isDescendantOf(activeScanNode!!, node)) {
            stopWifiScanService()
            activeScanNode = null
            WifiRepository.activeScanCsvFilename = null
        }

        // Удалить CSV-файлы всех подъездов этого задания
        deleteEntranceCsvFiles(node)

        // Удалить из списка
        rootNodes.removeAll { it.id == node.id }
        navStack.clear()

        persistState()
        updateUI()
        Toast.makeText(requireContext(), "Задание удалено", Toast.LENGTH_SHORT).show()
    }

    /** Рекурсивно удаляет CSV-файлы для всех ENTRANCE-узлов задания */
    private fun deleteEntranceCsvFiles(node: NodeDTO) {
        if (node.nodeType == "ENTRANCE") {
            val csvFilename = WifiRepository.entranceCsvFilenames.remove(node.id)
            if (csvFilename != null) {
                CsvLogger.deleteTaskCsvFile(csvFilename)
            }
        }
        for (child in node.children) {
            deleteEntranceCsvFiles(child)
        }
    }

    /** Проверяет, является ли target потомком parent */
    private fun isDescendantOf(target: NodeDTO, parent: NodeDTO): Boolean {
        if (parent.id == target.id) return true
        return parent.children.any { isDescendantOf(target, it) }
    }

    // ─── Отправка задания ────────────────────────────────────────────

    private fun shareTask(node: NodeDTO) {
        val csvFiles = collectEntranceCsvFiles(node)

        if (csvFiles.isEmpty()) {
            Toast.makeText(requireContext(), "Нет CSV-файлов для отправки", Toast.LENGTH_SHORT).show()
            return
        }

        val uris = ArrayList<android.net.Uri>()
        for (file in csvFiles) {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.fileprovider",
                file
            )
            uris.add(uri)
        }

        val shareIntent = Intent().apply {
            action = Intent.ACTION_SEND_MULTIPLE
            type = "text/csv"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            putExtra(Intent.EXTRA_SUBJECT, "Wi-Fi сканы: ${node.name}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        startActivity(Intent.createChooser(shareIntent, "Отправить результаты"))
    }

    /** Собирает все CSV-файлы для подъездов данного задания */
    private fun collectEntranceCsvFiles(node: NodeDTO): List<java.io.File> {
        val dir = java.io.File(
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS),
            "MyWifiScans"
        )
        val files = mutableListOf<java.io.File>()
        collectCsvFilesRecursive(node, dir, files)
        return files
    }

    private fun collectCsvFilesRecursive(node: NodeDTO, dir: java.io.File, result: MutableList<java.io.File>) {
        if (node.nodeType == "ENTRANCE") {
            val csvFilename = WifiRepository.entranceCsvFilenames[node.id]
            if (csvFilename != null) {
                val file = java.io.File(dir, csvFilename)
                if (file.exists()) {
                    result.add(file)
                }
            }
        }
        for (child in node.children) {
            collectCsvFilesRecursive(child, dir, result)
        }
    }

    // ─── Копирование задания ───────────────────────────────────────

    private fun copyTask(node: NodeDTO) {
        val copy = node.deepCopyWithReset()
        // Имя НЕ меняется — уникальность гарантирована GUID (id)
        rootNodes.add(copy)
        persistState()
        updateUI()
        Toast.makeText(requireContext(), "Задание скопировано", Toast.LENGTH_SHORT).show()
    }
    
    // ─── Добавление локации ────────────────────────────────────────

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
                    
                    persistState()
                    updateUI()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ─── Утилиты ───────────────────────────────────────────────────

    private fun isFullyCompleted(node: NodeDTO): Boolean {
        if (node.nodeType == "LOCATION") {
            val status = node.tasks.firstOrNull()?.status ?: "PENDING"
            return status.startsWith("COMPLETED") || status == "SKIPPED"
        }
        return node.children.isNotEmpty() && node.children.all { isFullyCompleted(it) }
    }

    private data class TreeStats(val total: Int, val completed: Int, val skipped: Int, val pending: Int)

    private fun calcTreeStats(node: NodeDTO): TreeStats {
        if (node.nodeType == "LOCATION") {
            val status = node.tasks.firstOrNull()?.status ?: "PENDING"
            return when {
                status.startsWith("COMPLETED") -> TreeStats(1, 1, 0, 0)
                status == "SKIPPED" -> TreeStats(1, 0, 1, 0)
                else -> TreeStats(1, 0, 0, 1)
            }
        }
        var t = 0; var c = 0; var s = 0; var p = 0
        for (child in node.children) {
            val st = calcTreeStats(child)
            t += st.total; c += st.completed; s += st.skipped; p += st.pending
        }
        return TreeStats(t, c, s, p)
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
            
            if (allCompleted) {
                uploadEntranceResults()
            }
        } else {
            fabNextLevel.visibility = View.GONE
        }
    }

    private fun uploadEntranceResults() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        if (!prefs.getBoolean("pref_yadisk_auto_upload", true)) return
        
        val token = DiskConfig.getToken(requireContext())
        if (token.isBlank()) return

        val csvFilename = deriveCsvFilenameFromNavStack()
        val dir = java.io.File(
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS),
            "MyWifiScans"
        )
        val file = java.io.File(dir, csvFilename)
        if (!file.exists()) return

        val lastModified = file.lastModified()
        val uploadKey = "uploaded_${csvFilename}_${lastModified}"
        if (prefs.getBoolean(uploadKey, false)) {
            return // Эта версия файла уже была успешно выгружена
        }

        Toast.makeText(requireContext(), "Фоновая загрузка результатов на Yandex Disk...", Toast.LENGTH_SHORT).show()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) { file.readBytes() }
                val uploadPath = "${DiskConfig.TASK_RESULTS_PATH}/${file.name}"
                val result = YandexDiskClient.uploadFile(token, uploadPath, bytes)
                if (result.isSuccess) {
                    prefs.edit().putBoolean(uploadKey, true).apply()
                    Toast.makeText(requireContext(), "Результаты сохранены в облаке ✓", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), "Ошибка загрузки: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
            }
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
