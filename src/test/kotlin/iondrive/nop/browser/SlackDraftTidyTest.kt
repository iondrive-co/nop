package iondrive.nop.browser

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset

class SlackDraftTidyTest {
    private val now = Instant.parse("2026-03-04T05:06:00Z")

    @Test
    fun `only nop's two addresses on Slack's own origin are served`() {
        val page = SlackDraftTidy.pageUrl("https://app.slack.com/client/TDEMO/CDEMO?x=1&y=2")
        assertEquals("https://app.slack.com/nop/tidy-drafts?next=https%3A%2F%2Fapp.slack.com%2Fclient%2FTDEMO%2FCDEMO%3Fx%3D1%26y%3D2", page)
        assertEquals(SlackDraftTidy.Route.Page, SlackDraftTidy.route(page, "GET"))
        assertTrue(SlackDraftTidy.isPage(page))
        assertEquals(SlackDraftTidy.Route.Report, SlackDraftTidy.route("https://app.slack.com/nop/tidy-drafts/report", "POST"))
        assertNull(SlackDraftTidy.route("https://app.slack.com/nop/tidy-drafts/report", "GET"))
        assertNull(SlackDraftTidy.route("https://app.slack.com/nop/tidy-drafts", "POST"))
        assertNull(SlackDraftTidy.route("http://app.slack.com/nop/tidy-drafts", "GET"))
        assertNull(SlackDraftTidy.route("https://app.slack.com.example.org/nop/tidy-drafts", "GET"))
        assertNull(SlackDraftTidy.route("https://someone@app.slack.com/nop/tidy-drafts", "GET"))
        assertNull(SlackDraftTidy.route("https://app.slack.com:8443/nop/tidy-drafts", "GET"))
        assertNull(SlackDraftTidy.route("https://example.org/nop/tidy-drafts", "GET"))
        assertNull(SlackDraftTidy.route("https://app.slack.com/client/TDEMO", "GET"))
        assertFalse(SlackDraftTidy.isPage("https://app.slack.com/client/TDEMO"))
    }

    @Test
    fun `the tidy runs once per profile`(@TempDir folder: Path) {
        val tidy = SlackDraftTidy({ folder }) { now }
        val slack = "https://app.slack.com/client/TDEMO"
        assertEquals(SlackDraftTidy.pageUrl(slack), tidy.firstUrl(slack))
        assertTrue(tidy.record("""{"stores":1,"kept":2,"removed":[],"errors":[]}"""))
        assertEquals(slack, tidy.firstUrl(slack))
        assertFalse(Files.exists(folder.resolve(SlackDraftTidy.BACKUP)), "nothing to keep")
        // A later nop finds the marker.
        assertEquals(slack, SlackDraftTidy({ folder }) { now }.firstUrl(slack))
    }

    @Test
    fun `the text of removed drafts is kept before the page may remove them`(@TempDir folder: Path) {
        val tidy = SlackDraftTidy({ folder }) { now }
        val report = """{"stores":1,"kept":1,"removed":[
            {"channel":"CEMPTY","created":1772600000000,"text":""},
            {"channel":"COLD","composer":true,"created":1772500000,"files":1,"text":"An old idea [emoji]"},
            {"channel":"CTHREAD","thread":true,"text":"  reply  "},
            {"people":2,"text":"hello both"}
        ],"errors":["one store could not be read"],"later":"ignored"}"""
        assertTrue(tidy.record(report))
        val kept = Files.readString(folder.resolve(SlackDraftTidy.BACKUP))
        assertTrue(kept.contains("An old idea [emoji]\n(1 attached file, not kept here)"), kept)
        assertTrue(kept.contains("Channel CTHREAD, in a thread\nreply\n"), kept)
        assertTrue(kept.contains("A new message to 2 people\nhello both"), kept)
        assertFalse(kept.contains("CEMPTY"), "an empty draft has nothing to keep")
        assertTrue(Files.exists(folder.resolve(SlackDraftTidy.MARKER)))
        // A second tidy adds to the file rather than replacing what the first kept.
        assertTrue(SlackDraftTidy({ folder }) { now }.record("""{"removed":[{"channel":"CNEW","text":"second"}]}"""))
        val both = Files.readString(folder.resolve(SlackDraftTidy.BACKUP))
        assertTrue(both.startsWith(kept) && both.contains("second"), both)
    }

    @Test
    fun `drafts stay when their text cannot be kept`(@TempDir folder: Path) {
        val notAFolder = folder.resolve("file")
        Files.writeString(notAFolder, "")
        val tidy = SlackDraftTidy({ notAFolder }) { now }
        assertFalse(tidy.record("""{"removed":[{"channel":"CDEMO","text":"keep me"}]}"""))
        assertEquals(SlackDraftTidy.pageUrl("https://app.slack.com/client"), tidy.firstUrl("https://app.slack.com/client"), "tried again next time")
        assertFalse(SlackDraftTidy({ folder }) { now }.record("not json"))
        assertFalse(Files.exists(folder.resolve(SlackDraftTidy.MARKER)))
    }

    @Test
    fun `the kept file names each draft's place and start`() {
        val report = SlackDraftTidyReport(removed = listOf(
            SlackDraftTidyReport.Removed(channel = "CDEMO", created = 1772600000000.0, text = "draft text"),
            SlackDraftTidyReport.Removed(people = 1, created = 1772500000.0, files = 2),
            SlackDraftTidyReport.Removed(text = "lost"),
        ))
        assertEquals(
            "Slack drafts nop removed on 2026-03-04 05:06. Slack's server never saved them, so this is their only copy.\n\n" +
                "Channel CDEMO, started 2026-03-04 04:53\ndraft text\n\n" +
                "A new message to 1 person, started 2026-03-03 01:06\n(2 attached files, not kept here)\n\n" +
                "A draft with no destination\nlost\n\n",
            SlackDraftTidy.backupText(report, now, ZoneOffset.UTC))
        assertNull(SlackDraftTidy.backupText(SlackDraftTidyReport(removed = listOf(SlackDraftTidyReport.Removed(channel = "CDEMO"))), now, ZoneOffset.UTC))
        assertEquals("Slack draft tidy: no unsaved drafts to remove; 1 draft Slack's server has saved left as they are",
            SlackDraftTidy.summary(SlackDraftTidyReport(kept = 1), null))
        assertEquals("Slack draft tidy: removed 3 drafts Slack's server never saved (their text is in /d/kept.txt); " +
            "0 drafts Slack's server has saved left as they are", SlackDraftTidy.summary(report, Path.of("/d/kept.txt")))
    }
}
