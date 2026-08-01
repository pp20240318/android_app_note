package com.localnotes.app.data.storage

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.localnotes.app.data.model.LibraryMeta
import com.localnotes.app.data.model.TreeIndex
import com.localnotes.app.data.model.TreeNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.UUID

/**
 * Portable note library on a user-chosen directory (SAF).
 *
 * Layout:
 *   library.json
 *   tree.json
 *   docs/{docId}/note.md
 *   docs/{docId}/assets/{file}
 */
class SafLibraryStore(
    private val context: Context,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }
) {
    private val resolver get() = context.contentResolver

    suspend fun createLibrary(treeUri: Uri, name: String): LibraryMeta = withContext(Dispatchers.IO) {
        val root = requireRoot(treeUri)
        val meta = LibraryMeta(name = name)
        writeText(root, LIBRARY_FILE, json.encodeToString(meta))
        writeText(root, TREE_FILE, json.encodeToString(TreeIndex()))
        ensureDir(root, DOCS_DIR)
        meta
    }

    suspend fun openLibrary(treeUri: Uri): LibraryMeta = withContext(Dispatchers.IO) {
        val root = requireRoot(treeUri)
        val existing = readText(root, LIBRARY_FILE)
        if (existing != null) {
            json.decodeFromString<LibraryMeta>(existing)
        } else {
            createLibrary(treeUri, "我的笔记库")
        }.also {
            if (readText(root, TREE_FILE) == null) {
                writeText(root, TREE_FILE, json.encodeToString(TreeIndex()))
            }
            ensureDir(root, DOCS_DIR)
        }
    }

    suspend fun loadTree(treeUri: Uri): TreeIndex = withContext(Dispatchers.IO) {
        val root = requireRoot(treeUri)
        val raw = readText(root, TREE_FILE) ?: return@withContext TreeIndex()
        json.decodeFromString<TreeIndex>(raw)
    }

    suspend fun saveTree(treeUri: Uri, tree: TreeIndex) = withContext(Dispatchers.IO) {
        val root = requireRoot(treeUri)
        writeText(root, TREE_FILE, json.encodeToString(tree))
    }

    suspend fun readDocumentBody(treeUri: Uri, docId: String): String = withContext(Dispatchers.IO) {
        val root = requireRoot(treeUri)
        val note = findDocFile(root, docId, NOTE_FILE) ?: return@withContext ""
        readFile(note) ?: ""
    }

    suspend fun writeDocumentBody(treeUri: Uri, docId: String, body: String) = withContext(Dispatchers.IO) {
        val root = requireRoot(treeUri)
        val docDir = ensureDocDir(root, docId)
        writeText(docDir, NOTE_FILE, body)
    }

    suspend fun ensureDocumentFiles(treeUri: Uri, docId: String) = withContext(Dispatchers.IO) {
        val root = requireRoot(treeUri)
        val docDir = ensureDocDir(root, docId)
        if (docDir.findFile(NOTE_FILE) == null) {
            writeText(docDir, NOTE_FILE, "")
        }
        ensureDir(docDir, ASSETS_DIR)
    }

    suspend fun importImage(
        treeUri: Uri,
        docId: String,
        sourceUri: Uri,
        mimeType: String?
    ): String = withContext(Dispatchers.IO) {
        val root = requireRoot(treeUri)
        val docDir = ensureDocDir(root, docId)
        val assets = ensureDir(docDir, ASSETS_DIR)
        val ext = extensionFor(mimeType, sourceUri)
        val fileName = "img_${System.currentTimeMillis()}$ext"
        val target = assets.createFile(mimeType ?: "image/*", fileName)
            ?: error("无法在库中创建图片文件")

        resolver.openInputStream(sourceUri)?.use { input ->
            resolver.openOutputStream(target.uri)?.use { output ->
                input.copyTo(output)
            } ?: error("无法写入图片")
        } ?: error("无法读取所选图片")

        "$ASSETS_DIR/$fileName"
    }

    suspend fun resolveAssetUri(treeUri: Uri, docId: String, relativePath: String): Uri? =
        withContext(Dispatchers.IO) {
            val root = requireRoot(treeUri)
            val docDir = root.findFile(DOCS_DIR)?.findFile(docId) ?: return@withContext null
            var current: DocumentFile = docDir
            relativePath.split('/').filter { it.isNotBlank() }.forEach { part ->
                current = current.findFile(part) ?: return@withContext null
            }
            current.uri
        }

    fun childrenOf(tree: TreeIndex, parentId: String?): List<TreeNode> {
        return tree.nodes
            .filter { it.parentId == parentId }
            .sortedWith(compareBy<TreeNode> { it.type.ordinal }.thenBy { it.order }.thenBy { it.name })
    }

    fun breadcrumbs(tree: TreeIndex, folderId: String?): List<com.localnotes.app.data.model.Breadcrumb> {
        val crumbs = mutableListOf(com.localnotes.app.data.model.Breadcrumb(null, "根目录"))
        if (folderId == null) return crumbs
        val byId = tree.nodes.associateBy { it.id }
        val stack = ArrayDeque<TreeNode>()
        var current = byId[folderId]
        while (current != null) {
            stack.addFirst(current)
            current = current.parentId?.let { byId[it] }
        }
        stack.forEach { crumbs += com.localnotes.app.data.model.Breadcrumb(it.id, it.name) }
        return crumbs
    }

    fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(16)

    private fun requireRoot(treeUri: Uri): DocumentFile {
        return DocumentFile.fromTreeUri(context, treeUri)
            ?: error("无法打开笔记库目录")
    }

    private fun ensureDir(parent: DocumentFile, name: String): DocumentFile {
        parent.findFile(name)?.let { if (it.isDirectory) return it }
        return parent.createDirectory(name) ?: error("无法创建目录: $name")
    }

    private fun ensureDocDir(root: DocumentFile, docId: String): DocumentFile {
        val docs = ensureDir(root, DOCS_DIR)
        return ensureDir(docs, docId)
    }

    private fun findDocFile(root: DocumentFile, docId: String, fileName: String): DocumentFile? {
        return root.findFile(DOCS_DIR)?.findFile(docId)?.findFile(fileName)
    }

    private fun writeText(dir: DocumentFile, fileName: String, content: String) {
        val mime = when {
            fileName.endsWith(".json") -> "application/json"
            fileName.endsWith(".md") -> "text/markdown"
            else -> "text/plain"
        }
        val outFile = findNamedFile(dir, fileName) ?: run {
            val baseName = fileName.substringBeforeLast('.')
            // Pass full fileName; some providers ignore extension from mime alone.
            dir.createFile(mime, fileName)
                ?: dir.createFile(mime, baseName)
                ?: error("无法创建文件: $fileName")
        }
        val bytes = content.toByteArray(Charsets.UTF_8)
        // "wt" is not supported by every DocumentProvider (common on emulators).
        val stream = resolver.openOutputStream(outFile.uri, "rwt")
            ?: resolver.openOutputStream(outFile.uri, "wt")
            ?: resolver.openOutputStream(outFile.uri, "w")
            ?: error("无法写入文件: $fileName")
        stream.use { output ->
            output.write(bytes)
            output.flush()
        }
    }

    private fun readText(dir: DocumentFile, fileName: String): String? {
        val file = findNamedFile(dir, fileName) ?: return null
        return readFile(file)
    }

    private fun findNamedFile(dir: DocumentFile, fileName: String): DocumentFile? {
        dir.findFile(fileName)?.let { return it }
        val baseName = fileName.substringBeforeLast('.')
        // DocumentFile.createFile may produce "tree", "tree.json", or "tree.json.json".
        return dir.listFiles().firstOrNull { file ->
            val name = file.name ?: return@firstOrNull false
            name == fileName ||
                name == baseName ||
                name == "$fileName.json" ||
                name.equals(fileName, ignoreCase = true)
        }
    }

    private fun readFile(file: DocumentFile): String? {
        return resolver.openInputStream(file.uri)?.use { input ->
            BufferedReader(InputStreamReader(input, Charsets.UTF_8)).readText()
        }
    }

    private fun extensionFor(mimeType: String?, uri: Uri): String {
        val fromMime = when (mimeType) {
            "image/png" -> ".png"
            "image/jpeg", "image/jpg" -> ".jpg"
            "image/webp" -> ".webp"
            "image/gif" -> ".gif"
            else -> null
        }
        if (fromMime != null) return fromMime
        val name = uri.lastPathSegment.orEmpty().lowercase()
        return when {
            name.endsWith(".png") -> ".png"
            name.endsWith(".webp") -> ".webp"
            name.endsWith(".gif") -> ".gif"
            else -> ".jpg"
        }
    }

    companion object {
        const val LIBRARY_FILE = "library.json"
        const val TREE_FILE = "tree.json"
        const val DOCS_DIR = "docs"
        const val ASSETS_DIR = "assets"
        const val NOTE_FILE = "note.md"
    }
}
