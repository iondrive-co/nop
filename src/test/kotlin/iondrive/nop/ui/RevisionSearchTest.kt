package iondrive.nop.ui

import iondrive.nop.git.CommitInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RevisionSearchTest {

    private fun commit(sha: String, message: String, author: String) =
        CommitInfo(sha = sha, author = author, whenEpochSeconds = 1_700_000_000L, shortMessage = message)

    private val log = listOf(
        commit("b09a25a656445718a494da86beba0f623e78ce56", "Fix the markdown preview split", "miles"),
        commit("1f2e3d4c5b6a79880011223344556677889900aa", "Resizable markdown preview", "Ada Lovelace"),
        commit("cafebabe00112233445566778899aabbccddeeff", "Release v0.52.0", "miles"),
    )

    @Test
    fun `an empty query keeps the whole log in order`() {
        assertEquals(log, RevisionSearch.filter("", log))
        assertEquals(log, RevisionSearch.filter("   ", log))
    }

    @Test
    fun `a pasted sha prefix narrows to that one commit`() {
        assertEquals(listOf(log[0]), RevisionSearch.filter("b09a25a", log))
        // The full sha matches too, though only the short form is on screen.
        assertEquals(listOf(log[2]), RevisionSearch.filter(log[2].sha, log))
    }

    @Test
    fun `message and author both match, case-insensitively`() {
        assertEquals(listOf(log[0], log[1]), RevisionSearch.filter("MARKDOWN", log))
        assertEquals(listOf(log[1]), RevisionSearch.filter("lovelace", log))
    }

    @Test
    fun `every term has to match, in any order`() {
        assertEquals(listOf(log[0]), RevisionSearch.filter("markdown miles", log))
        assertEquals(listOf(log[0]), RevisionSearch.filter("miles markdown", log))
        // "preview" is in two commits, but only one of them is Ada's.
        assertEquals(listOf(log[1]), RevisionSearch.filter("preview ada", log))
    }

    @Test
    fun `a query nothing matches comes back empty rather than unfiltered`() {
        assertTrue(RevisionSearch.filter("kubernetes", log).isEmpty())
    }
}
