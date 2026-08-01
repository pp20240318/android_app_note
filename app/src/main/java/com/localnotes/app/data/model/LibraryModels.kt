package com.localnotes.app.data.model

import kotlinx.serialization.Serializable

@Serializable
data class LibraryMeta(
    val name: String,
    val schemaVersion: Int = 1,
    val createdAt: Long = System.currentTimeMillis()
)

@Serializable
enum class NodeType {
    FOLDER,
    DOCUMENT
}

@Serializable
data class TreeNode(
    val id: String,
    val type: NodeType,
    val name: String,
    val parentId: String? = null,
    val order: Int = 0,
    val updatedAt: Long = System.currentTimeMillis()
)

@Serializable
data class TreeIndex(
    val nodes: List<TreeNode> = emptyList()
)

data class Breadcrumb(
    val id: String?,
    val name: String
)
