package com.localnotes.app.ui.editor

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Title
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.localnotes.app.ui.EditorUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    state: EditorUiState,
    initialPreviewMode: Boolean = false,
    onTitleChange: (String) -> Unit,
    onBodyChange: (String, Int) -> Unit,
    onCursorChange: (Int) -> Unit,
    onInsertSnippet: (String) -> Unit,
    onImportImage: (Uri, String?) -> Unit,
    resolveImage: suspend (relativePath: String) -> Uri?,
    onSave: () -> Unit,
    onSaveAndBack: () -> Unit,
    onExport: () -> Unit,
    onBack: () -> Unit,
    onDismissMessage: () -> Unit
) {
    var pendingBack by remember { mutableStateOf(false) }
    var previewMode by remember(initialPreviewMode, state.docId) {
        mutableStateOf(initialPreviewMode)
    }
    var bodyField by remember(state.docId) {
        mutableStateOf(TextFieldValue(state.body, TextRange(state.cursor)))
    }

    LaunchedEffect(state.body, state.docId) {
        if (bodyField.text != state.body) {
            bodyField = TextFieldValue(
                state.body,
                TextRange(state.cursor.coerceIn(0, state.body.length))
            )
        }
    }

    val pickImage = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            onImportImage(uri, null)
            previewMode = true
        }
    }

    fun tryBack() {
        if (state.dirty) pendingBack = true else onBack()
    }

    BackHandler(onBack = { tryBack() })

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            when {
                                previewMode && state.dirty -> "预览*"
                                previewMode -> "预览"
                                state.dirty -> "编辑中*"
                                state.autoSaved -> "已自动保存"
                                else -> "编辑文档"
                            }
                        )
                        if (state.saving) {
                            Text(
                                "保存中…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { tryBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = onExport) {
                        Icon(Icons.Default.Share, contentDescription = "导出")
                    }
                    IconButton(onClick = { previewMode = !previewMode }) {
                        Icon(
                            if (previewMode) Icons.Default.Edit else Icons.Default.Visibility,
                            contentDescription = "切换模式"
                        )
                    }
                    IconButton(onClick = onSave, enabled = !state.saving) {
                        Icon(Icons.Default.Save, contentDescription = "保存")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            OutlinedTextField(
                value = state.title,
                onValueChange = onTitleChange,
                label = { Text("标题") },
                singleLine = true,
                enabled = !previewMode,
                modifier = Modifier.fillMaxWidth()
            )

            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                FilterChip(
                    selected = !previewMode,
                    onClick = { previewMode = false },
                    label = { Text("编辑") },
                    leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                    modifier = Modifier.padding(end = 8.dp)
                )
                FilterChip(
                    selected = previewMode,
                    onClick = { previewMode = true },
                    label = { Text("预览") },
                    leadingIcon = { Icon(Icons.Default.Visibility, contentDescription = null) }
                )
            }

            if (!previewMode) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(vertical = 8.dp)
                ) {
                    IconButton(onClick = { onInsertSnippet("\n## 标题\n") }) {
                        Icon(Icons.Default.Title, contentDescription = "标题")
                    }
                    IconButton(onClick = { onInsertSnippet("**加粗**") }) {
                        Icon(Icons.Default.FormatBold, contentDescription = "加粗")
                    }
                    IconButton(onClick = { onInsertSnippet("*斜体*") }) {
                        Icon(Icons.Default.FormatItalic, contentDescription = "斜体")
                    }
                    IconButton(onClick = { onInsertSnippet("\n- 列表项\n") }) {
                        Icon(Icons.Default.FormatListBulleted, contentDescription = "无序列表")
                    }
                    IconButton(onClick = { onInsertSnippet("\n1. 列表项\n") }) {
                        Icon(Icons.Default.FormatListNumbered, contentDescription = "有序列表")
                    }
                    IconButton(onClick = { onInsertSnippet("[链接文字](https://)") }) {
                        Icon(Icons.Default.Link, contentDescription = "链接")
                    }
                    IconButton(onClick = { onInsertSnippet("\n```\n代码\n```\n") }) {
                        Icon(Icons.Default.Code, contentDescription = "代码块")
                    }
                    IconButton(onClick = { pickImage.launch(arrayOf("image/*")) }) {
                        Icon(Icons.Default.Image, contentDescription = "插入图片")
                    }
                }

                Text(
                    text = "支持自动保存；工具栏在光标处插入。点「预览」查看排版与图片。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                OutlinedTextField(
                    value = bodyField,
                    onValueChange = { value ->
                        bodyField = value
                        onBodyChange(value.text, value.selection.start)
                        onCursorChange(value.selection.start)
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 12.dp)
                        .verticalScroll(rememberScrollState()),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                    placeholder = { Text("开始记录…") }
                )
            } else {
                Text(
                    text = "预览：标题 / 列表 / 加粗斜体 / 链接 / 代码块 / 图片",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(vertical = 8.dp)
                )
                MarkdownPreview(
                    body = state.body,
                    resolveImage = resolveImage,
                    modifier = Modifier.fillMaxSize().padding(bottom = 12.dp)
                )
            }
        }
    }

    if (pendingBack) {
        AlertDialog(
            onDismissRequest = { pendingBack = false },
            title = { Text("尚未保存") },
            text = { Text("要先保存再离开吗？") },
            confirmButton = {
                TextButton(onClick = {
                    pendingBack = false
                    onSaveAndBack()
                }) { Text("保存并返回") }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingBack = false
                    onBack()
                }) { Text("不保存") }
            }
        )
    }

    if (!state.message.isNullOrBlank()) {
        AlertDialog(
            onDismissRequest = onDismissMessage,
            title = { Text("提示") },
            text = { Text(state.message) },
            confirmButton = {
                TextButton(onClick = onDismissMessage) { Text("好的") }
            }
        )
    }
}
