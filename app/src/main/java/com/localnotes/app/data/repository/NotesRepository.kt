package com.localnotes.app.data.repository

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.localnotes.app.data.model.IntegrityReport
import com.localnotes.app.data.model.LibraryMeta
import com.localnotes.app.data.model.NodeType
import com.localnotes.app.data.model.SearchHit
import com.localnotes.app.data.model.TreeIndex
import com.localnotes.app.data.model.TreeNode
import com.localnotes.app.data.prefs.LibraryPreferences
import com.localnotes.app.data.storage.SafLibraryStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

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
            // ignore
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

    suspend fun openLibrary(uri: Uri): Pair<LibraryMeta, IntegrityReport> = mutex.withLock {
        takePersistablePermission(uri)
        val meta = store.openLibrary(uri)
        cachedUri = uri
        cachedTree = store.loadTree(uri)
        val report = store.checkIntegrity(uri, cachedTree)
        meta to report
    }

    suspend fun reopenSavedLibrary(): Pair<LibraryMeta, IntegrityReport>? {
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
        mutateTree { tree, _ ->
            val node = TreeNode(
                id = store.newId(),
                type = NodeType.FOLDER,
                name = name.trim().ifBlank { "新建文件夹" },
                parentId = parentId,
                order = nextOrder(tree, parentId)
            )
            tree.copy(nodes = tree.nodes + node) to node
        }
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
        val previous = cachedTree
        cachedTree = cachedTree.copy(nodes = cachedTree.nodes + node)
        try {
            persistTreeLocked(uri)
            store.ensureDocumentFiles(uri, node.id)
            store.writeDocumentBody(uri, node.id, "")
        } catch (e: Exception) {
            cachedTree = previous
            throw e
        }
        node
    }

    suspend fun renameNode(nodeId: String, name: String) = mutex.withLock {
        mutateTree { tree, _ ->
            val updated = tree.copy(
                nodes = tree.nodes.map {
                    if (it.id == nodeId) {
                        it.copy(
                            name = name.trim().ifBlank { it.name },
                            updatedAt = System.currentTimeMillis()
                        )
                    } else it
                }
            )
            updated to Unit
        }
    }

    suspend fun toggleFavorite(nodeId: String) = mutex.withLock {
        mutateTree { tree, _ ->
            val updated = tree.copy(
                nodes = tree.nodes.map {
                    if (it.id == nodeId) it.copy(favorite = !it.favorite) else it
                }
            )
            updated to Unit
        }
    }

    /** Soft delete into trash. */
    suspend fun moveToTrash(nodeId: String) = mutex.withLock {
        mutateTree { tree, _ ->
            val now = System.currentTimeMillis()
            val ids = collectDescendants(tree, nodeId) + nodeId
            val updated = tree.copy(
                nodes = tree.nodes.map {
                    if (it.id in ids && !it.isDeleted) it.copy(deletedAt = now) else it
                }
            )
            updated to Unit
        }
    }

    suspend fun restoreFromTrash(nodeId: String) = mutex.withLock {
        mutateTree { tree, _ ->
            val node = tree.nodes.firstOrNull { it.id == nodeId } ?: error("项目不存在")
            val parentGone = node.parentId?.let { pid ->
                tree.nodes.none { it.id == pid && !it.isDeleted }
            } ?: false
            val updated = tree.copy(
                nodes = tree.nodes.map {
                    when {
                        it.id == nodeId -> it.copy(
                            deletedAt = null,
                            parentId = if (parentGone) null else it.parentId,
                            updatedAt = System.currentTimeMillis()
                        )
                        else -> it
                    }
                }
            )
            updated to Unit
        }
    }

    /** Permanently delete node (+ descendants) and document files on disk. */
    suspend fun purgeForever(nodeId: String) = mutex.withLock {
        val uri = ensureCacheLocked()
        val previous = cachedTree
        val ids = collectDescendants(cachedTree, nodeId) + nodeId
        val docIds = cachedTree.nodes
            .filter { it.id in ids && it.type == NodeType.DOCUMENT }
            .map { it.id }
        cachedTree = cachedTree.copy(nodes = cachedTree.nodes.filterNot { it.id in ids })
        try {
            persistTreeLocked(uri)
            docIds.forEach { store.deleteDocumentFiles(uri, it) }
        } catch (e: Exception) {
            cachedTree = previous
            throw e
        }
    }

    suspend fun emptyTrash() = mutex.withLock {
        val uri = ensureCacheLocked()
        val previous = cachedTree
        val trash = cachedTree.nodes.filter { it.isDeleted }
        val docIds = trash.filter { it.type == NodeType.DOCUMENT }.map { it.id }
        val trashIds = trash.map { it.id }.toSet()
        cachedTree = cachedTree.copy(nodes = cachedTree.nodes.filterNot { it.id in trashIds })
        try {
            persistTreeLocked(uri)
            docIds.forEach { store.deleteDocumentFiles(uri, it) }
        } catch (e: Exception) {
            cachedTree = previous
            throw e
        }
    }

    suspend fun readDocument(docId: String): Pair<TreeNode, String> = mutex.withLock {
        val uri = ensureCacheLocked()
        val node = cachedTree.nodes.firstOrNull {
            it.id == docId && it.type == NodeType.DOCUMENT && !it.isDeleted
        } ?: error("文档不存在或已在回收站")
        node to store.readDocumentBody(uri, docId)
    }

    suspend fun saveDocument(docId: String, title: String, body: String) = mutex.withLock {
        val uri = ensureCacheLocked()
        val previous = cachedTree
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
        try {
            persistTreeLocked(uri)
            store.writeDocumentBody(uri, docId, body)
        } catch (e: Exception) {
            cachedTree = previous
            throw e
        }
    }

    suspend fun importImage(docId: String, sourceUri: Uri, mimeType: String?): String =
        mutex.withLock {
            val uri = ensureCacheLocked()
            store.importImage(uri, docId, sourceUri, mimeType)
        }

    suspend fun resolveAssetUri(docId: String, relativePath: String): Uri? {
        val uri = currentUri() ?: return null
        return store.resolveAssetUri(uri, docId, relativePath)
    }

    suspend fun search(query: String): List<SearchHit> = mutex.withLock {
        val uri = ensureCacheLocked()
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val needle = q.lowercase()
        cachedTree.nodes
            .filter { !it.isDeleted }
            .mapNotNull { node ->
                val titleHit = node.name.lowercase().contains(needle)
                val body = if (node.type == NodeType.DOCUMENT) {
                    store.readDocumentBody(uri, node.id)
                } else ""
                val bodyHit = body.lowercase().contains(needle)
                if (!titleHit && !bodyHit) return@mapNotNull null
                val snippet = when {
                    titleHit && node.type == NodeType.FOLDER -> "文件夹"
                    titleHit -> "标题匹配"
                    else -> excerptAround(body, needle)
                }
                SearchHit(node, snippet)
            }
            .sortedByDescending { it.node.updatedAt }
    }

    suspend fun adoptOrphans(parentId: String?): IntegrityReport = mutex.withLock {
        val uri = ensureCacheLocked()
        val previous = cachedTree
        cachedTree = store.adoptOrphans(uri, cachedTree, parentId)
        try {
            persistTreeLocked(uri)
        } catch (e: Exception) {
            cachedTree = previous
            throw e
        }
        store.checkIntegrity(uri, cachedTree)
    }

    suspend fun checkIntegrity(): IntegrityReport = mutex.withLock {
        val uri = ensureCacheLocked()
        store.checkIntegrity(uri, cachedTree)
    }

    suspend fun exportLibraryZip(): File {
        val uri = currentUri() ?: error("未打开笔记库")
        val out = File(context.cacheDir, "localnotes-export-${System.currentTimeMillis()}.zip")
        return store.exportLibraryZip(uri, out)
    }

    suspend fun exportDocumentMarkdown(docId: String): File = mutex.withLock {
        val uri = ensureCacheLocked()
        val node = cachedTree.nodes.firstOrNull { it.id == docId }
            ?: error("文档不存在")
        store.exportDocumentMarkdown(uri, docId, node.name)
    }

    fun activeChildren(tree: TreeIndex, parentId: String?) = store.activeChildren(tree, parentId)

    fun trashNodes(tree: TreeIndex) = store.trashNodes(tree)

    fun breadcrumbs(tree: TreeIndex, folderId: String?) = store.breadcrumbs(tree, folderId)

    private suspend fun <T> mutateTree(block: suspend (TreeIndex, Uri) -> Pair<TreeIndex, T>): T {
        val uri = ensureCacheLocked()
        val previous = cachedTree
        val (next, result) = block(cachedTree, uri)
        cachedTree = next
        try {
            persistTreeLocked(uri)
        } catch (e: Exception) {
            cachedTree = previous
            throw e
        }
        return result
    }

    private suspend fun ensureCacheLocked(): Uri {
        val uri = currentUri() ?: error("未打开笔记库")
        if (cachedUri != uri) {
            cachedUri = uri
            cachedTree = store.loadTree(uri)
        }
        return uri
    }

    private suspend fun persistTreeLocked(uri: Uri) {
        store.saveTree(uri, cachedTree)
    }

    private fun nextOrder(tree: TreeIndex, parentId: String?): Int {
        return (tree.nodes.filter { !it.isDeleted && it.parentId == parentId }
            .maxOfOrNull { it.order } ?: -1) + 1
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

    private fun excerptAround(body: String, needle: String): String {
        val idx = body.lowercase().indexOf(needle)
        if (idx < 0) return body.take(40)
        val start = (idx - 20).coerceAtLeast(0)
        val end = (idx + needle.length + 20).coerceAtMost(body.length)
        return buildString {
            if (start > 0) append("…")
            append(body.substring(start, end).replace('\n', ' '))
            if (end < body.length) append("…")
        }
    }
}
