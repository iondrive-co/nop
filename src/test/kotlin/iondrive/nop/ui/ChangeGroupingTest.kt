package iondrive.nop.ui

import iondrive.nop.git.ChangeKind
import iondrive.nop.git.FileChange
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChangeGroupingTest {

    private fun changes(vararg paths: String) = paths.map { FileChange(it, ChangeKind.MODIFIED) }

    private fun titles(groups: List<ChangeGroup>) = groups.map { it.title }

    private fun group(groups: List<ChangeGroup>, title: String): ChangeGroup =
        groups.single { it.title == title }

    @Test
    fun `empty input produces no groups`() {
        assertEquals(emptyList<ChangeGroup>(), ChangeGrouping.group(emptyList()))
    }

    @Test
    fun `tests config and docs split from source, source grouped by directory`() {
        val groups = ChangeGrouping.group(
            changes(
                "src/main/kotlin/iondrive/nop/ui/CommitPanel.kt",
                "src/main/kotlin/iondrive/nop/ui/ChangeGrouping.kt",
                "src/main/kotlin/iondrive/nop/git/GitStatus.kt",
                "src/test/kotlin/iondrive/nop/ui/ChangeGroupingTest.kt",
                "build.gradle.kts",
                "README.md",
            )
        )
        assertEquals(listOf("ui", "git", "tests", "config", "docs"), titles(groups))
        assertEquals(2, group(groups, "ui").changes.size)
        assertEquals(1, group(groups, "git").changes.size)
    }

    @Test
    fun `source groups ordered by size then title, before the special groups`() {
        val groups = ChangeGrouping.group(
            changes(
                "a/one/File1.kt",
                "a/two/File2.kt",
                "a/two/File3.kt",
                "build.gradle.kts",
            )
        )
        assertEquals(listOf("two", "one", "config"), titles(groups))
    }

    @Test
    fun `test files detected by directory and by filename convention`() {
        val groups = ChangeGrouping.group(
            changes(
                "src/test/kotlin/FooTest.kt",
                "web/src/components/Button.spec.ts",
                "web/src/components/list.test.ts",
                "scripts/test_runner.py",
                "pkg/parse_test.go",
                "src/commonTest/kotlin/BarTest.kt",
            )
        )
        assertEquals(listOf("tests"), titles(groups))
    }

    @Test
    fun `latest and similar names are not mistaken for tests`() {
        val groups = ChangeGrouping.group(changes("src/main/kotlin/app/latest.kt"))
        assertEquals(listOf("app"), titles(groups))
    }

    @Test
    fun `config detected by filename, extension, and dot directories`() {
        val groups = ChangeGrouping.group(
            changes(
                "settings.gradle.kts",
                "gradle.properties",
                ".github/workflows/ci.yml",
                ".gitignore",
                "package-lock.json",
                "Dockerfile",
            )
        )
        assertEquals(listOf("config"), titles(groups))
        assertEquals(6, group(groups, "config").changes.size)
    }

    @Test
    fun `docs detected by extension and docs directory`() {
        val groups = ChangeGrouping.group(changes("README.md", "docs/screenshots/notes.html"))
        assertEquals(listOf("docs"), titles(groups))
    }

    @Test
    fun `root level source files fall into other`() {
        val groups = ChangeGrouping.group(changes("Main.kt", "src/main/kotlin/app/App.kt"))
        assertEquals(listOf("app", "other"), titles(groups))
    }

    @Test
    fun `group count is capped with overflow merged into other`() {
        val groups = ChangeGrouping.group(
            changes(
                "a/d1/F.kt", "a/d1/G.kt", "a/d1/H.kt",
                "a/d2/F.kt", "a/d2/G.kt",
                "a/d3/F.kt", "a/d3/G.kt",
                "a/d4/F.kt",
                "a/d5/F.kt",
                "a/d6/F.kt",
                "a/d7/F.kt",
                "build.gradle.kts",
                "README.md",
                "src/test/kotlin/FooTest.kt",
            )
        )
        assertTrue(groups.size <= ChangeGrouping.MAX_GROUPS)
        assertEquals(listOf("d1", "d2", "other", "tests", "config", "docs"), titles(groups))
        // d3..d7 (5 single/double-file groups beyond the cap) all land in "other".
        assertEquals(6, group(groups, ChangeGrouping.OTHER_TITLE).changes.size)
        // Nothing is dropped.
        assertEquals(14, groups.sumOf { it.changes.size })
    }

    @Test
    fun `source directory named like a special group merges with it`() {
        val groups = ChangeGrouping.group(
            changes(
                "src/main/kotlin/app/config/Settings.kt",
                "gradle.properties",
            )
        )
        assertEquals(listOf("config"), titles(groups))
        assertEquals(2, group(groups, "config").changes.size)
    }

    @Test
    fun `labels strip the shared directory prefix`() {
        val groups = ChangeGrouping.group(
            changes(
                "src/main/kotlin/iondrive/nop/ui/CommitPanel.kt",
                "src/main/kotlin/iondrive/nop/ui/ChangeGrouping.kt",
            )
        )
        val ui = group(groups, "ui")
        assertEquals("src/main/kotlin/iondrive/nop/ui/", ui.commonPrefix)
        assertEquals("CommitPanel.kt", ui.labelFor("src/main/kotlin/iondrive/nop/ui/CommitPanel.kt"))
    }

    @Test
    fun `labels keep full path when members share no directory prefix`() {
        val groups = ChangeGrouping.group(changes("build.gradle.kts", ".github/workflows/ci.yml"))
        val config = group(groups, "config")
        assertEquals("", config.commonPrefix)
        assertEquals(".github/workflows/ci.yml", config.labelFor(".github/workflows/ci.yml"))
    }
}
