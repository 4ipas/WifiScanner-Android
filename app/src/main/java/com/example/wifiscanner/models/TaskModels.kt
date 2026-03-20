package com.example.wifiscanner.models

import com.google.gson.annotations.SerializedName

data class NodeDTO(
    @SerializedName("id") val id: String,
    @SerializedName("parent_id") val parentId: String?,
    @SerializedName("node_type") val nodeType: String,
    @SerializedName("name") val name: String,
    @SerializedName("children") val children: List<NodeDTO> = emptyList(),
    @SerializedName("tasks") val tasks: List<ScanTaskDTO> = emptyList()
)

data class ScanTaskDTO(
    @SerializedName("id") val id: String,
    @SerializedName("scans") val requiredScans: Int,
    @SerializedName("status") var status: String
)
