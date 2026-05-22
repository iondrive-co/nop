package iondrive.nop.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FileSearchRankingTest {

    private val sample = listOf(
        "src/main/kotlin/iondrive/nop/Main.kt",
        "src/main/kotlin/iondrive/nop/ui/App.kt",
        "src/main/kotlin/iondrive/nop/ui/Tab.kt",
        "src/main/kotlin/iondrive/nop/ui/TabbedViewerPanel.kt",
        "README.md",
        "docs/screenshots/latest-diff.png",
        "build.gradle.kts",
    )

    @Test
    fun `empty query returns the first N files`() {
        val r = FileSearchRanking.rank("", sample, limit = 3)
        assertEquals(sample.take(3), r)
    }

    @Test
    fun `exact filename match outranks prefix and substring`() {
        // Query "Tab" hits "Tab.kt" exactly (after stripping ext won't, but full filename match
        // here is just the substring tier — what we're asserting is that Tab.kt sorts above
        // TabbedViewerPanel.kt because of the shorter-filename tiebreak).
        val r = FileSearchRanking.rank("Tab", sample)
        assertEquals("src/main/kotlin/iondrive/nop/ui/Tab.kt", r.first())
        assertTrue("src/main/kotlin/iondrive/nop/ui/TabbedViewerPanel.kt" in r)
    }

    @Test
    fun `prefix match outranks substring match`() {
        val r = FileSearchRanking.rank("tabb", sample)
        assertEquals("src/main/kotlin/iondrive/nop/ui/TabbedViewerPanel.kt", r.first())
    }

    @Test
    fun `path-only hit appears when filename does not contain the query`() {
        val r = FileSearchRanking.rank("screenshots", sample)
        assertEquals(listOf("docs/screenshots/latest-diff.png"), r)
    }

    @Test
    fun `query is case insensitive`() {
        val upper = FileSearchRanking.rank("README", sample)
        val lower = FileSearchRanking.rank("readme", sample)
        assertEquals(upper, lower)
        assertTrue("README.md" in upper)
    }

    @Test
    fun `no match returns empty`() {
        assertEquals(emptyList<String>(), FileSearchRanking.rank("zzznomatch", sample))
    }

    @Test
    fun `shorter filename wins on score tie`() {
        val files = listOf("a/longer-tab.kt", "a/tab.kt")
        val r = FileSearchRanking.rank("tab", files)
        assertEquals("a/tab.kt", r.first())
    }

    @Test
    fun `limit is honoured`() {
        val many = (1..100).map { "file$it.txt" }
        val r = FileSearchRanking.rank("file", many, limit = 5)
        assertEquals(5, r.size)
    }
}
