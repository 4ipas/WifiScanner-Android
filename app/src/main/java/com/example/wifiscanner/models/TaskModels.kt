package com.example.wifiscanner.models

import com.google.gson.annotations.SerializedName
import java.util.UUID

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

/**
 * Глубокое копирование дерева узлов с генерацией новых UUID
 * и сбросом всех статусов задач в PENDING.
 * Имя узла НЕ меняется — уникальность гарантируется GUID (id).
 */
fun NodeDTO.deepCopyWithReset(newParentId: String? = null): NodeDTO {
    val newId = UUID.randomUUID().toString()
    return NodeDTO(
        id = newId,
        parentId = newParentId ?: parentId,
        nodeType = nodeType,
        name = name,
        children = children.map { it.deepCopyWithReset(newId) },
        tasks = tasks.map { ScanTaskDTO(UUID.randomUUID().toString(), it.requiredScans, "PENDING") }
    )
}
