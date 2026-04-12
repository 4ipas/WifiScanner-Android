package com.example.wifiscanner.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.wifiscanner.R
import com.example.wifiscanner.models.NodeDTO

class TaskNodeAdapter(
    private val onNodeClick: (NodeDTO) -> Unit,
    private val onActionStart: (NodeDTO) -> Unit,
    private val onActionCancel: (NodeDTO) -> Unit,
    private val onActionMenu: (NodeDTO, View) -> Unit
) : ListAdapter<NodeDTO, TaskNodeAdapter.NodeViewHolder>(NodeDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NodeViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_task_node, parent, false)
        return NodeViewHolder(view)
    }

    override fun onBindViewHolder(holder: NodeViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class NodeViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvNodeName: TextView = itemView.findViewById(R.id.tvNodeName)
        private val tvNodeStatus: TextView = itemView.findViewById(R.id.tvNodeStatus)
        private val btnMenu: ImageButton = itemView.findViewById(R.id.btnMenu)
        private val btnActionStart: Button = itemView.findViewById(R.id.btnActionStart)
        private val btnActionCancel: Button = itemView.findViewById(R.id.btnActionCancel)
        
        private val defaultStatusColor = tvNodeStatus.textColors

        fun bind(node: NodeDTO) {
            tvNodeName.text = node.name
            
            // Default setup
            btnMenu.visibility = View.GONE
            btnActionStart.visibility = View.GONE
            btnActionCancel.visibility = View.GONE
            
            itemView.setOnClickListener { onNodeClick(node) }

            when (node.nodeType) {
                "ADDRESS" -> {
                    val stats = calcStats(node)
                    btnMenu.visibility = View.VISIBLE
                    if (stats.isFinished && stats.total > 0) {
                        tvNodeStatus.text = "Завершено (${stats.completed}/${stats.total})"
                        tvNodeStatus.setTextColor(android.graphics.Color.parseColor("#388E3C"))
                    } else {
                        tvNodeStatus.text = "Локаций: ${stats.completed}/${stats.total}"
                        tvNodeStatus.setTextColor(defaultStatusColor)
                    }
                    btnMenu.setOnClickListener { onActionMenu(node, it) }
                }
                "ENTRANCE", "FLOOR" -> {
                    val stats = calcStats(node)
                    if (stats.isFinished && stats.total > 0) {
                        tvNodeStatus.text = "Завершено (${stats.completed}/${stats.total})"
                        tvNodeStatus.setTextColor(android.graphics.Color.parseColor("#388E3C"))
                    } else {
                        tvNodeStatus.text = "Локаций: ${stats.completed}/${stats.total}"
                        tvNodeStatus.setTextColor(defaultStatusColor)
                    }
                }
                "LOCATION" -> {
                    btnMenu.visibility = View.VISIBLE
                    
                    val hasActiveScan = currentList.any { it.nodeType == "LOCATION" && it.tasks.firstOrNull()?.status?.startsWith("IN_PROGRESS") == true }
                    
                    val task = node.tasks.firstOrNull()
                    val required = task?.requiredScans ?: 5
                    val status = task?.status ?: "PENDING"
                    
                    if (status.startsWith("COMPLETED")) {
                        val recordsText = status.substringAfter("COMPLETED_", "").let { 
                            if (it.isNotEmpty()) " | Записей: $it" else "" 
                        }
                        tvNodeStatus.text = "Завершено ($required/$required)$recordsText"
                        tvNodeStatus.setTextColor(android.graphics.Color.parseColor("#388E3C"))
                        btnActionStart.visibility = View.GONE
                        btnActionCancel.visibility = View.GONE
                    } else if (status == "SKIPPED") {
                        tvNodeStatus.text = "Отсутствует (Пропущено)"
                        tvNodeStatus.setTextColor(defaultStatusColor)
                        btnActionStart.visibility = View.GONE
                        btnActionCancel.visibility = View.GONE
                    } else if (status.startsWith("IN_PROGRESS")) {
                        val parts = status.split("_")
                        val progress = parts.getOrNull(2)?.toIntOrNull() ?: 0
                        val records = parts.getOrNull(3)?.toIntOrNull() ?: 0
                        tvNodeStatus.text = "В процессе ($progress/$required)\nЗаписей: $records"
                        tvNodeStatus.setTextColor(android.graphics.Color.parseColor("#F57C00"))
                        btnActionStart.visibility = View.GONE
                        btnActionCancel.visibility = View.VISIBLE
                    } else {
                        tvNodeStatus.text = "Ожидает (0/$required)"
                        tvNodeStatus.setTextColor(defaultStatusColor)
                        btnActionStart.visibility = if (hasActiveScan) View.GONE else View.VISIBLE
                        btnActionCancel.visibility = View.GONE
                    }
                    
                    btnMenu.setOnClickListener { onActionMenu(node, it) }
                    btnActionStart.setOnClickListener { onActionStart(node) }
                    btnActionCancel.setOnClickListener { onActionCancel(node) }
                }
            }
        }
    }

    class NodeDiffCallback : DiffUtil.ItemCallback<NodeDTO>() {
        override fun areItemsTheSame(oldItem: NodeDTO, newItem: NodeDTO): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: NodeDTO, newItem: NodeDTO): Boolean {
            return oldItem == newItem
        }
    }
}

private data class NodeStats(val total: Int, val completed: Int, val skipped: Int, val pending: Int) {
    val isFinished: Boolean get() = total > 0 && pending == 0
}

private fun calcStats(node: NodeDTO): NodeStats {
    if (node.nodeType == "LOCATION") {
        val status = node.tasks.firstOrNull()?.status ?: "PENDING"
        return when {
            status.startsWith("COMPLETED") -> NodeStats(1, 1, 0, 0)
            status == "SKIPPED" -> NodeStats(1, 0, 1, 0)
            else -> NodeStats(1, 0, 0, 1)
        }
    } else {
        var t = 0; var c = 0; var s = 0; var p = 0
        for (child in node.children) {
            val st = calcStats(child)
            t += st.total; c += st.completed; s += st.skipped; p += st.pending
        }
        return NodeStats(t, c, s, p)
    }
}
