package com.example.wifiscanner.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.example.wifiscanner.R
import com.example.wifiscanner.cloud.DiskConfig
import com.example.wifiscanner.cloud.YandexDiskClient
import com.example.wifiscanner.models.NodeDTO
import com.example.wifiscanner.utils.ControllerCsvLogger
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [CONTROLLER MODE — TEMPORARY, REMOVABLE]
 * Фрагмент контролёра: кнопки этажей/улица/подъезд.
 * Работает сразу со ВСЕМИ адресами из загруженных Blueprint.
 * Одна непрерывная сессия, переключение домов/подъездов на лету.
 */
class ControllerFragment : Fragment() {

    var onStopSession: (() -> Unit)? = null

    private lateinit var tvTimer: TextView
    private lateinit var tvCheckpointCount: TextView
    private lateinit var tvCurrentLocation: TextView
    private lateinit var btnStreet: Button
    private lateinit var llAddressSelector: LinearLayout
    private lateinit var tvEntranceLabel: TextView
    private lateinit var llEntranceSelector: LinearLayout
    private lateinit var btnBeforeEntrance: Button
    private lateinit var llFloorGrid: LinearLayout
    private lateinit var btnElevator: Button
    private lateinit var tvLog: TextView
    private lateinit var btnStop: Button

    private var allAddresses: List<NodeDTO> = emptyList()
    private var selectedAddress: NodeDTO? = null
    private var selectedEntrance: NodeDTO? = null
    private var currentZone: String = ""
    private var checkpointCount = 0
    private var sessionStartTime: Long = 0
    private val timerHandler = Handler(Looper.getMainLooper())
    private val logLines = mutableListOf<String>()
    private val timeFormat = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_controller, container, false)

        tvTimer = view.findViewById(R.id.tvControllerTimer)
        tvCheckpointCount = view.findViewById(R.id.tvCheckpointCount)
        tvCurrentLocation = view.findViewById(R.id.tvCurrentLocation)
        btnStreet = view.findViewById(R.id.btnStreet)
        llAddressSelector = view.findViewById(R.id.llAddressSelector)
        tvEntranceLabel = view.findViewById(R.id.tvEntranceLabel)
        llEntranceSelector = view.findViewById(R.id.llEntranceSelector)
        btnBeforeEntrance = view.findViewById(R.id.btnBeforeEntrance)
        llFloorGrid = view.findViewById(R.id.llFloorGrid)
        btnElevator = view.findViewById(R.id.btnElevator)
        tvLog = view.findViewById(R.id.tvLog)
        btnStop = view.findViewById(R.id.btnStop)

        btnStreet.setOnClickListener { recordCheckpoint("Улица") }
        btnBeforeEntrance.setOnClickListener { recordCheckpoint("Перед подъездом") }
        btnElevator.setOnClickListener { recordCheckpoint("Лифт") }
        btnStop.setOnClickListener { confirmStop() }

        return view
    }

    /**
     * Инициализация: вызывается из TasksFragment.
     * Принимает ВСЕ адреса из всех заданий.
     */
    fun initWithAddresses(addresses: List<NodeDTO>) {
        allAddresses = addresses

        // Start CSV session
        val sessionName = if (addresses.size == 1) addresses[0].name else "multi_${addresses.size}"
        ControllerCsvLogger.startSession(sessionName)
        sessionStartTime = System.currentTimeMillis()
        startTimer()

        // Build address buttons
        buildAddressButtons()

        // Auto-select first address
        if (addresses.isNotEmpty()) {
            selectAddress(addresses[0])
        }

        addLogLine("Сессия запущена (${addresses.size} адрес[ов])")
    }

    // ─── Address selector ──────────────────────────────────────────

    private fun buildAddressButtons() {
        llAddressSelector.removeAllViews()
        for (address in allAddresses) {
            val btn = MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = address.name
                textSize = 12f
                isAllCaps = false
                setPadding(8.dpToPx(), 0, 8.dpToPx(), 0)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = 6 }
                setOnClickListener { selectAddress(address) }
            }
            llAddressSelector.addView(btn)
        }
    }

    private fun selectAddress(address: NodeDTO) {
        selectedAddress = address

        // Highlight selected
        for (i in 0 until llAddressSelector.childCount) {
            val btn = llAddressSelector.getChildAt(i) as? MaterialButton ?: continue
            val isSelected = (btn.text.toString() == address.name)
            btn.alpha = if (isSelected) 1f else 0.4f
        }

        // Build entrance buttons
        val entrances = address.children.filter { it.nodeType == "ENTRANCE" }
        buildEntranceButtons(entrances)

        if (entrances.isNotEmpty()) {
            selectEntrance(entrances[0])
        } else {
            // No entrances — clear floors
            selectedEntrance = null
            tvEntranceLabel.visibility = View.GONE
            btnBeforeEntrance.visibility = View.GONE
            llFloorGrid.removeAllViews()
            btnElevator.visibility = View.GONE
        }
    }

    // ─── Entrance selector ─────────────────────────────────────────

    private fun buildEntranceButtons(entrances: List<NodeDTO>) {
        llEntranceSelector.removeAllViews()
        if (entrances.isEmpty()) {
            tvEntranceLabel.visibility = View.GONE
            return
        }
        tvEntranceLabel.visibility = View.VISIBLE

        for (entrance in entrances) {
            val btn = MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = entrance.name.replace("Подъезд ", "П")
                textSize = 13f
                isAllCaps = false
                setPadding(8.dpToPx(), 0, 8.dpToPx(), 0)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = 6 }
                setOnClickListener { selectEntrance(entrance) }
            }
            llEntranceSelector.addView(btn)
        }
    }

    private fun selectEntrance(entrance: NodeDTO) {
        selectedEntrance = entrance
        btnBeforeEntrance.visibility = View.VISIBLE

        // Highlight selected
        for (i in 0 until llEntranceSelector.childCount) {
            val btn = llEntranceSelector.getChildAt(i) as? MaterialButton ?: continue
            val isSelected = (btn.text.toString() == entrance.name.replace("Подъезд ", "П"))
            btn.alpha = if (isSelected) 1f else 0.4f
        }

        // Build floor buttons
        val floors = entrance.children.filter { it.nodeType == "FLOOR" }
        buildFloorButtons(floors)

        // Elevator for 9+ floors
        btnElevator.visibility = if (floors.size >= 9) View.VISIBLE else View.GONE
    }

    // ─── Floor grid ────────────────────────────────────────────────

    private fun buildFloorButtons(floors: List<NodeDTO>) {
        llFloorGrid.removeAllViews()
        // v5.3.0: Адаптивное кол-во колонок — 4 для компактности на узких экранах
        val cols = 4
        var row: LinearLayout? = null

        for ((index, floor) in floors.withIndex()) {
            if (index % cols == 0) {
                row = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = 4 }
                }
                llFloorGrid.addView(row)
            }

            val btn = MaterialButton(requireContext()).apply {
                text = floor.name
                textSize = 13f
                isAllCaps = false
                cornerRadius = 8.dpToPx()
                // v5.3.0: Уменьшенная высота для компактности, padding для отступа текста
                setPadding(4.dpToPx(), 0, 4.dpToPx(), 0)
                layoutParams = LinearLayout.LayoutParams(
                    0, 44.dpToPx(), 1f
                ).apply { marginEnd = 4; marginStart = 4 }
                setOnClickListener { recordCheckpoint(floor.name) }
            }
            row?.addView(btn)
        }

        // Fill last row with spacers
        val remainder = floors.size % cols
        if (remainder != 0 && row != null) {
            for (i in remainder until cols) {
                val spacer = View(requireContext()).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 1, 1f).apply { marginEnd = 4; marginStart = 4 }
                }
                row.addView(spacer)
            }
        }
    }

    // ─── Checkpoint recording ──────────────────────────────────────

    private fun recordCheckpoint(zone: String) {
        // Улица независима от конкретного дома и подъезда
        val isStreet = (zone == "Улица")
        val address = if (isStreet) "" else (selectedAddress?.name ?: "")
        val entrance = if (isStreet) "" else (selectedEntrance?.name ?: "")

        ControllerCsvLogger.writeCheckpoint(address, entrance, zone)
        currentZone = zone
        checkpointCount++

        // Update UI
        if (isStreet) {
            tvCurrentLocation.text = "📍 Улица (переход/независимая зона)"
            addLogLine("Улица (переход/независимая зона)")
        } else {
            tvCurrentLocation.text = "📍 $zone  •  $entrance  •  $address"
            val shortAddr = address.take(15)
            val shortEntrance = entrance.replace("Подъезд ", "П")
            addLogLine("$zone  [$shortEntrance, $shortAddr]")
        }
        
        tvCheckpointCount.text = "$checkpointCount чекпоинтов"

        vibrateShort()
        // Снимаем выделение у кнопок адресов/подъездов, если Улица
        if (isStreet) {
            clearAddressEntranceHighlight()
        } else {
            // Восстанавливаем выделение нужного адреса и подъезда, если мы не на улице
            rehighlightAddressEntrance()
        }
        highlightActiveZone(zone)
    }

    private fun clearAddressEntranceHighlight() {
        for (i in 0 until llAddressSelector.childCount) {
            (llAddressSelector.getChildAt(i) as? MaterialButton)?.alpha = 0.4f
        }
        for (i in 0 until llEntranceSelector.childCount) {
            (llEntranceSelector.getChildAt(i) as? MaterialButton)?.alpha = 0.4f
        }
    }

    private fun rehighlightAddressEntrance() {
        for (i in 0 until llAddressSelector.childCount) {
            val btn = llAddressSelector.getChildAt(i) as? MaterialButton ?: continue
            val isSelected = (btn.text.toString() == selectedAddress?.name)
            btn.alpha = if (isSelected) 1f else 0.4f
        }
        for (i in 0 until llEntranceSelector.childCount) {
            val btn = llEntranceSelector.getChildAt(i) as? MaterialButton ?: continue
            val isSelected = (btn.text.toString() == selectedEntrance?.name?.replace("Подъезд ", "П"))
            btn.alpha = if (isSelected) 1f else 0.4f
        }
    }

    private fun highlightActiveZone(zone: String) {
        // Floor buttons
        for (i in 0 until llFloorGrid.childCount) {
            val row = llFloorGrid.getChildAt(i) as? LinearLayout ?: continue
            for (j in 0 until row.childCount) {
                val btn = row.getChildAt(j) as? MaterialButton ?: continue
                btn.alpha = if (btn.text.toString() == zone) 1f else 0.5f
            }
        }
        btnStreet.alpha = if (zone == "Улица") 1f else 0.6f
        btnBeforeEntrance.alpha = if (zone == "Перед подъездом") 1f else 0.6f
        btnElevator.alpha = if (zone == "Лифт") 1f else 0.6f
    }

    // ─── Stop session ──────────────────────────────────────────────

    private fun confirmStop() {
        AlertDialog.Builder(requireContext())
            .setTitle("Остановить сессию?")
            .setMessage("CSV с $checkpointCount чекпоинтами будет сохранён и загружен на Яндекс Диск.")
            .setPositiveButton("Стоп") { _, _ -> doStop() }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun doStop() {
        timerHandler.removeCallbacksAndMessages(null)
        ControllerCsvLogger.stopSession()

        // Сохраняем контекст ДО удаления фрагмента
        val ctx = context ?: return
        val file = ControllerCsvLogger.getFile()

        Toast.makeText(ctx, "GT сохранён ($checkpointCount чекпоинтов)", Toast.LENGTH_LONG).show()

        // Фоновая загрузка
        if (file != null && file.exists()) {
            uploadGtFileInBackground(ctx, file)
        }

        // Возврат к заданиям
        onStopSession?.invoke()
    }

    private fun uploadGtFileInBackground(ctx: android.content.Context, file: java.io.File) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        if (!prefs.getBoolean("pref_yadisk_auto_upload", true)) return

        val token = DiskConfig.getToken(ctx)
        if (token.isBlank()) return

        // v5.2.2: Через UploadQueueManager — offline-устойчивость
        val uploadPath = "${DiskConfig.GT_RESULTS_PATH}/${file.name}"
        com.example.wifiscanner.cloud.UploadQueueManager.enqueue(file.absolutePath, uploadPath)
    }

    // ─── Timer ─────────────────────────────────────────────────────

    private fun startTimer() {
        timerHandler.post(object : Runnable {
            override fun run() {
                val elapsed = System.currentTimeMillis() - sessionStartTime
                val secs = (elapsed / 1000) % 60
                val mins = (elapsed / 1000 / 60) % 60
                val hrs = (elapsed / 1000 / 3600)
                tvTimer.text = "⏱ %02d:%02d:%02d".format(hrs, mins, secs)
                timerHandler.postDelayed(this, 1000)
            }
        })
    }

    // ─── Utilities ─────────────────────────────────────────────────

    private fun addLogLine(text: String) {
        val time = timeFormat.format(java.util.Date())
        logLines.add(0, "$time → $text")
        // Keep last 50 lines
        if (logLines.size > 50) logLines.removeAt(logLines.size - 1)
        tvLog.text = logLines.joinToString("\n")
    }

    private fun vibrateShort() {
        try {
            val vibrator = requireContext().getSystemService(android.content.Context.VIBRATOR_SERVICE) as Vibrator
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(50)
            }
        } catch (_: Exception) {}
    }

    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        super.onDestroyView()
        timerHandler.removeCallbacksAndMessages(null)
    }
}
