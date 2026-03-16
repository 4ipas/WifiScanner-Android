package com.example.wifiscanner.ui

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.wifiscanner.R
import com.example.wifiscanner.adapters.WifiListAdapter
import com.example.wifiscanner.models.WifiScanResult
import com.example.wifiscanner.utils.PermissionHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ViewFragment : Fragment() {

    private lateinit var adapter: WifiListAdapter
    private lateinit var rvWifiList: RecyclerView
    private lateinit var wifiManager: WifiManager

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_view, container, false)
        rvWifiList = view.findViewById(R.id.rvWifiList)
        wifiManager = requireContext().applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        
        setupRecyclerView()
        startAutoRefresh()
        
        return view
    }

    private fun setupRecyclerView() {
        adapter = WifiListAdapter()
        rvWifiList.layoutManager = LinearLayoutManager(requireContext())
        rvWifiList.adapter = adapter
    }

    private fun startAutoRefresh() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (isActive) {
                    updateWifiList()
                    delay(2000) // Update list every 2 seconds for a responsive UI
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun updateWifiList() {
        if (!PermissionHelper.hasBasicPermissions(requireContext())) return

        @Suppress("DEPRECATION")
        if (wifiManager.isWifiEnabled) {
            wifiManager.startScan()
        }

        val results = try { wifiManager.scanResults } catch (_: Exception) { emptyList() }

        val mapped = results.map { scanResult ->
            val extractedSsid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                try {
                    scanResult.wifiSsid?.toString()?.removeSurrounding("\"") ?: ""
                } catch (_: Exception) {
                    @Suppress("DEPRECATION")
                    scanResult.SSID
                }
            } else {
                @Suppress("DEPRECATION")
                scanResult.SSID
            }

            WifiScanResult(
                locationName = "View",
                timestamp = "",
                mac = scanResult.BSSID ?: "",
                rssi = scanResult.level,
                ssid = extractedSsid ?: "",
                frequency = scanResult.frequency,
                recordNumber = 0L
            )
        }.sortedByDescending { it.rssi }

        adapter.submitList(mapped)
    }
}
