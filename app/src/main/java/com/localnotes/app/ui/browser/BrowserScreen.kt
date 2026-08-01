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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NoteAdd
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
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.localnotes.app.data.model.NodeType
import com.localnotes.app.data.model.TreeNode
import com.localnotes.app.ui.BrowserUiState

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
    onDelete: (String) -> Unit,
    onSwitchLibrary: () -> Unit,
    onDismissMessage: () -> Unit
) {
    var showNewFolder by remember { mutableStateOf(false) }
    var showNewDoc by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<TreeNode?>(null) }
    var deleting by remember { mutableStateOf<TreeNode?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(state.library?.name ?: "本地笔记")
                        Text(
                            text = "虚拟目录树 · 文件在库文件夹内",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onSwitchLibrary) {
                        Icon(Icons.Default.SwapHoriz, contentDescription = "切换笔记库")
                    }
                }
            )
        },
        floatingActionButton = {
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
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            BreadcrumbRow(
                crumbs = state.breadcrumbs,
                onClick = onOpenFolder
            )
            if (state.children.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("当前目录为空")
                    Text(
                        text = "点右下角新建文件夹或文档",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            } else {
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
                            onDelete = { deleting = node }
                        )
                    }
                }
            }
        }
    }

    if (showNewFolder) {
        NameDialog(
            title = "新建文件夹",
            initial = "新建文件夹",
            onDismiss = { showNewFolder = false },
            onConfirm = {
                onCreateFolder(it)
                showNewFolder = false
            }
        )
    }
    if (showNewDoc) {
        NameDialog(
            title = "新建文档",
            initial = "未命名文档",
            onDismiss = { showNewDoc = false },
            onConfirm = {
                onCreateDocument(it)
                showNewDoc = false
            }
        )
    }
    renaming?.let { node ->
        NameDialog(
            title = "重命名",
            initial = node.name,
            onDismiss = { renaming = null },
            onConfirm = {
                onRename(node.id, it)
                renaming = null
            }
        )
    }
    deleting?.let { node ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除确认") },
            text = {
                Text(
                    if (node.type == NodeType.FOLDER) {
                        "将删除文件夹「${node.name}」及其子项（索引删除，磁盘文件暂保留）。"
                    } else {
                        "将删除文档「${node.name}」（索引删除，磁盘文件暂保留）。"
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(node.id)
                    deleting = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("取消") }
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
            imageVector = if (isFolder) {
                Icons.Default.Folder
            } else {
                Icons.AutoMirrored.Filled.Article
            },
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(end = 12.dp)
        )
        Text(
            text = node.name,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge
        )
        if (!isFolder) {
            TextButton(onClick = onEditDocument) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 4.dp)
                )
                Text("编辑", fontSize = 13.sp)
            }
            TextButton(onClick = onPreviewDocument) {
                Icon(
                    Icons.Default.Visibility,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 4.dp)
                )
                Text("预览", fontSize = 13.sp)
            }
        }
        IconButton(onClick = { menuOpen = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = "更多")
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("重命名") },
                onClick = {
                    menuOpen = false
                    onRename()
                }
            )
            DropdownMenuItem(
                text = { Text("删除") },
                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    onDelete()
                }
            )
        }
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
        confirmButton = {
            TextButton(onClick = { onConfirm(value) }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
