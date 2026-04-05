package com.example.wifiscanner.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.wifiscanner.R
import com.example.wifiscanner.models.WifiScanResult

import com.example.wifiscanner.utils.frequencyToChannel

class WifiListAdapter : ListAdapter<WifiScanResult, WifiListAdapter.WifiViewHolder>(WifiDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): WifiViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_wifi, parent, false)
        return WifiViewHolder(view)
    }

    override fun onBindViewHolder(holder: WifiViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class WifiViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvSsid: TextView = itemView.findViewById(R.id.tvSsid)
        private val tvMac: TextView = itemView.findViewById(R.id.tvMac)
        private val tvFreq: TextView = itemView.findViewById(R.id.tvFreq)
        private val tvChannel: TextView = itemView.findViewById(R.id.tvChannel)
        private val tvRssi: TextView = itemView.findViewById(R.id.tvRssi)

        fun bind(result: WifiScanResult) {
            tvSsid.text = if (result.ssid.isNullOrEmpty()) "<Hidden SSID>" else result.ssid
            tvMac.text = result.mac
            tvFreq.text = "${result.frequency} MHz"
            val ch = if (result.channel > 0) result.channel else frequencyToChannel(result.frequency)
            tvChannel.text = "Ch:$ch"
            tvRssi.text = "${result.rssi} dBm"
        }
    }
}

class WifiDiffCallback : DiffUtil.ItemCallback<WifiScanResult>() {
    override fun areItemsTheSame(oldItem: WifiScanResult, newItem: WifiScanResult): Boolean {
        return oldItem.mac == newItem.mac
    }

    override fun areContentsTheSame(oldItem: WifiScanResult, newItem: WifiScanResult): Boolean {
        return oldItem == newItem
    }
}
