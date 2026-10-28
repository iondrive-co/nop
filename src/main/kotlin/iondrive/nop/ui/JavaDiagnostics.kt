package iondrive.nop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import iondrive.nop.lang.JavaAnalysis
import iondrive.nop.lang.JavaAnalysisProject
import iondrive.nop.lang.JavaAnalysisResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import java.io.File

internal data class JavaDiagnostics(val text: String? = null, val result: JavaAnalysisResult? = null, val status: String? = null)

private val javaAnalysisDispatcher = Dispatchers.Default.limitedParallelism(1)

/** The compiler reads immutable snapshots, including unsaved edits in other open Java files. */
@OptIn(FlowPreview::class)
@Composable
internal fun rememberJavaDiagnostics(
    file: File,
    edit: FileEdit?,
    store: FileEditStore,
    project: JavaAnalysisProject,
): JavaDiagnostics {
    var diagnostics by remember(file) { mutableStateOf(JavaDiagnostics()) }
    LaunchedEffect(file, edit, project) {
        if (edit == null || !file.extension.equals("java", ignoreCase = true) || project.dependencyStatus != null) return@LaunchedEffect
        var immediate = true
        snapshotFlow {
            edit.state.text.toString() to store.snapshot()
                .filter { it.file.extension.equals("java", ignoreCase = true) }
                .associate { it.file to it.state.text.toString() }
        }.debounce { if (immediate) 0L.also { immediate = false } else 300L }
            .distinctUntilChanged()
            .collectLatest { (text, buffers) ->
                val result = withContext(javaAnalysisDispatcher) {
                    JavaAnalysis.analyze(text, file, project.root, project.files, buffers, project.gradle)
                }
                if (edit.state.text.toString() == text) diagnostics = JavaDiagnostics(text, result)
            }
    }
    if (edit != null && file.extension.equals("java", ignoreCase = true) && project.dependencyStatus != null)
        return JavaDiagnostics(status = project.dependencyStatus)
    return diagnostics.takeIf { it.text == edit?.state?.text?.toString() } ?: JavaDiagnostics()
}
