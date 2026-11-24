package iondrive.nop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import iondrive.nop.TextFileRefusal
import iondrive.nop.TextFiles
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import java.io.File

/**
 * Placeholder shown in place of the text editor when a file is binary or exceeds [TextFiles.MAX_TEXT_BYTES].
 * Draws without creating a buffer or touching Skia text layout for the file's contents.
 */
@Composable
internal fun UneditableFileView(
    file: File,
    refusal: TextFileRefusal,
    onOpenInSystem: (File) -> Unit = ::openInSystem,
    modifier: Modifier = Modifier,
) {
    val heading = when (refusal) {
        is TextFileRefusal.Binary -> "Binary file cannot be opened in the text editor"
        is TextFileRefusal.TooLarge -> "File is too large to open in the text editor"
    }
    val detail = when (refusal) {
        is TextFileRefusal.Binary -> refusal.sizeBytes?.let { "${file.name} (${TextFiles.formatSize(it)})" } ?: file.name
        is TextFileRefusal.TooLarge ->
            "${file.name} is ${TextFiles.formatSize(refusal.sizeBytes)} — limit is ${TextFiles.formatSize(refusal.limitBytes)}"
    }
    Box(
        modifier = modifier.fillMaxSize().background(JewelTheme.globalColors.panelBackground),
    ) {
        Column(
            modifier = Modifier.align(Alignment.Center).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(heading)
            Text(detail, color = JewelTheme.globalColors.text.info)
            if (file.isFile) {
                OutlinedButton(onClick = { onOpenInSystem(file) }) {
                    Text("Open with system app")
                }
            }
        }
        FileNameBadgeOverlay()
    }
}

/** Placeholder shown in place of a diff when either side is binary or exceeds [TextFiles.MAX_TEXT_BYTES]. */
@Composable
internal fun DiffRefusalView(
    refusal: TextFileRefusal,
    file: File? = null,
    onOpenInSystem: (File) -> Unit = ::openInSystem,
    showFileNameBadge: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize(),
    ) {
        Column(
            modifier = Modifier.align(Alignment.Center).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(diffRefusalMessage(refusal), color = JewelTheme.globalColors.text.info)
            if (file != null && file.isFile) {
                OutlinedButton(onClick = { onOpenInSystem(file) }) {
                    Text("Open with system app")
                }
            }
        }
        if (showFileNameBadge) FileNameBadgeOverlay()
    }
}

internal fun diffRefusalMessage(refusal: TextFileRefusal): String = when (refusal) {
    is TextFileRefusal.Binary -> refusal.sizeBytes?.let {
        "Binary file (${TextFiles.formatSize(it)}) — diff not shown"
    } ?: "Binary file — diff not shown"
    is TextFileRefusal.TooLarge ->
        "File is too large to diff (${TextFiles.formatSize(refusal.sizeBytes)}; limit is ${TextFiles.formatSize(refusal.limitBytes)})"
}

internal fun previewRefusalMessage(refusal: TextFileRefusal): String = when (refusal) {
    is TextFileRefusal.Binary -> "Binary file cannot be previewed"
    is TextFileRefusal.TooLarge ->
        "File is too large to preview (${TextFiles.formatSize(refusal.sizeBytes)}; limit is ${TextFiles.formatSize(refusal.limitBytes)})"
}
