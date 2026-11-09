package iondrive.nop.browser

import iondrive.nop.Log
import org.cef.browser.CefBrowser
import org.cef.callback.CefBeforeDownloadCallback
import org.cef.callback.CefDownloadItem
import org.cef.callback.CefDownloadItemCallback
import org.cef.handler.CefDownloadHandler
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** A file a Slack page asked Chromium to save, as the Slack panel reports it. */
data class SlackDownload(
    val id: Int,
    val name: String,
    val file: Path,
    val received: Long = 0,
    val total: Long = -1,
    val status: Status = Status.Running,
) {
    enum class Status { Running, Saved, Failed }
}

/**
 * Saves a Slack page's downloads straight into the downloads folder, as a browser does by default.
 * Asking Chromium for a save dialog instead cancels every download: this Linux CEF has no dialog, and
 * says nothing. The name is made unique here, since Chromium overwrites a file already at the path it
 * is given. Slack's own Download button fetches the file and clicks a `blob:` link to it, so this sees
 * that as one download from the page itself.
 */
internal class SlackDownloads(
    private val directory: () -> Path,
    private val report: (SlackDownload) -> Unit,
) : CefDownloadHandler {
    // Chromium writes a download under another name until it finishes, so a running download's
    // file is not on disk yet to keep the next one from choosing the same name.
    private val running = mutableMapOf<Int, SlackDownload>()

    @Synchronized
    override fun onBeforeDownload(browser: CefBrowser, item: CefDownloadItem, suggestedName: String,
                                  callback: CefBeforeDownloadCallback): Boolean {
        val name = safeDownloadName(suggestedName)
        val file = try {
            val folder = directory()
            Files.createDirectories(folder)
            uniqueDownloadPath(folder, name, running.values.map { it.file })
        } catch (failure: Exception) {
            Log.warn("Could not save a Slack download: ${failure.message}")
            report(SlackDownload(item.id, name, Path.of(name), status = SlackDownload.Status.Failed))
            return false
        }
        val download = SlackDownload(item.id, file.fileName.toString(), file, total = item.totalBytes)
        running[item.id] = download
        report(download)
        callback.Continue(file.toString(), false)
        return true
    }

    @Synchronized
    override fun onDownloadUpdated(browser: CefBrowser, item: CefDownloadItem, callback: CefDownloadItemCallback) {
        // Chromium reports a download once before it asks where to put it.
        val download = running[item.id] ?: return
        val status = when {
            item.isComplete -> SlackDownload.Status.Saved
            item.isCanceled || !item.isInProgress -> SlackDownload.Status.Failed
            else -> SlackDownload.Status.Running
        }
        val next = download.copy(received = item.receivedBytes, total = item.totalBytes, status = status)
        if (status == SlackDownload.Status.Running) running[item.id] = next else running.remove(item.id)
        if (status == SlackDownload.Status.Failed) Log.warn("A Slack download did not finish: ${download.name}")
        if (next != download) report(next)
    }
}

/**
 * The downloads shown together: [download] replaces its earlier report, and the first download
 * after the last batch finished starts a new batch.
 */
internal fun List<SlackDownload>.including(download: SlackDownload): List<SlackDownload> = when {
    any { it.id == download.id } -> map { if (it.id == download.id) download else it }
    none { it.status == SlackDownload.Status.Running } -> listOf(download)
    else -> this + download
}

/** Chromium already cleans the name it suggests; this keeps the file in the downloads folder regardless. */
internal fun safeDownloadName(suggested: String): String {
    var name = suggested.substringAfterLast('/').substringAfterLast('\\')
        .filterNot { it.isISOControl() }.trim()
    if (name.isEmpty() || name == "." || name == "..") return "download"
    // Room under the usual 255-byte limit for the " (n)" a repeated name gets.
    val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
    val extension = name.substring(dot).take(32)
    var base = name.substring(0, dot)
    while (base.isNotEmpty() && (base + extension).toByteArray().size > 240) base = base.dropLast(1)
    name = base + extension
    return name.ifBlank { "download" }
}

internal fun uniqueDownloadPath(directory: Path, name: String, taken: Collection<Path>): Path {
    val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
    val base = name.substring(0, dot)
    val extension = name.substring(dot)
    var copy = 0
    while (true) {
        val candidate = directory.resolve(if (copy == 0) name else "$base ($copy)$extension")
        check(candidate.parent == directory) { "download name leaves the folder" }
        if (candidate !in taken && !Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) return candidate
        copy++
    }
}

/**
 * The desktop's downloads folder: `XDG_DOWNLOAD_DIR` from xdg-user-dirs, as Chrome and Firefox use,
 * and otherwise `~/Downloads`. xdg-user-dirs points a folder it has no name for at the home folder
 * itself, and nobody wants downloads scattered there.
 */
internal fun downloadDirectory(
    home: Path = Path.of(System.getProperty("user.home")),
    environment: (String) -> String? = System::getenv,
): Path {
    val config = environment("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }?.let(Path::of) ?: home.resolve(".config")
    val setting = Regex("""^\s*XDG_DOWNLOAD_DIR\s*=\s*"(.*)"\s*$""")
    val configured = runCatching {
        Files.readAllLines(config.resolve("user-dirs.dirs")).firstNotNullOfOrNull { setting.find(it)?.groupValues?.get(1) }
    }.getOrNull()?.let { value ->
        val expanded = when {
            value == "\$HOME" -> home.toString()
            value.startsWith("\$HOME/") -> home.resolve(value.removePrefix("\$HOME/")).toString()
            else -> value
        }
        runCatching { Path.of(expanded) }.getOrNull()?.takeIf { it.isAbsolute }?.normalize()
    }
    return configured?.takeIf { it != home.normalize() } ?: home.resolve("Downloads")
}
