package com.localnotes.app.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.localnotes.app.NotesApplication
import com.localnotes.app.data.model.Breadcrumb
import com.localnotes.app.data.model.LibraryMeta
import com.localnotes.app.data.model.SearchHit
import com.localnotes.app.data.model.TreeNode
import com.localnotes.app.data.repository.NotesRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class BrowserUiState(
    val ready: Boolean = false,
    val loading: Boolean = true,
    val library: LibraryMeta? = null,
    val currentFolderId: String? = null,
    val breadcrumbs: List<Breadcrumb> = listOf(Breadcrumb(null, "根目录")),
    val children: List<TreeNode> = emptyList(),
    val trash: List<TreeNode> = emptyList(),
    val showTrash: Boolean = false,
    val searchQuery: String = "",
    val searchResults: List<SearchHit> = emptyList(),
    val integrityMessage: String? = null,
    val message: String? = null,
    val busy: Boolean = false
)

data class EditorUiState(
    val docId: String = "",
    val title: String = "",
    val body: String = "",
    val cursor: Int = 0,
    val dirty: Boolean = false,
    val saving: Boolean = false,
    val autoSaved: Boolean = false,
    val message: String? = null
)

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: NotesRepository =
        (application as NotesApplication).repository

    private val _browser = MutableStateFlow(BrowserUiState())
    val browser: StateFlow<BrowserUiState> = _browser.asStateFlow()

    private val _editor = MutableStateFlow(EditorUiState())
    val editor: StateFlow<EditorUiState> = _editor.asStateFlow()

    private var autoSaveJob: Job? = null
    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            _browser.update { it.copy(loading = true, ready = false) }
            try {
                val opened = repository.reopenSavedLibrary()
                if (opened != null) {
                    val (meta, report) = opened
                    _browser.update {
                        it.copy(
                            library = meta,
                            integrityMessage = report.takeIf { r -> r.hasIssues }?.summary()
                        )
                    }
                    refreshBrowser()
                }
            } catch (e: Exception) {
                repository.clearLibrary()
                _browser.update {
                    it.copy(
                        message = "无法打开上次的笔记库，请重新选择：${e.message}",
                        library = null
                    )
                }
            } finally {
                _browser.update { it.copy(ready = true, loading = false) }
            }
        }
    }

    fun createLibrary(uri: Uri, name: String) {
        viewModelScope.launch {
            runCatching {
                _browser.update { it.copy(busy = true, message = null) }
                val meta = repository.createLibrary(uri, name)
                _browser.update {
                    it.copy(
                        library = meta,
                        currentFolderId = null,
                        showTrash = false,
                        integrityMessage = null
                    )
                }
                refreshBrowser()
            }.onFailure { e ->
                _browser.update { it.copy(message = e.message ?: "创建失败") }
            }
            _browser.update { it.copy(busy = false, ready = true, loading = false) }
        }
    }

    fun openLibrary(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                _browser.update { it.copy(busy = true, message = null) }
                val (meta, report) = repository.openLibrary(uri)
                _browser.update {
                    it.copy(
                        library = meta,
                        currentFolderId = null,
                        showTrash = false,
                        integrityMessage = report.takeIf { r -> r.hasIssues }?.summary()
                    )
                }
                refreshBrowser()
            }.onFailure { e ->
                _browser.update { it.copy(message = e.message ?: "打开失败") }
            }
            _browser.update { it.copy(busy = false, ready = true, loading = false) }
        }
    }

    fun adoptOrphans() {
        viewModelScope.launch {
            runCatching {
                val report = repository.adoptOrphans(_browser.value.currentFolderId)
                _browser.update {
                    it.copy(
                        integrityMessage = if (report.hasIssues) report.summary() else null,
                        message = "已将未索引文档恢复到当前目录"
                    )
                }
                refreshBrowser()
            }.onFailure { e ->
                _browser.update { it.copy(message = e.message) }
            }
        }
    }

    fun dismissIntegrity() {
        _browser.update { it.copy(integrityMessage = null) }
    }

    fun switchLibrary() {
        viewModelScope.launch {
            repository.clearLibrary()
            _browser.value = BrowserUiState(ready = true, loading = false)
        }
    }

    fun openFolder(folderId: String?) {
        viewModelScope.launch {
            _browser.update {
                it.copy(currentFolderId = folderId, showTrash = false, searchQuery = "", searchResults = emptyList())
            }
            refreshBrowser()
        }
    }

    fun toggleTrashView() {
        viewModelScope.launch {
            _browser.update {
                it.copy(
                    showTrash = !it.showTrash,
                    searchQuery = "",
                    searchResults = emptyList()
                )
            }
            refreshBrowser()
        }
    }

    fun setSearchQuery(query: String) {
        _browser.update { it.copy(searchQuery = query) }
        searchJob?.cancel()
        if (query.isBlank()) {
            _browser.update { it.copy(searchResults = emptyList()) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(250)
            runCatching {
                val hits = repository.search(query)
                _browser.update { it.copy(searchResults = hits, showTrash = false) }
            }.onFailure { e ->
                _browser.update { it.copy(message = e.message) }
            }
        }
    }

    fun createFolder(name: String) {
        viewModelScope.launch {
            runCatching {
                repository.createFolder(_browser.value.currentFolderId, name)
                refreshBrowser()
            }.onFailure { e ->
                _browser.update { it.copy(message = e.message ?: "新建文件夹失败") }
            }
        }
    }

    fun createDocument(title: String, onCreated: (String) -> Unit) {
        viewModelScope.launch {
            runCatching {
                val node = repository.createDocument(_browser.value.currentFolderId, title)
                refreshBrowser()
                onCreated(node.id)
            }.onFailure { e ->
                _browser.update { it.copy(message = e.message ?: "新建文档失败") }
            }
        }
    }

    fun renameNode(nodeId: String, name: String) {
        viewModelScope.launch {
            runCatching {
                repository.renameNode(nodeId, name)
                refreshBrowser()
            }.onFailure { e ->
                _browser.update { it.copy(message = e.message) }
            }
        }
    }

    fun toggleFavorite(nodeId: String) {
        viewModelScope.launch {
            runCatching {
                repository.toggleFavorite(nodeId)
                refreshBrowser()
            }.onFailure { e ->
                _browser.update { it.copy(message = e.message) }
            }
        }
    }

    fun moveToTrash(nodeId: String) {
        viewModelScope.launch {
            runCatching {
                repository.moveToTrash(nodeId)
                refreshBrowser()
            }.onFailure { e ->
                _browser.update { it.copy(message = e.message) }
            }
        }
    }

    fun restoreFromTrash(nodeId: String) {
        viewModelScope.launch {
            runCatching {
                repository.restoreFromTrash(nodeId)
                refreshBrowser()
            }.onFailure { e ->
                _browser.update { it.copy(message = e.message) }
            }
        }
    }

    fun purgeForever(nodeId: String) {
        viewModelScope.launch {
            runCatching {
                repository.purgeForever(nodeId)
                refreshBrowser()
            }.onFailure { e ->
                _browser.update { it.copy(message = e.message) }
            }
        }
    }

    fun emptyTrash() {
        viewModelScope.launch {
            runCatching {
                repository.emptyTrash()
                refreshBrowser()
                _browser.update { it.copy(message = "回收站已清空") }
            }.onFailure { e ->
                _browser.update { it.copy(message = e.message) }
            }
        }
    }

    fun clearMessage() {
        _browser.update { it.copy(message = null) }
        _editor.update { it.copy(message = null) }
    }

    fun loadDocument(docId: String) {
        autoSaveJob?.cancel()
        viewModelScope.launch {
            runCatching {
                val (node, body) = repository.readDocument(docId)
                _editor.value = EditorUiState(
                    docId = node.id,
                    title = node.name,
                    body = body,
                    cursor = body.length,
                    dirty = false
                )
            }.onFailure { e ->
                _editor.update { it.copy(message = e.message ?: "打开文档失败") }
            }
        }
    }

    fun updateTitle(title: String) {
        _editor.update { it.copy(title = title, dirty = true, autoSaved = false) }
        scheduleAutoSave()
    }

    fun updateBody(body: String, cursor: Int = body.length) {
        _editor.update {
            it.copy(body = body, cursor = cursor.coerceIn(0, body.length), dirty = true, autoSaved = false)
        }
        scheduleAutoSave()
    }

    fun updateCursor(cursor: Int) {
        _editor.update { state ->
            state.copy(cursor = cursor.coerceIn(0, state.body.length))
        }
    }

    fun insertMarkdown(snippet: String) {
        _editor.update { state ->
            val insertAt = state.cursor.coerceIn(0, state.body.length)
            val newBody = state.body.substring(0, insertAt) + snippet + state.body.substring(insertAt)
            state.copy(
                body = newBody,
                cursor = insertAt + snippet.length,
                dirty = true,
                autoSaved = false
            )
        }
        scheduleAutoSave()
    }

    fun saveDocument(onSaved: (() -> Unit)? = null) {
        autoSaveJob?.cancel()
        viewModelScope.launch {
            val state = _editor.value
            runCatching {
                _editor.update { it.copy(saving = true, message = null) }
                repository.saveDocument(state.docId, state.title, state.body)
                _editor.update { it.copy(dirty = false, saving = false, autoSaved = true) }
                refreshBrowser()
                onSaved?.invoke()
            }.onFailure { e ->
                _editor.update { it.copy(saving = false, message = e.message ?: "保存失败") }
            }
        }
    }

    fun importImage(uri: Uri, mimeType: String?, onInserted: (String) -> Unit) {
        viewModelScope.launch {
            val docId = _editor.value.docId
            runCatching {
                val relative = repository.importImage(docId, uri, mimeType)
                val markdown = "\n![image]($relative)\n"
                insertMarkdown(markdown)
                onInserted(markdown)
            }.onFailure { e ->
                _editor.update { it.copy(message = e.message ?: "插入图片失败") }
            }
        }
    }

    suspend fun resolveAssetUri(docId: String, relativePath: String): Uri? {
        return repository.resolveAssetUri(docId, relativePath)
    }

    suspend fun exportLibraryZip(): File = repository.exportLibraryZip()

    suspend fun exportCurrentDocument(): File {
        val docId = _editor.value.docId
        if (_editor.value.dirty) {
            repository.saveDocument(docId, _editor.value.title, _editor.value.body)
            _editor.update { it.copy(dirty = false, autoSaved = true) }
        }
        return repository.exportDocumentMarkdown(docId)
    }

    suspend fun exportDocument(docId: String): File = repository.exportDocumentMarkdown(docId)

    private fun scheduleAutoSave() {
        autoSaveJob?.cancel()
        autoSaveJob = viewModelScope.launch {
            delay(1200)
            val state = _editor.value
            if (!state.dirty || state.docId.isBlank()) return@launch
            runCatching {
                _editor.update { it.copy(saving = true) }
                repository.saveDocument(state.docId, state.title, state.body)
                _editor.update { it.copy(dirty = false, saving = false, autoSaved = true) }
                refreshBrowser()
            }.onFailure { e ->
                _editor.update { it.copy(saving = false, message = e.message ?: "自动保存失败") }
            }
        }
    }

    private suspend fun refreshBrowser() {
        val tree = repository.loadTree()
        val folderId = _browser.value.currentFolderId
        val children = repository.activeChildren(tree, folderId)
        val trash = repository.trashNodes(tree)
        val crumbs = repository.breadcrumbs(tree, folderId)
        _browser.update {
            it.copy(
                children = children,
                trash = trash,
                breadcrumbs = crumbs,
                ready = true,
                loading = false
            )
        }
    }
}
