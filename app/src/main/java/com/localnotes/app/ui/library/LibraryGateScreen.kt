package com.localnotes.app.ui.library

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun LibraryGateScreen(
    loading: Boolean,
    busy: Boolean,
    message: String?,
    onCreateLibrary: (Uri, String) -> Unit,
    onOpenLibrary: (Uri) -> Unit
) {
    if (loading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text("正在打开笔记库…")
            }
        }
        return
    }

    var libraryName by remember { mutableStateOf("我的笔记库") }
    var pendingCreate by remember { mutableStateOf(false) }

    val openTree = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) {
            pendingCreate = false
            return@rememberLauncherForActivityResult
        }
        if (pendingCreate) {
            onCreateLibrary(uri, libraryName)
        } else {
            onOpenLibrary(uri)
        }
        pendingCreate = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "本地笔记",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "数据保存在你选择的文件夹中。换机时拷贝整个文件夹，再“打开已有库”即可。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f)
        )
        Spacer(Modifier.height(28.dp))

        OutlinedTextField(
            value = libraryName,
            onValueChange = { libraryName = it },
            label = { Text("新建库名称") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(Modifier.height(16.dp))

        Button(
            onClick = {
                pendingCreate = true
                openTree.launch(null)
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("创建新笔记库")
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = {
                pendingCreate = false
                openTree.launch(null)
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("打开已有笔记库")
        }

        if (busy) {
            Spacer(Modifier.height(20.dp))
            CircularProgressIndicator()
        }
        if (!message.isNullOrBlank()) {
            Spacer(Modifier.height(16.dp))
            Text(text = message, color = MaterialTheme.colorScheme.error)
        }
    }
}
