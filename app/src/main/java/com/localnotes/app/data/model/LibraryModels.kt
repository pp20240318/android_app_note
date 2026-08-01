package com.localnotes.app.data.model

import kotlinx.serialization.Serializable

const val CURRENT_SCHEMA_VERSION = 2

@Serializable
data class LibraryMeta(
    val name: String,
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
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
    val updatedAt: Long = System.currentTimeMillis(),
    val favorite: Boolean = false,
    val deletedAt: Long? = null
) {
    val isDeleted: Boolean get() = deletedAt != null
}

@Serializable
data class TreeIndex(
    val nodes: List<TreeNode> = emptyList(),
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION
)

data class Breadcrumb(
    val id: String?,
    val name: String
)

data class SearchHit(
    val node: TreeNode,
    val snippet: String
)

data class IntegrityReport(
    val orphanDocDirs: List<String> = emptyList(),
    val missingDocDirs: List<String> = emptyList()
) {
    val hasIssues: Boolean
        get() = orphanDocDirs.isNotEmpty() || missingDocDirs.isNotEmpty()

    fun summary(): String {
        val parts = mutableListOf<String>()
        if (orphanDocDirs.isNotEmpty()) {
            parts += "发现 ${orphanDocDirs.size} 个未被索引的文档目录"
        }
        if (missingDocDirs.isNotEmpty()) {
            parts += "${missingDocDirs.size} 个文档缺少磁盘文件"
        }
        return parts.joinToString("；")
    }
}
