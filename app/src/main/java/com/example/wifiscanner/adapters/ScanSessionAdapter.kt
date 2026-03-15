package com.example.wifiscanner.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.wifiscanner.R
import com.example.wifiscanner.models.ScanSession

class ScanSessionAdapter : ListAdapter<ScanSession, ScanSessionAdapter.SessionViewHolder>(DiffCallback) {

    class SessionViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvLocation: TextView = view.findViewById(R.id.tvHistoryLocation)
        val tvTime: TextView = view.findViewById(R.id.tvHistoryTime)
        val tvSnapshots: TextView = view.findViewById(R.id.tvHistorySnapshots)
        val tvRecords: TextView = view.findViewById(R.id.tvHistoryRecords)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SessionViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_scan_session, parent, false)
        return SessionViewHolder(view)
    }

    override fun onBindViewHolder(holder: SessionViewHolder, position: Int) {
        val session = getItem(position)
        holder.tvLocation.text = session.locationName.ifBlank { "Без названия" }
        
        val end = session.endTime ?: "В процессе..."
        holder.tvTime.text = "${session.startTime} - $end"
        
        holder.tvSnapshots.text = "Слепков: ${session.snapshotCount}"
        holder.tvRecords.text = "Записей: ${session.recordCount}"
    }

    companion object {
        private val DiffCallback = object : DiffUtil.ItemCallback<ScanSession>() {
            override fun areItemsTheSame(oldItem: ScanSession, newItem: ScanSession): Boolean {
                return oldItem.id == newItem.id
            }

            override fun areContentsTheSame(oldItem: ScanSession, newItem: ScanSession): Boolean {
                return oldItem == newItem
            }
        }
    }
}
