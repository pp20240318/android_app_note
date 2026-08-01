package com.localnotes.app.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.localnotes.app.NotesApplication
import com.localnotes.app.data.model.Breadcrumb
import com.localnotes.app.data.model.LibraryMeta
import com.localnotes.app.data.model.TreeNode
import com.localnotes.app.data.repository.NotesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BrowserUiState(
    val ready: Boolean = false,
    val library: LibraryMeta? = null,
    val currentFolderId: String? = null,
    val breadcrumbs: List<Breadcrumb> = listOf(Breadcrumb(null, "根目录")),
    val children: List<TreeNode> = emptyList(),
    val message: String? = null,
    val busy: Boolean = false
)

data class EditorUiState(
    val docId: String = "",
    val title: String = "",
    val body: String = "",
    val dirty: Boolean = false,
    val saving: Boolean = false,
    val message: String? = null
)

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: NotesRepository =
        (application as NotesApplication).repository

    private val _browser = MutableStateFlow(BrowserUiState())
    val browser: StateFlow<BrowserUiState> = _browser.asStateFlow()

    private val _editor = MutableStateFlow(EditorUiState())
    val editor: StateFlow<EditorUiState> = _editor.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                val meta = repository.reopenSavedLibrary()
                if (meta != null) {
                    _browser.update { it.copy(library = meta) }
                    refreshBrowser()
                }
            } catch (e: Exception) {
                _browser.update {
                    it.copy(message = "无法打开上次的笔记库：${e.message}", ready = true)
                }
                return@launch
            }
            _browser.update { it.copy(ready = true) }
        }
    }

    fun createLibrary(uri: Uri, name: String) {
        viewModelScope.launch {
            runCatching {
                _browser.update { it.copy(busy = true, message = null) }
                val meta = repository.createLibrary(uri, name)
                _browser.update { it.copy(library = meta, currentFolderId = null) }
                refreshBrowser()
            }.onFailure { e ->
                _browser.update { it.copy(message = e.message ?: "创建失败") }
            }
            _browser.update { it.copy(busy = false, ready = true) }
        }
    }

    fun openLibrary(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                _browser.update { it.copy(busy = true, message = null) }
                val meta = repository.openLibrary(uri)
                _browser.update { it.copy(library = meta, currentFolderId = null) }
                refreshBrowser()
            }.onFailure { e ->
                _browser.update { it.copy(message = e.message ?: "打开失败") }
            }
            _browser.update { it.copy(busy = false, ready = true) }
        }
    }

    fun switchLibrary() {
        viewModelScope.launch {
            repository.clearLibrary()
            _browser.value = BrowserUiState(ready = true)
        }
    }

    fun openFolder(folderId: String?) {
        viewModelScope.launch {
            _browser.update { it.copy(currentFolderId = folderId) }
            refreshBrowser()
        }
    }

    fun createFolder(name: String) {
        viewModelScope.launch {
            val result = runCatching {
                repository.createFolder(_browser.value.currentFolderId, name)
            }
            // Always refresh from in-memory tree so the new folder appears immediately.
            runCatching { refreshBrowser() }
            result.onFailure { e ->
                _browser.update { it.copy(message = e.message ?: "新建文件夹失败") }
            }
        }
    }

    fun createDocument(title: String, onCreated: (String) -> Unit) {
        viewModelScope.launch {
            val result = runCatching {
                repository.createDocument(_browser.value.currentFolderId, title)
            }
            runCatching { refreshBrowser() }
            result.onSuccess { node -> onCreated(node.id) }
            result.onFailure { e ->
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

    fun deleteNode(nodeId: String) {
        viewModelScope.launch {
            runCatching {
                repository.deleteNode(nodeId)
                refreshBrowser()
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
        viewModelScope.launch {
            runCatching {
                val (node, body) = repository.readDocument(docId)
                _editor.value = EditorUiState(
                    docId = node.id,
                    title = node.name,
                    body = body,
                    dirty = false
                )
            }.onFailure { e ->
                _editor.update { it.copy(message = e.message ?: "打开文档失败") }
            }
        }
    }

    fun updateTitle(title: String) {
        _editor.update { it.copy(title = title, dirty = true) }
    }

    fun updateBody(body: String) {
        _editor.update { it.copy(body = body, dirty = true) }
    }

    fun insertMarkdown(snippet: String, cursorHint: Int? = null) {
        _editor.update { state ->
            val body = state.body
            val insertAt = cursorHint?.coerceIn(0, body.length) ?: body.length
            val newBody = body.substring(0, insertAt) + snippet + body.substring(insertAt)
            state.copy(body = newBody, dirty = true)
        }
    }

    fun saveDocument(onSaved: (() -> Unit)? = null) {
        viewModelScope.launch {
            val state = _editor.value
            runCatching {
                _editor.update { it.copy(saving = true, message = null) }
                repository.saveDocument(state.docId, state.title, state.body)
                _editor.update { it.copy(dirty = false, saving = false) }
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

    private suspend fun refreshBrowser() {
        val tree = repository.loadTree()
        val folderId = _browser.value.currentFolderId
        val children = repository.childrenOf(tree, folderId)
        val crumbs = repository.breadcrumbs(tree, folderId)
        _browser.update {
            it.copy(
                children = children,
                breadcrumbs = crumbs,
                ready = true
            )
        }
    }
}
