package com.localnotes.app.data.storage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.localnotes.app.data.model.CURRENT_SCHEMA_VERSION
import com.localnotes.app.data.model.IntegrityReport
import com.localnotes.app.data.model.LibraryMeta
import com.localnotes.app.data.model.NodeType
import com.localnotes.app.data.model.TreeIndex
import com.localnotes.app.data.model.TreeNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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

    suspend fun libraryExists(treeUri: Uri): Boolean = withContext(Dispatchers.IO) {
        val root = requireRoot(treeUri)
        findNamedFile(root, LIBRARY_FILE) != null || findNamedFile(root, TREE_FILE) != null
    }

    suspend fun createLibrary(treeUri: Uri, name: String): LibraryMeta = withContext(Dispatchers.IO) {
        val root = requireRoot(treeUri)
        if (findNamedFile(root, LIBRARY_FILE) != null || findNamedFile(root, TREE_FILE) != null) {
            error("该目录已有笔记库，请改用「打开已有笔记库」，避免覆盖数据")
        }
        val meta = LibraryMeta(name = name, schemaVersion = CURRENT_SCHEMA_VERSION)
        writeText(root, LIBRARY_FILE, json.encodeToString(meta))
        writeText(root, TREE_FILE, json.encodeToString(TreeIndex()))
        ensureDir(root, DOCS_DIR)
        meta
    }

    suspend fun openLibrary(treeUri: Uri): LibraryMeta = withContext(Dispatchers.IO) {
        val root = requireRoot(treeUri)
        val existing = readText(root, LIBRARY_FILE)
        val meta = if (existing != null) {
            json.decodeFromString<LibraryMeta>(existing)
        } else if (findNamedFile(root, TREE_FILE) != null) {
            // Recover meta if only tree.json remains.
            LibraryMeta(name = "我的笔记库", schemaVersion = CURRENT_SCHEMA_VERSION).also {
                writeText(root, LIBRARY_FILE, json.encodeToString(it))
            }
        } else {
            error("所选目录不是笔记库。请先「创建新笔记库」，或选择含 library.json 的目录")
        }
        if (readText(root, TREE_FILE) == null) {
            writeText(root, TREE_FILE, json.encodeToString(TreeIndex()))
        }
        ensureDir(root, DOCS_DIR)
        migrateMetaIfNeeded(root, meta)
    }

    suspend fun loadTree(treeUri: Uri): TreeIndex = withContext(Dispatchers.IO) {
        val root = requireRoot(treeUri)
        val raw = readText(root, TREE_FILE) ?: return@withContext TreeIndex()
        val tree = json.decodeFromString<TreeIndex>(raw)
        if (tree.schemaVersion < CURRENT_SCHEMA_VERSION) {
            val upgraded = tree.copy(schemaVersion = CURRENT_SCHEMA_VERSION)
            writeText(root, TREE_FILE, json.encodeToString(upgraded))
            upgraded
        } else tree
    }

    suspend fun saveTree(treeUri: Uri, tree: TreeIndex) = withContext(Dispatchers.IO) {
        val root = requireRoot(treeUri)
        writeText(root, TREE_FILE, json.encodeToString(tree.copy(schemaVersion = CURRENT_SCHEMA_VERSION)))
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
        if (findNamedFile(docDir, NOTE_FILE) == null) {
            writeText(docDir, NOTE_FILE, "")
        }
        ensureDir(docDir, ASSETS_DIR)
    }

    suspend fun deleteDocumentFiles(treeUri: Uri, docId: String) = withContext(Dispatchers.IO) {
        val root = requireRoot(treeUri)
        root.findFile(DOCS_DIR)?.findFile(docId)?.deleteRecursivelySafe()
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
        val fileName = "img_${System.currentTimeMillis()}.jpg"
        val target = assets.createFile("image/jpeg", fileName)
            ?: error("无法在库中创建图片文件")

        val compressed = compressImage(sourceUri)
        resolver.openOutputStream(target.uri)?.use { output ->
            output.write(compressed)
            output.flush()
        } ?: error("无法写入图片")

        "$ASSETS_DIR/$fileName"
    }

    suspend fun resolveAssetUri(treeUri: Uri, docId: String, relativePath: String): Uri? =
        withContext(Dispatchers.IO) {
            val root = requireRoot(treeUri)
            val docDir = root.findFile(DOCS_DIR)?.findFile(docId) ?: return@withContext null
            var current: DocumentFile = docDir
            relativePath.split('/').filter { it.isNotBlank() }.forEach { part ->
                current = current.findFile(part) ?: findNamedFile(current, part)
                    ?: return@withContext null
            }
            current.uri
        }

    suspend fun checkIntegrity(treeUri: Uri, tree: TreeIndex): IntegrityReport =
        withContext(Dispatchers.IO) {
            val root = requireRoot(treeUri)
            val docsDir = root.findFile(DOCS_DIR) ?: return@withContext IntegrityReport()
            val onDisk = docsDir.listFiles().filter { it.isDirectory }.mapNotNull { it.name }.toSet()
            val indexedDocs = tree.nodes
                .filter { it.type == NodeType.DOCUMENT }
                .map { it.id }
                .toSet()
            IntegrityReport(
                orphanDocDirs = (onDisk - indexedDocs).sorted(),
                missingDocDirs = (indexedDocs - onDisk).sorted()
            )
        }

    suspend fun adoptOrphans(treeUri: Uri, tree: TreeIndex, parentId: String?): TreeIndex =
        withContext(Dispatchers.IO) {
            val report = checkIntegrity(treeUri, tree)
            if (report.orphanDocDirs.isEmpty()) return@withContext tree
            var order = (tree.nodes.filter { it.parentId == parentId }.maxOfOrNull { it.order } ?: -1) + 1
            val extras = report.orphanDocDirs.map { docId ->
                TreeNode(
                    id = docId,
                    type = NodeType.DOCUMENT,
                    name = "恢复文档-$docId",
                    parentId = parentId,
                    order = order++
                )
            }
            tree.copy(nodes = tree.nodes + extras)
        }

    suspend fun exportLibraryZip(treeUri: Uri, outFile: File): File = withContext(Dispatchers.IO) {
        val root = requireRoot(treeUri)
        ZipOutputStream(BufferedOutputStream(FileOutputStream(outFile))).use { zip ->
            fun addFile(path: String, file: DocumentFile) {
                if (file.isDirectory) {
                    file.listFiles().forEach { child ->
                        val childName = child.name ?: return@forEach
                        val childPath = if (path.isEmpty()) childName else "$path/$childName"
                        addFile(childPath, child)
                    }
                } else {
                    zip.putNextEntry(ZipEntry(path))
                    resolver.openInputStream(file.uri)?.use { input ->
                        input.copyTo(zip)
                    }
                    zip.closeEntry()
                }
            }
            root.listFiles().forEach { child ->
                val name = child.name ?: return@forEach
                addFile(name, child)
            }
        }
        outFile
    }

    suspend fun exportDocumentMarkdown(treeUri: Uri, docId: String, title: String): File =
        withContext(Dispatchers.IO) {
            val body = readDocumentBody(treeUri, docId)
            val safe = title.replace(Regex("""[\\/:*?"<>|]"""), "_").ifBlank { docId }
            val file = File(context.cacheDir, "$safe.md")
            file.writeText("# $title\n\n$body", Charsets.UTF_8)
            file
        }

    fun activeChildren(tree: TreeIndex, parentId: String?): List<TreeNode> {
        return tree.nodes
            .filter { !it.isDeleted && it.parentId == parentId }
            .sortedWith(
                compareByDescending<TreeNode> { it.favorite }
                    .thenBy { it.type.ordinal }
                    .thenBy { it.order }
                    .thenBy { it.name }
            )
    }

    fun trashNodes(tree: TreeIndex): List<TreeNode> {
        return tree.nodes
            .filter { it.isDeleted }
            .sortedByDescending { it.deletedAt ?: 0L }
    }

    fun breadcrumbs(tree: TreeIndex, folderId: String?): List<com.localnotes.app.data.model.Breadcrumb> {
        val crumbs = mutableListOf(com.localnotes.app.data.model.Breadcrumb(null, "根目录"))
        if (folderId == null) return crumbs
        val byId = tree.nodes.filter { !it.isDeleted }.associateBy { it.id }
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

    private fun migrateMetaIfNeeded(root: DocumentFile, meta: LibraryMeta): LibraryMeta {
        if (meta.schemaVersion >= CURRENT_SCHEMA_VERSION) return meta
        val upgraded = meta.copy(schemaVersion = CURRENT_SCHEMA_VERSION)
        writeText(root, LIBRARY_FILE, json.encodeToString(upgraded))
        return upgraded
    }

    private fun compressImage(sourceUri: Uri): ByteArray {
        val original = resolver.openInputStream(sourceUri)?.use { input ->
            BitmapFactory.decodeStream(BufferedInputStream(input))
        } ?: error("无法解码图片")
        val maxSide = 1920
        val w = original.width
        val h = original.height
        val scaled = if (w <= maxSide && h <= maxSide) {
            original
        } else {
            val ratio = maxSide.toFloat() / maxOf(w, h)
            Bitmap.createScaledBitmap(
                original,
                (w * ratio).toInt().coerceAtLeast(1),
                (h * ratio).toInt().coerceAtLeast(1),
                true
            ).also {
                if (it !== original) original.recycle()
            }
        }
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
        if (scaled !== original) scaled.recycle() else original.recycle()
        return out.toByteArray()
    }

    private fun requireRoot(treeUri: Uri): DocumentFile {
        return DocumentFile.fromTreeUri(context, treeUri)
            ?: error("无法打开笔记库目录")
    }

    private fun ensureDir(parent: DocumentFile, name: String): DocumentFile {
        parent.findFile(name)?.let { if (it.isDirectory) return it }
        findNamedFile(parent, name)?.let { if (it.isDirectory) return it }
        return parent.createDirectory(name) ?: error("无法创建目录: $name")
    }

    private fun ensureDocDir(root: DocumentFile, docId: String): DocumentFile {
        val docs = ensureDir(root, DOCS_DIR)
        return ensureDir(docs, docId)
    }

    private fun findDocFile(root: DocumentFile, docId: String, fileName: String): DocumentFile? {
        val docDir = root.findFile(DOCS_DIR)?.findFile(docId) ?: return null
        return findNamedFile(docDir, fileName)
    }

    /**
     * Reliable truncate write: delete existing target then create fresh file.
     */
    private fun writeText(dir: DocumentFile, fileName: String, content: String) {
        val mime = when {
            fileName.endsWith(".json") -> "application/json"
            fileName.endsWith(".md") -> "text/markdown"
            else -> "text/plain"
        }
        val bytes = content.toByteArray(Charsets.UTF_8)
        val baseName = fileName.substringBeforeLast('.')

        // Remove ambiguous legacy names so we don't append into stale files.
        dir.listFiles().forEach { file ->
            val name = file.name ?: return@forEach
            if (
                name == fileName ||
                name == baseName ||
                name == "$fileName.json" ||
                name.equals(fileName, ignoreCase = true)
            ) {
                file.delete()
            }
        }

        val outFile = dir.createFile(mime, baseName)
            ?: dir.createFile(mime, fileName)
            ?: error("无法创建文件: $fileName")

        val stream = resolver.openOutputStream(outFile.uri, "w")
            ?: resolver.openOutputStream(outFile.uri)
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

    private fun DocumentFile.deleteRecursivelySafe(): Boolean {
        if (isDirectory) {
            listFiles().forEach { it.deleteRecursivelySafe() }
        }
        return delete()
    }

    companion object {
        const val LIBRARY_FILE = "library.json"
        const val TREE_FILE = "tree.json"
        const val DOCS_DIR = "docs"
        const val ASSETS_DIR = "assets"
        const val NOTE_FILE = "note.md"
    }
}
