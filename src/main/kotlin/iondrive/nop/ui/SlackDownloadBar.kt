package iondrive.nop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import iondrive.nop.browser.SlackDownload
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import java.nio.file.Path

/** Where the Slack page's downloads went, since nothing else in nop shows a browser download. */
@Composable
internal fun SlackDownloadBar(downloads: List<SlackDownload>, onDismiss: () -> Unit) {
    if (downloads.isEmpty()) return
    val finished = downloads.none { it.status == SlackDownload.Status.Running }
    val saved = downloads.filter { it.status == SlackDownload.Status.Saved }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(slackDownloadSummary(downloads), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (finished) {
            if (saved.size == 1) OutlinedButton(onClick = { openInSystem(saved[0].file.toFile()) }) { Text("Open") }
            saved.lastOrNull()?.let { last -> OutlinedButton(onClick = { showInFolder(last.file) }) { Text("Show in folder") } }
            OutlinedButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}

internal fun slackDownloadSummary(downloads: List<SlackDownload>, home: Path = Path.of(System.getProperty("user.home"))): String {
    val running = downloads.filter { it.status == SlackDownload.Status.Running }
    if (running.size == 1) {
        val download = running[0]
        val percent = if (download.total > 0) " ${(download.received * 100 / download.total).coerceIn(0, 100)}%" else ""
        return "Downloading ${download.name}…$percent"
    }
    if (running.size > 1) return "Downloading ${running.size} files…"
    val saved = downloads.filter { it.status == SlackDownload.Status.Saved }
    val failed = downloads.size - saved.size
    val folder = saved.lastOrNull()?.file?.parent?.let { shortPath(it, home) }
    val savedText = when (saved.size) {
        0 -> null
        1 -> "Saved ${saved[0].name} to $folder"
        else -> "Saved ${saved.size} files to $folder"
    }
    val failedText = when {
        failed == 0 -> null
        downloads.size == 1 -> "Could not download ${downloads[0].name}"
        saved.isEmpty() -> "Could not download $failed files"
        else -> "$failed could not be downloaded"
    }
    return listOfNotNull(savedText, failedText).joinToString("; ")
}

private fun shortPath(path: Path, home: Path): String {
    val normalized = path.toAbsolutePath().normalize()
    val root = home.toAbsolutePath().normalize()
    return if (normalized.startsWith(root) && normalized != root) "~/" + root.relativize(normalized).toString() else normalized.toString()
}

/**
 * Opens the file's folder with the file selected, through the freedesktop FileManager1 interface on
 * Linux (the same one [openInSystem] uses for folders), else just the folder.
 */
private fun showInFolder(file: Path) {
    Thread {
        val linux = System.getProperty("os.name").lowercase().contains("linux")
        val selected = linux && runCatching {
            ProcessBuilder(
                "dbus-send", "--session", "--dest=org.freedesktop.FileManager1", "--type=method_call",
                "/org/freedesktop/FileManager1", "org.freedesktop.FileManager1.ShowItems",
                "array:string:${file.toUri()}", "string:",
            ).redirectErrorStream(true).start().waitFor() == 0
        }.getOrDefault(false)
        if (!selected) file.parent?.let { openInSystem(it.toFile()) }
    }.apply { name = "nop-show-in-folder"; isDaemon = true }.start()
}
