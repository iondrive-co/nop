package iondrive.nop.browser

import iondrive.nop.ui.slackDownloadSummary
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class SlackDownloadsTest {
    @Test
    fun `a repeated name gets a number instead of overwriting the earlier file`(@TempDir folder: Path) {
        assertEquals(folder.resolve("report.pdf"), uniqueDownloadPath(folder, "report.pdf", emptyList()))
        Files.writeString(folder.resolve("report.pdf"), "first")
        assertEquals(folder.resolve("report (1).pdf"), uniqueDownloadPath(folder, "report.pdf", emptyList()))
        // A download still running has no file yet, but its name is spoken for.
        assertEquals(folder.resolve("report (2).pdf"),
            uniqueDownloadPath(folder, "report.pdf", listOf(folder.resolve("report (1).pdf"))))
        Files.writeString(folder.resolve("notes"), "")
        assertEquals(folder.resolve("notes (1)"), uniqueDownloadPath(folder, "notes", emptyList()))
        Files.writeString(folder.resolve(".env"), "")
        assertEquals(folder.resolve(".env (1)"), uniqueDownloadPath(folder, ".env", emptyList()))
    }

    @Test
    fun `download names stay inside the downloads folder`() {
        assertEquals("passwd", safeDownloadName("../../etc/passwd"))
        assertEquals("evil.txt", safeDownloadName("..\\..\\evil.txt"))
        assertEquals("download", safeDownloadName(".."))
        assertEquals("download", safeDownloadName("  "))
        assertEquals("ab.txt", safeDownloadName("a\u0000b.txt"))
        val long = safeDownloadName("x".repeat(400) + ".pdf")
        assertTrue(long.endsWith(".pdf") && long.toByteArray().size <= 240, long)
        val wide = safeDownloadName("é".repeat(300) + ".png")
        assertTrue(wide.endsWith(".png") && wide.toByteArray().size <= 240, wide)
    }

    @Test
    fun `downloads go to the desktop's downloads folder, else Downloads in the home folder`(@TempDir home: Path) {
        val noConfig = { _: String -> null }
        assertEquals(home.resolve("Downloads"), downloadDirectory(home, noConfig))
        val dirs = home.resolve(".config/user-dirs.dirs")
        Files.createDirectories(dirs.parent)
        Files.writeString(dirs, "# written by xdg-user-dirs-update\nXDG_DESKTOP_DIR=\"\$HOME/Desktop\"\nXDG_DOWNLOAD_DIR=\"\$HOME/Téléchargements\"\n")
        assertEquals(home.resolve("Téléchargements"), downloadDirectory(home, noConfig))
        Files.writeString(dirs, "XDG_DOWNLOAD_DIR=\"/srv/incoming\"\n")
        assertEquals(Path.of("/srv/incoming"), downloadDirectory(home, noConfig))
        // xdg-user-dirs' way of saying it has no downloads folder.
        Files.writeString(dirs, "XDG_DOWNLOAD_DIR=\"\$HOME/\"\n")
        assertEquals(home.resolve("Downloads"), downloadDirectory(home, noConfig))
        Files.writeString(dirs, "XDG_DOWNLOAD_DIR=\"relative/place\"\n")
        assertEquals(home.resolve("Downloads"), downloadDirectory(home, noConfig))
        val elsewhere = home.resolve("elsewhere")
        Files.createDirectories(elsewhere)
        Files.writeString(elsewhere.resolve("user-dirs.dirs"), "XDG_DOWNLOAD_DIR=\"\$HOME/Incoming\"\n")
        assertEquals(home.resolve("Incoming"), downloadDirectory(home) { if (it == "XDG_CONFIG_HOME") elsewhere.toString() else null })
    }

    @Test
    fun `the bar shows the downloads since the last batch finished`() {
        val first = SlackDownload(1, "a.txt", Path.of("/d/a.txt"))
        val second = SlackDownload(2, "b.txt", Path.of("/d/b.txt"))
        val both = listOf(first).including(second)
        assertEquals(listOf(1, 2), both.map { it.id })
        val done = both.including(first.copy(status = SlackDownload.Status.Saved))
            .including(second.copy(status = SlackDownload.Status.Saved))
        assertEquals(listOf(SlackDownload.Status.Saved, SlackDownload.Status.Saved), done.map { it.status })
        assertEquals(listOf(3), done.including(SlackDownload(3, "c.txt", Path.of("/d/c.txt"))).map { it.id })
    }

    @Test
    fun `the bar says where each download went`() {
        val home = Path.of("/home/someone")
        val file = SlackDownload(1, "report.pdf", home.resolve("Downloads/report.pdf"), received = 50, total = 200)
        assertEquals("Downloading report.pdf… 25%", slackDownloadSummary(listOf(file), home))
        assertEquals("Downloading report.pdf…", slackDownloadSummary(listOf(file.copy(total = -1)), home))
        assertEquals("Downloading 2 files…", slackDownloadSummary(listOf(file, file.copy(id = 2)), home))
        val saved = file.copy(status = SlackDownload.Status.Saved)
        val failed = file.copy(id = 2, name = "other.zip", status = SlackDownload.Status.Failed)
        assertEquals("Saved report.pdf to ~/Downloads", slackDownloadSummary(listOf(saved), home))
        assertEquals("Saved 2 files to ~/Downloads", slackDownloadSummary(listOf(saved, saved.copy(id = 3)), home))
        assertEquals("Could not download other.zip", slackDownloadSummary(listOf(failed), home))
        assertEquals("Saved report.pdf to ~/Downloads; 1 could not be downloaded", slackDownloadSummary(listOf(saved, failed), home))
        assertEquals("Saved report.pdf to /srv/incoming",
            slackDownloadSummary(listOf(saved.copy(file = Path.of("/srv/incoming/report.pdf"))), home))
    }

    @Test
    fun `only Slack's private file links are saved from the page`() {
        assertTrue(SlackNavigation.fileLink("https://files.slack.com/files-pri/TDEMO-FDEMO/download/report.pdf"))
        assertTrue(SlackNavigation.fileLink("https://files.slack.com/files-pri/TDEMO-FDEMO/report.pdf?origin_team=TDEMO"))
        assertFalse(SlackNavigation.fileLink("http://files.slack.com/files-pri/TDEMO-FDEMO/download/report.pdf"))
        assertFalse(SlackNavigation.fileLink("https://files.slack.com.example.org/files-pri/TDEMO-FDEMO/report.pdf"))
        assertFalse(SlackNavigation.fileLink("https://someone@files.slack.com/files-pri/TDEMO-FDEMO/report.pdf"))
        assertFalse(SlackNavigation.fileLink("https://files.slack.com:8443/files-pri/TDEMO-FDEMO/report.pdf"))
        assertFalse(SlackNavigation.fileLink("https://app.slack.com/client/TDEMO/CGENERAL"))
        assertFalse(SlackNavigation.fileLink("https://example.org/files-pri/report.pdf"))
    }
}
