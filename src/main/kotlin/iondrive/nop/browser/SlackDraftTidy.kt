package iondrive.nop.browser

import iondrive.nop.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.callback.CefCallback
import org.cef.handler.CefResourceHandler
import org.cef.handler.CefResourceHandlerAdapter
import org.cef.handler.CefResourceRequestHandler
import org.cef.handler.CefResourceRequestHandlerAdapter
import org.cef.misc.IntRef
import org.cef.misc.StringRef
import org.cef.network.CefPostDataElement
import org.cef.network.CefRequest
import org.cef.network.CefResponse
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Vector

/** What the tidy page found in Slack's local drafts (slack-drafts-tidy.js). */
@Serializable
internal data class SlackDraftTidyReport(
    val stores: Int = 0,
    val kept: Int = 0,
    val removed: List<Removed> = emptyList(),
    val errors: List<String> = emptyList(),
) {
    @Serializable
    data class Removed(
        val channel: String = "",
        val thread: Boolean = false,
        val people: Int = 0,
        val composer: Boolean = false,
        /** Slack's own `date_created`: milliseconds when the client made it, seconds from its server. */
        val created: Double = 0.0,
        val files: Int = 0,
        val text: String = "",
    )
}

/**
 * nop 1.7.2 and 1.7.3 set an attribute on Slack's message box, and Slack's editor took that for
 * typing and saved drafts nobody wrote. Slack's server never accepted the empty ones, and Slack's
 * client keeps a draft its server never saved in localStorage for good, counting it for the draft
 * pencil beside the channel with nothing in the message box. Once per profile, before Slack's
 * client starts, nop opens its own page on Slack's origin that removes those drafts, keeping any
 * text they hold in [BACKUP], and then goes on to Slack.
 */
internal class SlackDraftTidy(
    private val directory: () -> Path,
    private val clock: () -> Instant = Instant::now,
) {
    @Volatile private var finished: Boolean? = null

    /** Where a Slack page starts: by way of the tidy page, until that has run once for this profile. */
    fun firstUrl(next: String): String = if (finished()) next else pageUrl(next)

    private fun finished(): Boolean = finished ?: runCatching { Files.exists(directory().resolve(MARKER)) }
        .getOrDefault(false).also { finished = it }

    /** Serves the tidy page and takes its report; every other request goes to the network as usual. */
    fun resourceRequestHandler(request: CefRequest): CefResourceRequestHandler? {
        // Every request Slack's page makes comes through here.
        val url = request.url ?: return null
        if (!url.startsWith("https://app.slack.com$PAGE")) return null
        val route = route(url, request.method) ?: return null
        return object : CefResourceRequestHandlerAdapter() {
            override fun getResourceHandler(browser: CefBrowser?, frame: CefFrame?, request: CefRequest): CefResourceHandler =
                when (route) {
                    Route.Page -> Response(200, "text/html", page)
                    Route.Report -> object : Response(204, "text/plain", ByteArray(0)) {
                        override fun processRequest(request: CefRequest, callback: CefCallback): Boolean {
                            if (!record(body(request))) status = 500
                            return super.processRequest(request, callback)
                        }
                    }
                }
        }
    }

    /**
     * Keeps the text of the drafts the page is about to remove, then marks the tidy as done. False
     * when that text could not be kept, so the page leaves the drafts where they are.
     */
    @Synchronized
    internal fun record(body: String): Boolean {
        val report = runCatching { json.decodeFromString<SlackDraftTidyReport>(body) }.getOrElse {
            Log.warn("Could not read the Slack draft tidy report: ${it.message}")
            return false
        }
        val now = clock()
        val folder = directory()
        val backup = folder.resolve(BACKUP)
        val text = backupText(report, now, ZoneId.systemDefault())
        try {
            if (text != null) appendPrivately(backup, text)
            Files.writeString(folder.resolve(MARKER), "$now\n")
        } catch (failure: Exception) {
            Log.warn("Could not keep the Slack drafts nop was removing, so they stay: ${failure.message}")
            return false
        }
        finished = true
        Log.info(summary(report, backup.takeIf { text != null }))
        report.errors.forEach { Log.warn("Slack draft tidy: $it") }
        return true
    }

    private open class Response(var status: Int, private val mimeType: String, private val body: ByteArray) :
        CefResourceHandlerAdapter() {
        private var offset = 0

        override fun processRequest(request: CefRequest, callback: CefCallback): Boolean {
            callback.Continue()
            return true
        }

        override fun getResponseHeaders(response: CefResponse, length: IntRef, redirect: StringRef) {
            response.status = status
            response.statusText = when (status) { 200 -> "OK"; 204 -> "No Content"; else -> "Internal Server Error" }
            response.mimeType = mimeType
            response.setHeaderMap(mapOf("Cache-Control" to "no-store", "Referrer-Policy" to "no-referrer"))
            length.set(body.size)
        }

        override fun readResponse(data: ByteArray, count: Int, read: IntRef, callback: CefCallback): Boolean {
            val size = minOf(count, body.size - offset)
            if (size <= 0) {
                read.set(0)
                return false
            }
            body.copyInto(data, 0, offset, offset + size)
            offset += size
            read.set(size)
            return true
        }
    }

    internal enum class Route { Page, Report }

    companion object {
        const val PAGE = "/nop/tidy-drafts"
        private const val REPORT = "$PAGE/report"
        const val MARKER = "slack-drafts-tidied"
        const val BACKUP = "slack-removed-drafts.txt"
        private const val MOST = 4 shl 20
        private val json = Json { ignoreUnknownKeys = true }
        private val page: ByteArray by lazy {
            val script = checkNotNull(SlackDraftTidy::class.java.getResource("/browser/slack-drafts-tidy.js")).readText()
            check(!script.contains("</script", ignoreCase = true)) { "The tidy script would end its own page" }
            ("<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"color-scheme\" content=\"dark light\">" +
                "<title>Slack</title></head><body><script>$script</script></body></html>").toByteArray(UTF_8)
        }

        fun pageUrl(next: String): String = "https://app.slack.com$PAGE?next=" + URLEncoder.encode(next, UTF_8)

        fun isPage(url: String): Boolean = route(url, "GET") == Route.Page

        internal fun route(url: String, method: String): Route? {
            val uri = runCatching { URI(url) }.getOrNull() ?: return null
            if (uri.scheme != "https" || uri.host != "app.slack.com" || uri.port != -1 || uri.userInfo != null) return null
            return when {
                uri.rawPath == PAGE && method == "GET" -> Route.Page
                uri.rawPath == REPORT && method == "POST" -> Route.Report
                else -> null
            }
        }

        /** The removed drafts worth keeping, those with text or files, or null when there are none. */
        internal fun backupText(report: SlackDraftTidyReport, now: Instant, zone: ZoneId): String? {
            val worth = report.removed.filter { it.text.isNotBlank() || it.files > 0 }
            if (worth.isEmpty()) return null
            val time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(zone)
            return buildString {
                append("Slack drafts nop removed on ${time.format(now)}. Slack's server never saved them, so this is their only copy.\n\n")
                for (draft in worth) {
                    val where = when {
                        draft.channel.isNotEmpty() -> "Channel ${draft.channel}" + if (draft.thread) ", in a thread" else ""
                        draft.people > 0 -> "A new message to ${draft.people} " + if (draft.people == 1) "person" else "people"
                        else -> "A draft with no destination"
                    }
                    val created = draft.created.takeIf { it > 0 }?.let {
                        val instant = if (it > 1e11) Instant.ofEpochMilli(it.toLong()) else Instant.ofEpochSecond(it.toLong())
                        ", started ${time.format(instant)}"
                    }.orEmpty()
                    append(where).append(created).append('\n')
                    if (draft.text.isNotBlank()) append(draft.text.trim()).append('\n')
                    if (draft.files > 0) append("(${draft.files} attached ${if (draft.files == 1) "file" else "files"}, not kept here)\n")
                    append('\n')
                }
            }
        }

        internal fun summary(report: SlackDraftTidyReport, backup: Path?): String {
            val removed = report.removed.size
            val saved = "${report.kept} ${if (report.kept == 1) "draft" else "drafts"} Slack's server has saved left as they are"
            if (removed == 0) return "Slack draft tidy: no unsaved drafts to remove; $saved"
            val kept = backup?.let { " (their text is in $it)" }.orEmpty()
            return "Slack draft tidy: removed $removed ${if (removed == 1) "draft" else "drafts"} Slack's server never saved$kept; $saved"
        }

        private fun body(request: CefRequest): String {
            val data = request.postData ?: return ""
            val elements = Vector<CefPostDataElement>()
            data.getElements(elements)
            val out = ByteArrayOutputStream()
            for (element in elements) {
                val size = element.bytesCount
                if (size <= 0 || out.size() + size > MOST) continue
                val bytes = ByteArray(size)
                out.write(bytes, 0, element.getBytes(size, bytes))
            }
            return out.toString(UTF_8)
        }

        private fun appendPrivately(file: Path, text: String) {
            if (!Files.exists(file)) runCatching {
                Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
            }
            Files.writeString(file, text, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        }
    }
}
