package iondrive.nop.browser

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SlackWorkspaceTest {
    @Test
    fun `closed workspace stays closed on rediscovery and can reopen without losing identity`() {
        val first = SlackWorkspaceTabs().discover(listOf(SlackWorkspace("TALPHA", "Workshop"), SlackWorkspace("TBRAVO", "Studio")))
            .reopen("TALPHA").reopen("TBRAVO")
        val closed = first.close("TALPHA").discover(listOf(SlackWorkspace("TALPHA", "New workshop")))
        assertEquals(listOf("TBRAVO"), closed.open.map { it.id })
        assertEquals("New workshop", closed.known.first().name)
        assertEquals(closed.known, closed.reopen("TALPHA").open)
        val restored = SlackWorkspaceTabs(closed.known, Json.decodeFromString(Json.encodeToString(closed.opened)))
        assertEquals(closed.open, restored.open)
        assertEquals(first, first.close("../invalid"))
    }

    @Test
    fun `discovering saved sign-ins never adds workspace tabs to another project`() {
        val catalog = listOf(SlackWorkspace("TALPHA", "Workshop"), SlackWorkspace("TBRAVO", "Studio"))
        val first = SlackWorkspaceTabs(catalog).reopen("TALPHA")
        val second = SlackWorkspaceTabs(catalog).reopen("TBRAVO")
        val discovered = catalog + SlackWorkspace("TCHARLIE", "Library")
        assertEquals(listOf("TALPHA"), first.discover(discovered).open.map { it.id })
        assertEquals(listOf("TBRAVO"), second.discover(discovered).open.map { it.id })
        assertTrue(SlackWorkspaceTabs(discovered).open.isEmpty())
        assertTrue(first.close("TALPHA").open.isEmpty())
        assertEquals(listOf("TBRAVO"), second.open.map { it.id })
    }

    @Test
    fun `notification metadata is bounded and a mention implies unread activity`() {
        val json = Json { ignoreUnknownKeys = true }
        val metadata = json.decodeFromString<SlackMetadata>("""{"attention":{"mentions":12},"current":{"id":"TALPHA","name":"Workshop"}}""")
        assertEquals(SlackAttention(true, 12), metadata.attention.sanitized())
        assertEquals(SlackAttention(false, 0), SlackAttention(false, -1).sanitized())
        assertEquals(9999, SlackAttention(false, Int.MAX_VALUE).sanitized().mentions)
        assertTrue(SlackAttention(true, 0).sanitized().unread)
    }

    @Test
    fun `each workspace keeps its identity when names coincide`() {
        val workspaces = SlackWorkspaceNames.merge(emptyList(), listOf(
            SlackWorkspace("TALPHA", "Workshop"), SlackWorkspace("TBRAVO", "Workshop"),
        ))
        assertEquals(listOf("TALPHA", "TBRAVO"), workspaces.map { it.id })
        assertEquals(listOf("https://app.slack.com/client/TALPHA", "https://app.slack.com/client/TBRAVO"), workspaces.map { it.url })
    }

    @Test
    fun `discovery refreshes names and keeps other workspace tabs`() {
        val first = listOf(SlackWorkspace("TALPHA", "Workshop"), SlackWorkspace("TBRAVO", "Studio"))
        val merged = SlackWorkspaceNames.merge(first, listOf(SlackWorkspace("TALPHA", "New workshop"), SlackWorkspace("TCHARLIE", "Library")))
        assertEquals(listOf(SlackWorkspace("TALPHA", "New workshop"), SlackWorkspace("TBRAVO", "Studio"), SlackWorkspace("TCHARLIE", "Library")), merged)
    }

    @Test
    fun `workspace metadata cannot introduce navigation paths or terminal controls`() {
        val merged = SlackWorkspaceNames.merge(emptyList(), listOf(
            SlackWorkspace("../elsewhere", "Invalid"), SlackWorkspace("TALPHA/other", "Invalid"),
            SlackWorkspace("TALPHA", "  Work\nshop\u0000  "), SlackWorkspace("TBRAVO", " "),
        ))
        assertEquals(listOf(SlackWorkspace("TALPHA", "Workshop")), merged)
    }

    @Test
    fun `catalog serialization only includes workspace identity and display name`() {
        val json = Json { ignoreUnknownKeys = true }
        val metadata = json.decodeFromString<SlackMetadata>("""{"current":{"id":"TALPHA","name":"Workshop","credential":"invented-secret"},"workspaces":[]}""")
        val saved = json.encodeToString(listOf(metadata.current!!))
        assertEquals("""[{"id":"TALPHA","name":"Workshop"}]""", saved)
        assertFalse(saved.contains("credential"))
    }
}
