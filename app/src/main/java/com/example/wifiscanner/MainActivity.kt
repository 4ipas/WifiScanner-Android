package com.example.wifiscanner

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.viewpager2.widget.ViewPager2
import com.example.wifiscanner.adapters.ViewPagerAdapter
import com.example.wifiscanner.utils.OemBatteryHelper
import com.example.wifiscanner.utils.PermissionHelper
import com.example.wifiscanner.ui.SettingsActivity
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator

class MainActivity : AppCompatActivity() {

    private var isTabsSetup = false
    private var onboardingRunThisSession = false

    // ── v5.3.0: Пошаговый onboarding разрешений ────────────────────────
    // Порядок: 1) Location → 2) Activity Recognition → 3) Notifications →
    //          4) Background Location → 5) Battery Whitelist

    // Шаг 1: Foreground Location
    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.entries.all { it.value }
        if (granted) {
            requestNextPermission(OnboardingStep.ACTIVITY_RECOGNITION)
        } else {
            // Даже если отказался — пробуем остальные
            requestNextPermission(OnboardingStep.ACTIVITY_RECOGNITION)
        }
    }

    // Шаг 2: Activity Recognition (шагомер)
    private val activityRecognitionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        requestNextPermission(OnboardingStep.NOTIFICATIONS)
    }

    // Шаг 3: Notifications (Android 13+)
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        requestNextPermission(OnboardingStep.BACKGROUND_LOCATION)
    }

    // Шаг 4: Background Location (отдельно от foreground — требование Android 11+)
    private val backgroundLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        requestNextPermission(OnboardingStep.BATTERY_WHITELIST)
    }

    // Шаг 5: Battery Whitelist — не через launcher, а через Intent
    // (обрабатывается в onResume после возврата из системного диалога)

    private enum class OnboardingStep {
        LOCATION,
        ACTIVITY_RECOGNITION,
        NOTIFICATIONS,
        BACKGROUND_LOCATION,
        BATTERY_WHITELIST,
        DONE
    }

    private var pendingBatteryCheck = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // v5.1.0: Глобальная инициализация логирования (до всего остального)
        com.example.wifiscanner.utils.DiagnosticLogger.initGlobal(this)

        // v5.1.0: Инициализация очереди загрузки + ретрай зависших файлов
        com.example.wifiscanner.cloud.UploadQueueManager.init(this)
        com.example.wifiscanner.cloud.UploadQueueManager.processQueue()

        findViewById<Button>(R.id.btnGrantPermission).setOnClickListener {
            startOnboarding()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_custom_menu -> {
                com.example.wifiscanner.utils.UIHelper.showActionSheet(this, listOf(
                    com.example.wifiscanner.utils.ActionSheetItem("Настройки") {
                        startActivity(Intent(this, SettingsActivity::class.java))
                    }
                ))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onStart() {
        super.onStart()
        if (PermissionHelper.hasBasicPermissions(this)) {
            setupTabsOnce()
            // v5.3.0: Даже если базовые разрешения есть, запросить остальные (один раз за сессию)
            if (!onboardingRunThisSession) {
                onboardingRunThisSession = true
                requestMissingExtendedPermissions()
            }
        } else {
            showEmptyState()
            startOnboarding()
        }
    }

    /**
     * v5.3.0: Запрашивает расширенные разрешения, которые могли быть пропущены.
     * Вызывается если basic permissions уже выданы (location + activity_recognition).
     */
    private fun requestMissingExtendedPermissions() {
        // Проверяем с конца цепочки — находим первый недостающий
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestNextPermission(OnboardingStep.NOTIFICATIONS)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestNextPermission(OnboardingStep.BACKGROUND_LOCATION)
        } else {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                requestNextPermission(OnboardingStep.BATTERY_WHITELIST)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // После возврата из системного диалога battery whitelist
        if (pendingBatteryCheck) {
            pendingBatteryCheck = false
            requestNextPermission(OnboardingStep.DONE)
        }
        // Если вернулись из настроек и разрешения дали — показать вкладки
        if (PermissionHelper.hasBasicPermissions(this) && !isTabsSetup) {
            setupTabsOnce()
        }
    }

    // ── Onboarding Flow ─────────────────────────────────────────────────

    private fun startOnboarding() {
        requestNextPermission(OnboardingStep.LOCATION)
    }

    private fun requestNextPermission(step: OnboardingStep) {
        when (step) {
            OnboardingStep.LOCATION -> {
                val perms = arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
                val needed = perms.filter {
                    ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
                }
                if (needed.isNotEmpty()) {
                    locationPermissionLauncher.launch(needed.toTypedArray())
                } else {
                    requestNextPermission(OnboardingStep.ACTIVITY_RECOGNITION)
                }
            }

            OnboardingStep.ACTIVITY_RECOGNITION -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val perm = Manifest.permission.ACTIVITY_RECOGNITION
                    if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                        activityRecognitionLauncher.launch(perm)
                    } else {
                        requestNextPermission(OnboardingStep.NOTIFICATIONS)
                    }
                } else {
                    requestNextPermission(OnboardingStep.NOTIFICATIONS)
                }
            }

            OnboardingStep.NOTIFICATIONS -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val perm = Manifest.permission.POST_NOTIFICATIONS
                    if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                        notificationPermissionLauncher.launch(perm)
                    } else {
                        requestNextPermission(OnboardingStep.BACKGROUND_LOCATION)
                    }
                } else {
                    requestNextPermission(OnboardingStep.BACKGROUND_LOCATION)
                }
            }

            OnboardingStep.BACKGROUND_LOCATION -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val perm = Manifest.permission.ACCESS_BACKGROUND_LOCATION
                    if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                        // Foreground location должна быть уже выдана
                        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                            backgroundLocationLauncher.launch(perm)
                        } else {
                            // Foreground не дали — пропускаем background
                            requestNextPermission(OnboardingStep.BATTERY_WHITELIST)
                        }
                    } else {
                        requestNextPermission(OnboardingStep.BATTERY_WHITELIST)
                    }
                } else {
                    requestNextPermission(OnboardingStep.BATTERY_WHITELIST)
                }
            }

            OnboardingStep.BATTERY_WHITELIST -> {
                val pm = getSystemService(POWER_SERVICE) as PowerManager
                if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                    pendingBatteryCheck = true
                    try {
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:$packageName")
                        }
                        startActivity(intent)
                    } catch (e: Exception) {
                        pendingBatteryCheck = false
                        requestNextPermission(OnboardingStep.DONE)
                    }
                } else {
                    requestNextPermission(OnboardingStep.DONE)
                }
            }

            OnboardingStep.DONE -> {
                if (PermissionHelper.hasBasicPermissions(this)) {
                    setupTabsOnce()
                } else {
                    showEmptyState()
                }
            }
        }
    }

    // ── UI Setup ────────────────────────────────────────────────────────

    private fun setupTabsOnce() {
        if (isTabsSetup) return
        isTabsSetup = true

        findViewById<LinearLayout>(R.id.layoutPermissionDenied).visibility = View.GONE
        val viewPager = findViewById<ViewPager2>(R.id.viewPager)
        viewPager.visibility = View.VISIBLE

        val tabLayout = findViewById<TabLayout>(R.id.tabLayout)

        val adapter = ViewPagerAdapter(this)
        viewPager.adapter = adapter

        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> "Текущие сети"
                1 -> "Запись"
                2 -> "Задания"
                3 -> "История"
                else -> ""
            }
        }.attach()
    }

    private fun showEmptyState() {
        findViewById<LinearLayout>(R.id.layoutPermissionDenied).visibility = View.VISIBLE
        findViewById<ViewPager2>(R.id.viewPager).visibility = View.GONE
    }
}
