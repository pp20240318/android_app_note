package com.localnotes.app.data.repository

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.localnotes.app.data.model.LibraryMeta
import com.localnotes.app.data.model.NodeType
import com.localnotes.app.data.model.TreeIndex
import com.localnotes.app.data.model.TreeNode
import com.localnotes.app.data.prefs.LibraryPreferences
import com.localnotes.app.data.storage.SafLibraryStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class NotesRepository(
    private val context: Context,
    private val prefs: LibraryPreferences,
    private val store: SafLibraryStore
) {
    private val mutex = Mutex()
    private var cachedUri: Uri? = null
    private var cachedTree: TreeIndex = TreeIndex()

    val savedLibraryUri: Flow<String?> = prefs.libraryTreeUri

    suspend fun takePersistablePermission(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            context.contentResolver.takePersistableUriPermission(uri, flags)
        } catch (_: SecurityException) {
            // Some providers may not support persistable permissions; continue anyway.
        }
        prefs.setLibraryTreeUri(uri.toString())
    }

    suspend fun clearLibrary() = mutex.withLock {
        prefs.setLibraryTreeUri(null)
        cachedUri = null
        cachedTree = TreeIndex()
    }

    suspend fun createLibrary(uri: Uri, name: String): LibraryMeta = mutex.withLock {
        takePersistablePermission(uri)
        val meta = store.createLibrary(uri, name)
        cachedUri = uri
        cachedTree = TreeIndex()
        meta
    }

    suspend fun openLibrary(uri: Uri): LibraryMeta = mutex.withLock {
        takePersistablePermission(uri)
        val meta = store.openLibrary(uri)
        cachedUri = uri
        cachedTree = store.loadTree(uri)
        meta
    }

    suspend fun reopenSavedLibrary(): LibraryMeta? {
        val raw = prefs.libraryTreeUri.first() ?: return null
        return openLibrary(Uri.parse(raw))
    }

    suspend fun currentUri(): Uri? =
        cachedUri ?: prefs.libraryTreeUri.first()?.let(Uri::parse)

    suspend fun loadTree(): TreeIndex = mutex.withLock {
        ensureCacheLocked()
        cachedTree
    }

    suspend fun createFolder(parentId: String?, name: String): TreeNode = mutex.withLock {
        val uri = ensureCacheLocked()
        val node = TreeNode(
            id = store.newId(),
            type = NodeType.FOLDER,
            name = name.trim().ifBlank { "新建文件夹" },
            parentId = parentId,
            order = nextOrder(cachedTree, parentId)
        )
        cachedTree = cachedTree.copy(nodes = cachedTree.nodes + node)
        persistTreeLocked(uri)
        node
    }

    suspend fun createDocument(parentId: String?, title: String): TreeNode = mutex.withLock {
        val uri = ensureCacheLocked()
        val node = TreeNode(
            id = store.newId(),
            type = NodeType.DOCUMENT,
            name = title.trim().ifBlank { "未命名文档" },
            parentId = parentId,
            order = nextOrder(cachedTree, parentId)
        )
        cachedTree = cachedTree.copy(nodes = cachedTree.nodes + node)
        persistTreeLocked(uri)
        store.ensureDocumentFiles(uri, node.id)
        store.writeDocumentBody(uri, node.id, "")
        node
    }

    suspend fun renameNode(nodeId: String, name: String) = mutex.withLock {
        val uri = ensureCacheLocked()
        cachedTree = cachedTree.copy(
            nodes = cachedTree.nodes.map {
                if (it.id == nodeId) {
                    it.copy(
                        name = name.trim().ifBlank { it.name },
                        updatedAt = System.currentTimeMillis()
                    )
                } else it
            }
        )
        persistTreeLocked(uri)
    }

    suspend fun deleteNode(nodeId: String) = mutex.withLock {
        val uri = ensureCacheLocked()
        val toDelete = collectDescendants(cachedTree, nodeId) + nodeId
        cachedTree = cachedTree.copy(nodes = cachedTree.nodes.filterNot { it.id in toDelete })
        persistTreeLocked(uri)
    }

    suspend fun readDocument(docId: String): Pair<TreeNode, String> = mutex.withLock {
        val uri = ensureCacheLocked()
        val node = cachedTree.nodes.firstOrNull { it.id == docId && it.type == NodeType.DOCUMENT }
            ?: error("文档不存在")
        val body = store.readDocumentBody(uri, docId)
        node to body
    }

    suspend fun saveDocument(docId: String, title: String, body: String) = mutex.withLock {
        val uri = ensureCacheLocked()
        cachedTree = cachedTree.copy(
            nodes = cachedTree.nodes.map {
                if (it.id == docId) {
                    it.copy(
                        name = title.trim().ifBlank { "未命名文档" },
                        updatedAt = System.currentTimeMillis()
                    )
                } else it
            }
        )
        persistTreeLocked(uri)
        store.writeDocumentBody(uri, docId, body)
    }

    suspend fun importImage(docId: String, sourceUri: Uri, mimeType: String?): String {
        val uri = currentUri() ?: error("未打开笔记库")
        return store.importImage(uri, docId, sourceUri, mimeType)
    }

    suspend fun resolveAssetUri(docId: String, relativePath: String): Uri? {
        val uri = currentUri() ?: return null
        return store.resolveAssetUri(uri, docId, relativePath)
    }

    fun childrenOf(tree: TreeIndex, parentId: String?) = store.childrenOf(tree, parentId)

    fun breadcrumbs(tree: TreeIndex, folderId: String?) = store.breadcrumbs(tree, folderId)

    private suspend fun ensureCacheLocked(): Uri {
        val uri = currentUri() ?: error("未打开笔记库")
        if (cachedUri != uri) {
            cachedUri = uri
            cachedTree = store.loadTree(uri)
        }
        return uri
    }

    private suspend fun persistTreeLocked(uri: Uri) {
        // Memory is source of truth for the current session.
        // Disk write can fail on some emulator SAF providers; keep UI updated anyway.
        try {
            store.saveTree(uri, cachedTree)
        } catch (error: Exception) {
            throw IllegalStateException(
                "已在列表中创建，但保存到库文件夹失败：${error.message}",
                error
            )
        }
    }

    private fun nextOrder(tree: TreeIndex, parentId: String?): Int {
        return (tree.nodes.filter { it.parentId == parentId }.maxOfOrNull { it.order } ?: -1) + 1
    }

    private fun collectDescendants(tree: TreeIndex, rootId: String): Set<String> {
        val byParent = tree.nodes.groupBy { it.parentId }
        val result = mutableSetOf<String>()
        val queue = ArrayDeque<String>().apply { add(rootId) }
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            byParent[id].orEmpty().forEach { child ->
                result += child.id
                queue += child.id
            }
        }
        return result
    }
}
