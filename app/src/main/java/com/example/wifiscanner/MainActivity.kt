package com.example.wifiscanner

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.viewpager2.widget.ViewPager2
import com.example.wifiscanner.adapters.ViewPagerAdapter
import com.example.wifiscanner.utils.PermissionHelper
import com.example.wifiscanner.ui.SettingsActivity
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import android.view.Menu
import android.view.MenuItem

class MainActivity : AppCompatActivity() {

    private var isTabsSetup = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            setupTabsOnce()
        } else {
            showPermissionDeniedDialog()
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onStart() {
        super.onStart()
        if (PermissionHelper.hasAllPermissions(this)) {
            setupTabsOnce()
        } else {
            requestPermissions()
        }
    }

    private fun setupTabsOnce() {
        if (isTabsSetup) return
        isTabsSetup = true

        val tabLayout = findViewById<TabLayout>(R.id.tabLayout)
        val viewPager = findViewById<ViewPager2>(R.id.viewPager)

        val adapter = ViewPagerAdapter(this)
        viewPager.adapter = adapter

        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> "Текущие сети"
                1 -> "Запись"
                else -> ""
            }
        }.attach()
    }

    private fun requestPermissions() {
        val permissions = PermissionHelper.getRequiredPermissions()
        val notGranted = permissions.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (notGranted.isNotEmpty()) {
            permissionLauncher.launch(notGranted.toTypedArray())
        }
    }

    private fun showPermissionDeniedDialog() {
        AlertDialog.Builder(this)
            .setTitle("Обязательные разрешения")
            .setMessage("Для поиска Wi-Fi сетей Android требует доступ к местоположению на аппаратном уровне. Без этого приложение не сможет работать.\n\nПожалуйста, нажмите «В Настройки» -> Разрешения -> Местоположение -> «Разрешать всегда».")
            .setPositiveButton("В Настройки") { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
                startActivity(intent)
            }
            .setCancelable(false)
            .show()
    }
}
