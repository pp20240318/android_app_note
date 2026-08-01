package com.localnotes.app

import android.content.ContentResolver
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
    val browser by vm.browser.collectAsStateWithLifecycle()
    val editor by vm.editor.collectAsStateWithLifecycle()
    val navController = rememberNavController()

    if (!browser.ready) {
        return
    }

    if (browser.library == null) {
        LibraryGateScreen(
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
                onDelete = vm::deleteNode,
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
                onInsertSnippet = { vm.insertMarkdown(it) },
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
                onBack = { navController.popBackStack() },
                onDismissMessage = vm::clearMessage
            )
        }
    }
}
