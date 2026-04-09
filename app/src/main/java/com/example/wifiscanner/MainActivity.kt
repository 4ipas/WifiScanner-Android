package com.example.wifiscanner

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
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

import android.view.View
import android.widget.Button
import android.widget.LinearLayout

class MainActivity : AppCompatActivity() {

    private var isTabsSetup = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            setupTabsOnce()
        } else {
            showEmptyState()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        findViewById<Button>(R.id.btnGrantPermission).setOnClickListener {
            requestPermissions()
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
                    "Настройки" to {
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
        } else {
            showEmptyState()
            requestPermissions()
        }
    }

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
                else -> ""
            }
        }.attach()
    }

    private fun showEmptyState() {
        findViewById<LinearLayout>(R.id.layoutPermissionDenied).visibility = View.VISIBLE
        findViewById<ViewPager2>(R.id.viewPager).visibility = View.GONE
    }

    private fun requestPermissions() {
        val permissions = PermissionHelper.getBasicLocationPermissions()
        val notGranted = permissions.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (notGranted.isNotEmpty()) {
            permissionLauncher.launch(notGranted.toTypedArray())
        }
    }
}
