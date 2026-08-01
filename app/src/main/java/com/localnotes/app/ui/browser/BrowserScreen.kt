package com.localnotes.app.ui.browser

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localnotes.app.data.model.NodeType
import com.localnotes.app.data.model.SearchHit
import com.localnotes.app.data.model.TreeNode
import com.localnotes.app.ui.BrowserUiState
import com.localnotes.app.util.AppFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    state: BrowserUiState,
    onOpenFolder: (String?) -> Unit,
    onEditDocument: (String) -> Unit,
    onPreviewDocument: (String) -> Unit,
    onCreateFolder: (String) -> Unit,
    onCreateDocument: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onMoveToTrash: (String) -> Unit,
    onRestore: (String) -> Unit,
    onPurge: (String) -> Unit,
    onEmptyTrash: () -> Unit,
    onToggleTrash: () -> Unit,
    onSearch: (String) -> Unit,
    onExportLibrary: () -> Unit,
    onExportDocument: (String) -> Unit,
    onAdoptOrphans: () -> Unit,
    onDismissIntegrity: () -> Unit,
    onSwitchLibrary: () -> Unit,
    onDismissMessage: () -> Unit
) {
    var showNewFolder by remember { mutableStateOf(false) }
    var showNewDoc by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<TreeNode?>(null) }
    var deleting by remember { mutableStateOf<TreeNode?>(null) }
    var purging by remember { mutableStateOf<TreeNode?>(null) }
    var confirmEmptyTrash by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(state.library?.name ?: "本地笔记")
                        Text(
                            text = if (state.showTrash) "回收站" else "本地笔记库",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onToggleTrash) {
                        Icon(
                            if (state.showTrash) Icons.Default.Folder else Icons.Default.RestoreFromTrash,
                            contentDescription = "回收站"
                        )
                    }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "更多")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("导出整库 ZIP") },
                            leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onExportLibrary()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("切换笔记库") },
                            leadingIcon = { Icon(Icons.Default.SwapHoriz, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onSwitchLibrary()
                            }
                        )
                        if (state.showTrash && state.trash.isNotEmpty()) {
                            DropdownMenuItem(
                                text = { Text("清空回收站") },
                                leadingIcon = { Icon(Icons.Default.DeleteSweep, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    confirmEmptyTrash = true
                                }
                            )
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            if (!state.showTrash && state.searchQuery.isBlank()) {
                Row {
                    FloatingActionButton(
                        onClick = { showNewFolder = true },
                        modifier = Modifier.padding(end = 12.dp)
                    ) {
                        Icon(Icons.Default.CreateNewFolder, contentDescription = "新建文件夹")
                    }
                    FloatingActionButton(onClick = { showNewDoc = true }) {
                        Icon(Icons.Default.NoteAdd, contentDescription = "新建文档")
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = onSearch,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                singleLine = true,
                label = { Text("搜索标题或正文") },
                placeholder = { Text("输入关键词") }
            )

            if (!state.integrityMessage.isNullOrBlank()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = state.integrityMessage,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    TextButton(onClick = onAdoptOrphans) { Text("恢复") }
                    TextButton(onClick = onDismissIntegrity) { Text("忽略") }
                }
            }

            if (!state.showTrash && state.searchQuery.isBlank()) {
                BreadcrumbRow(crumbs = state.breadcrumbs, onClick = onOpenFolder)
            }

            when {
                state.searchQuery.isNotBlank() -> {
                    SearchList(
                        hits = state.searchResults,
                        onEdit = onEditDocument,
                        onPreview = onPreviewDocument,
                        onOpenFolder = onOpenFolder
                    )
                }

                state.showTrash -> {
                    if (state.trash.isEmpty()) {
                        EmptyHint("回收站为空")
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            items(state.trash, key = { it.id }) { node ->
                                TrashRow(
                                    node = node,
                                    onRestore = { onRestore(node.id) },
                                    onPurge = { purging = node }
                                )
                            }
                        }
                    }
                }

                state.children.isEmpty() -> EmptyHint("当前目录为空\n点右下角新建文件夹或文档")

                else -> {
                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(state.children, key = { it.id }) { node ->
                            NodeRow(
                                node = node,
                                onOpenFolder = { onOpenFolder(node.id) },
                                onEditDocument = { onEditDocument(node.id) },
                                onPreviewDocument = { onPreviewDocument(node.id) },
                                onRename = { renaming = node },
                                onToggleFavorite = { onToggleFavorite(node.id) },
                                onExport = { onExportDocument(node.id) },
                                onDelete = { deleting = node }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showNewFolder) {
        NameDialog("新建文件夹", "新建文件夹", { showNewFolder = false }) {
            onCreateFolder(it); showNewFolder = false
        }
    }
    if (showNewDoc) {
        NameDialog("新建文档", "未命名文档", { showNewDoc = false }) {
            onCreateDocument(it); showNewDoc = false
        }
    }
    renaming?.let { node ->
        NameDialog("重命名", node.name, { renaming = null }) {
            onRename(node.id, it); renaming = null
        }
    }
    deleting?.let { node ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("移入回收站") },
            text = { Text("「${node.name}」将移入回收站，可稍后恢复或永久删除。") },
            confirmButton = {
                TextButton(onClick = {
                    onMoveToTrash(node.id); deleting = null
                }) { Text("移入回收站") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } }
        )
    }
    purging?.let { node ->
        AlertDialog(
            onDismissRequest = { purging = null },
            title = { Text("永久删除") },
            text = { Text("将永久删除「${node.name}」及其磁盘文件，不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    onPurge(node.id); purging = null
                }) { Text("永久删除") }
            },
            dismissButton = { TextButton(onClick = { purging = null }) { Text("取消") } }
        )
    }
    if (confirmEmptyTrash) {
        AlertDialog(
            onDismissRequest = { confirmEmptyTrash = false },
            title = { Text("清空回收站") },
            text = { Text("将永久删除回收站内全部项目。") },
            confirmButton = {
                TextButton(onClick = {
                    onEmptyTrash(); confirmEmptyTrash = false
                }) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { confirmEmptyTrash = false }) { Text("取消") }
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

@Composable
private fun EmptyHint(text: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text)
    }
}

@Composable
private fun SearchList(
    hits: List<SearchHit>,
    onEdit: (String) -> Unit,
    onPreview: (String) -> Unit,
    onOpenFolder: (String?) -> Unit
) {
    if (hits.isEmpty()) {
        EmptyHint("无搜索结果")
        return
    }
    LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
        items(hits, key = { it.node.id }) { hit ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        if (hit.node.type == NodeType.FOLDER) onOpenFolder(hit.node.id)
                        else onEdit(hit.node.id)
                    }
                    .padding(12.dp)
            ) {
                Text(hit.node.name, fontWeight = FontWeight.SemiBold)
                Text(
                    hit.snippet,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                )
                if (hit.node.type == NodeType.DOCUMENT) {
                    Row {
                        TextButton(onClick = { onEdit(hit.node.id) }) { Text("编辑") }
                        TextButton(onClick = { onPreview(hit.node.id) }) { Text("预览") }
                    }
                }
            }
        }
    }
}

@Composable
private fun BreadcrumbRow(
    crumbs: List<com.localnotes.app.data.model.Breadcrumb>,
    onClick: (String?) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        crumbs.forEachIndexed { index, crumb ->
            Text(
                text = crumb.name,
                modifier = Modifier.clickable { onClick(crumb.id) },
                fontWeight = if (index == crumbs.lastIndex) FontWeight.Bold else FontWeight.Normal,
                color = if (index == crumbs.lastIndex) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                }
            )
            if (index != crumbs.lastIndex) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                )
            }
        }
    }
}

@Composable
private fun NodeRow(
    node: TreeNode,
    onOpenFolder: () -> Unit,
    onEditDocument: () -> Unit,
    onPreviewDocument: () -> Unit,
    onRename: () -> Unit,
    onToggleFavorite: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    val isFolder = node.type == NodeType.FOLDER
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = {
                if (isFolder) onOpenFolder() else onEditDocument()
            })
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (isFolder) Icons.Default.Folder else Icons.AutoMirrored.Filled.Article,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(end = 12.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (node.favorite) {
                    Icon(
                        Icons.Default.Star,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.padding(end = 4.dp)
                    )
                }
                Text(text = node.name, style = MaterialTheme.typography.bodyLarge)
            }
            Text(
                text = AppFormat.time(node.updatedAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
            )
        }
        if (!isFolder) {
            TextButton(onClick = onEditDocument) {
                Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                Text("编辑", fontSize = 13.sp)
            }
            TextButton(onClick = onPreviewDocument) {
                Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                Text("预览", fontSize = 13.sp)
            }
        }
        IconButton(onClick = { menuOpen = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = "更多")
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(if (node.favorite) "取消收藏" else "收藏") },
                leadingIcon = {
                    Icon(
                        if (node.favorite) Icons.Default.Star else Icons.Default.StarBorder,
                        contentDescription = null
                    )
                },
                onClick = { menuOpen = false; onToggleFavorite() }
            )
            DropdownMenuItem(
                text = { Text("重命名") },
                onClick = { menuOpen = false; onRename() }
            )
            if (!isFolder) {
                DropdownMenuItem(
                    text = { Text("导出 Markdown") },
                    leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                    onClick = { menuOpen = false; onExport() }
                )
            }
            DropdownMenuItem(
                text = { Text("移入回收站") },
                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                onClick = { menuOpen = false; onDelete() }
            )
        }
    }
}

@Composable
private fun TrashRow(
    node: TreeNode,
    onRestore: () -> Unit,
    onPurge: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(node.name)
            Text(
                text = "删除于 ${AppFormat.time(node.deletedAt ?: 0L)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
            )
        }
        TextButton(onClick = onRestore) { Text("恢复") }
        TextButton(onClick = onPurge) { Text("彻底删除") }
    }
}

@Composable
private fun NameDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(value) }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
