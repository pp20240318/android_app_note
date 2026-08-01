package com.localnotes.app

import android.content.ContentResolver
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.localnotes.app.ui.AppViewModel
import com.localnotes.app.ui.browser.BrowserScreen
import com.localnotes.app.ui.editor.EditorScreen
import com.localnotes.app.ui.library.LibraryGateScreen
import com.localnotes.app.ui.theme.LocalNotesTheme
import com.localnotes.app.util.shareFile
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LocalNotesTheme {
                Surface {
                    NotesRoot()
                }
            }
        }
    }
}

@Composable
private fun NotesRoot(vm: AppViewModel = viewModel()) {
    val contentResolver: ContentResolver = LocalContext.current.contentResolver
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val browser by vm.browser.collectAsStateWithLifecycle()
    val editor by vm.editor.collectAsStateWithLifecycle()
    val navController = rememberNavController()

    if (!browser.ready || browser.loading) {
        if (browser.library == null) {
            LibraryGateScreen(
                loading = browser.loading,
                busy = browser.busy,
                message = browser.message,
                onCreateLibrary = vm::createLibrary,
                onOpenLibrary = vm::openLibrary
            )
            return
        }
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CircularProgressIndicator()
        }
        return
    }

    if (browser.library == null) {
        LibraryGateScreen(
            loading = false,
            busy = browser.busy,
            message = browser.message,
            onCreateLibrary = vm::createLibrary,
            onOpenLibrary = vm::openLibrary
        )
        return
    }

    NavHost(navController = navController, startDestination = "browser") {
        composable("browser") {
            BrowserScreen(
                state = browser,
                onOpenFolder = vm::openFolder,
                onEditDocument = { docId ->
                    vm.loadDocument(docId)
                    navController.navigate("editor/$docId/edit")
                },
                onPreviewDocument = { docId ->
                    vm.loadDocument(docId)
                    navController.navigate("editor/$docId/preview")
                },
                onCreateFolder = vm::createFolder,
                onCreateDocument = { title ->
                    vm.createDocument(title) { docId ->
                        vm.loadDocument(docId)
                        navController.navigate("editor/$docId/edit")
                    }
                },
                onRename = vm::renameNode,
                onToggleFavorite = vm::toggleFavorite,
                onMoveToTrash = vm::moveToTrash,
                onRestore = vm::restoreFromTrash,
                onPurge = vm::purgeForever,
                onEmptyTrash = vm::emptyTrash,
                onToggleTrash = vm::toggleTrashView,
                onSearch = vm::setSearchQuery,
                onExportLibrary = {
                    scope.launch {
                        runCatching {
                            val file = vm.exportLibraryZip()
                            shareFile(context, file, "application/zip", "导出笔记库")
                        }.onFailure {
                            Toast.makeText(context, it.message ?: "导出失败", Toast.LENGTH_LONG).show()
                        }
                    }
                },
                onExportDocument = { docId ->
                    scope.launch {
                        runCatching {
                            val file = vm.exportDocument(docId)
                            shareFile(context, file, "text/markdown", "导出文档")
                        }.onFailure {
                            Toast.makeText(context, it.message ?: "导出失败", Toast.LENGTH_LONG).show()
                        }
                    }
                },
                onAdoptOrphans = vm::adoptOrphans,
                onDismissIntegrity = vm::dismissIntegrity,
                onSwitchLibrary = vm::switchLibrary,
                onDismissMessage = vm::clearMessage
            )
        }
        composable(
            route = "editor/{docId}/{mode}",
            arguments = listOf(
                navArgument("docId") { type = NavType.StringType },
                navArgument("mode") { type = NavType.StringType }
            )
        ) { entry ->
            val mode = entry.arguments?.getString("mode").orEmpty()
            EditorScreen(
                state = editor,
                initialPreviewMode = mode == "preview",
                onTitleChange = vm::updateTitle,
                onBodyChange = vm::updateBody,
                onCursorChange = vm::updateCursor,
                onInsertSnippet = vm::insertMarkdown,
                onImportImage = { uri, _ ->
                    val mime = contentResolver.getType(uri)
                    vm.importImage(uri, mime) {}
                },
                resolveImage = { relativePath ->
                    vm.resolveAssetUri(editor.docId, relativePath)
                },
                onSave = { vm.saveDocument() },
                onSaveAndBack = {
                    vm.saveDocument { navController.popBackStack() }
                },
                onExport = {
                    scope.launch {
                        runCatching {
                            val file = vm.exportCurrentDocument()
                            shareFile(context, file, "text/markdown", "导出文档")
                        }.onFailure {
                            Toast.makeText(context, it.message ?: "导出失败", Toast.LENGTH_LONG).show()
                        }
                    }
                },
                onBack = { navController.popBackStack() },
                onDismissMessage = vm::clearMessage
            )
        }
    }
}
