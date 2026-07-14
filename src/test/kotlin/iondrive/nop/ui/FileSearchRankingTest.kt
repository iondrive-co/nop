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

    @Test
    fun `empty query surfaces most-frequently-accessed files in the top slots`() {
        val counts = mapOf(
            "README.md" to 5,
            "build.gradle.kts" to 3,
            "src/main/kotlin/iondrive/nop/ui/App.kt" to 8,
        )
        val r = FileSearchRanking.rank("", sample, counts)
        assertEquals(
            listOf(
                "src/main/kotlin/iondrive/nop/ui/App.kt",
                "README.md",
                "build.gradle.kts",
            ),
            r.take(3),
        )
        // The rest of the index still appears, deduped, so nothing is lost.
        assertEquals(sample.size, r.size)
        assertEquals(sample.toSet(), r.toSet())
    }

    @Test
    fun `empty query reserves at most three slots for frequent files`() {
        val counts = mapOf(
            "README.md" to 5,
            "build.gradle.kts" to 4,
            "src/main/kotlin/iondrive/nop/Main.kt" to 3,
            "src/main/kotlin/iondrive/nop/ui/App.kt" to 2,
        )
        val top3 = FileSearchRanking.rank("", sample, counts).take(3)
        assertEquals(
            listOf("README.md", "build.gradle.kts", "src/main/kotlin/iondrive/nop/Main.kt"),
            top3,
        )
        // The 4th most-accessed file is not force-promoted; it keeps its natural position.
        assertTrue("src/main/kotlin/iondrive/nop/ui/App.kt" !in top3)
    }

    @Test
    fun `empty query ignores accessed files no longer in the index`() {
        val counts = mapOf("deleted/old.kt" to 99, "README.md" to 2)
        val r = FileSearchRanking.rank("", sample, counts)
        assertEquals("README.md", r.first())
        assertTrue("deleted/old.kt" !in r)
        assertEquals(sample.size, r.size)
    }

    @Test
    fun `empty query with no access history returns the head unchanged`() {
        assertEquals(sample.take(3), FileSearchRanking.rank("", sample, emptyMap(), limit = 3))
    }

    @Test
    fun `frequent returns most-accessed files capped at TOP_SLOTS, index-only`() {
        val counts = mapOf(
            "README.md" to 5,
            "build.gradle.kts" to 4,
            "src/main/kotlin/iondrive/nop/Main.kt" to 3,
            "src/main/kotlin/iondrive/nop/ui/App.kt" to 2,
            "deleted/old.kt" to 99,
        )
        val f = FileSearchRanking.frequent(sample, counts)
        assertEquals(FileSearchRanking.TOP_SLOTS, f.size)
        assertEquals(
            listOf("README.md", "build.gradle.kts", "src/main/kotlin/iondrive/nop/Main.kt"),
            f,
        )
        assertTrue("deleted/old.kt" !in f)
    }

    @Test
    fun `frequent is empty without access history`() {
        assertTrue(FileSearchRanking.frequent(sample, emptyMap()).isEmpty())
    }

    @Test
    fun `access count breaks ties within a score bucket`() {
        // Both are prefix matches for "tab" (equal score). Without counts, the shorter name wins;
        // a higher access count on the longer name pulls it ahead.
        val files = listOf("a/tab.kt", "a/tabx.kt")
        assertEquals("a/tab.kt", FileSearchRanking.rank("tab", files).first())
        assertEquals("a/tabx.kt", FileSearchRanking.rank("tab", files, mapOf("a/tabx.kt" to 3)).first())
    }
}
