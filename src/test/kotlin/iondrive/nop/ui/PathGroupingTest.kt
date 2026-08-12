package iondrive.nop.ui

import iondrive.nop.git.ChangeKind
import iondrive.nop.git.FileChange
import iondrive.nop.index.SearchHit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PathGroupingTest {

    /** Groups bare paths — the bucketing only reads paths, so a path stands in for its own item. */
    private fun group(vararg paths: String): List<PathGroup<String>> =
        PathGrouping.group(paths.toList()) { it }

    private fun titles(groups: List<PathGroup<*>>) = groups.map { it.title }

    private fun <T> group(groups: List<PathGroup<T>>, title: String): PathGroup<T> =
        groups.single { it.title == title }

    @Test
    fun `empty input produces no groups`() {
        assertEquals(emptyList<PathGroup<String>>(), PathGrouping.group(emptyList<String>()) { it })
    }

    @Test
    fun `tests config and docs split from source, source grouped by directory`() {
        val groups = group(
            "src/main/kotlin/iondrive/nop/ui/CommitPanel.kt",
            "src/main/kotlin/iondrive/nop/ui/PathGrouping.kt",
            "src/main/kotlin/iondrive/nop/git/GitStatus.kt",
            "src/test/kotlin/iondrive/nop/ui/PathGroupingTest.kt",
            "build.gradle.kts",
            "README.md",
        )
        assertEquals(listOf("ui", "git", "tests", "config", "docs"), titles(groups))
        assertEquals(2, group(groups, "ui").items.size)
        assertEquals(1, group(groups, "git").items.size)
    }

    @Test
    fun `source groups ordered by size then title, before the special groups`() {
        val groups = group(
            "a/one/File1.kt",
            "a/two/File2.kt",
            "a/two/File3.kt",
            "build.gradle.kts",
        )
        assertEquals(listOf("two", "one", "config"), titles(groups))
    }

    @Test
    fun `test files detected by directory and by filename convention`() {
        val groups = group(
            "src/test/kotlin/FooTest.kt",
            "web/src/components/Button.spec.ts",
            "web/src/components/list.test.ts",
            "scripts/test_runner.py",
            "pkg/parse_test.go",
            "src/commonTest/kotlin/BarTest.kt",
        )
        assertEquals(listOf("tests"), titles(groups))
    }

    @Test
    fun `latest and similar names are not mistaken for tests`() {
        assertEquals(listOf("app"), titles(group("src/main/kotlin/app/latest.kt")))
    }

    @Test
    fun `config detected by filename, extension, and dot directories`() {
        val groups = group(
            "settings.gradle.kts",
            "gradle.properties",
            ".github/workflows/ci.yml",
            ".gitignore",
            "package-lock.json",
            "Dockerfile",
        )
        assertEquals(listOf("config"), titles(groups))
        assertEquals(6, group(groups, "config").items.size)
    }

    @Test
    fun `docs detected by extension and docs directory`() {
        assertEquals(listOf("docs"), titles(group("README.md", "docs/screenshots/notes.html")))
    }

    @Test
    fun `root level source files fall into other`() {
        assertEquals(listOf("app", "other"), titles(group("Main.kt", "src/main/kotlin/app/App.kt")))
    }

    @Test
    fun `group count is capped with overflow merged into other`() {
        val groups = group(
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
        assertTrue(groups.size <= PathGrouping.MAX_GROUPS)
        assertEquals(listOf("d1", "d2", "other", "tests", "config", "docs"), titles(groups))
        // d3..d7 (5 single/double-file groups beyond the cap) all land in "other".
        assertEquals(6, group(groups, PathGrouping.OTHER_TITLE).items.size)
        // Nothing is dropped.
        assertEquals(14, groups.sumOf { it.items.size })
    }

    @Test
    fun `source directory named like a special group merges with it`() {
        val groups = group(
            "src/main/kotlin/app/config/Settings.kt",
            "gradle.properties",
        )
        assertEquals(listOf("config"), titles(groups))
        assertEquals(2, group(groups, "config").items.size)
    }

    @Test
    fun `labels strip the shared directory prefix`() {
        val groups = group(
            "src/main/kotlin/iondrive/nop/ui/CommitPanel.kt",
            "src/main/kotlin/iondrive/nop/ui/PathGrouping.kt",
        )
        val ui = group(groups, "ui")
        assertEquals("src/main/kotlin/iondrive/nop/ui/", ui.commonPrefix)
        assertEquals("CommitPanel.kt", ui.labelFor("src/main/kotlin/iondrive/nop/ui/CommitPanel.kt"))
    }

    @Test
    fun `labels keep full path when members share no directory prefix`() {
        val config = group(group("build.gradle.kts", ".github/workflows/ci.yml"), "config")
        assertEquals("", config.commonPrefix)
        assertEquals(".github/workflows/ci.yml", config.labelFor(".github/workflows/ci.yml"))
    }

    @Test
    fun `headers name the group and count its members`() {
        val groups = group("a/ui/One.kt", "a/ui/Two.kt", "src/test/kotlin/OneTest.kt")
        assertEquals("ui · 2", group(groups, "ui").header)
        assertEquals("tests · 1", group(groups, "tests").header)
    }

    @Test
    fun `grouping git changes keeps each change with its path`() {
        val changes = listOf(
            FileChange("src/main/kotlin/app/App.kt", ChangeKind.MODIFIED),
            FileChange("src/test/kotlin/app/AppTest.kt", ChangeKind.ADDED),
        )
        val groups = PathGrouping.group(changes) { it.path }
        assertEquals(listOf("app", "tests"), titles(groups))
        assertEquals(listOf(changes[0]), group(groups, "app").items)
        assertEquals(listOf("src/test/kotlin/app/AppTest.kt"), group(groups, "tests").paths)
    }

    @Test
    fun `several search hits in one file stay together and each counts`() {
        val hits = listOf(
            hit("src/main/kotlin/app/App.kt", 3),
            hit("src/main/kotlin/app/App.kt", 9),
            hit("src/main/kotlin/app/Util.kt", 1),
            hit("src/test/kotlin/app/AppTest.kt", 4),
        )
        val groups = PathGrouping.group(hits) { it.path }
        assertEquals(listOf("app", "tests"), titles(groups))
        // Three matches across two files in one column — the count is of matches, not files.
        assertEquals("app · 3", group(groups, "app").header)
        assertEquals(hits.take(3), group(groups, "app").items)
        assertEquals("tests · 1", group(groups, "tests").header)
    }

    @Test
    fun `a group's members are ordered by folder then file name`() {
        // The shape a docs repo produces: one big "docs" column spanning a deep tree, arriving in
        // whatever order git status listed it in.
        val groups = group(
            "adnuntius-advertising/admin-ui/inventory/sites.md",
            "SUMMARY.md",
            "adnuntius-advertising/admin-api/endpoints/lineitems.md",
            "adnuntius-self-service/marketing-tips.md",
            "adnuntius-advertising/admin-ui/advertising/line-items.md",
            "adnuntius-advertising/admin-api/endpoints/adunits.md",
            "README.md",
            "adnuntius-advertising/admin-ui/advertising/advertisers.md",
        )
        assertEquals(
            listOf(
                // Root files first (no folder at all), then folder by folder, each folder's files
                // together and alphabetical within it.
                "README.md",
                "SUMMARY.md",
                "adnuntius-advertising/admin-api/endpoints/adunits.md",
                "adnuntius-advertising/admin-api/endpoints/lineitems.md",
                "adnuntius-advertising/admin-ui/advertising/advertisers.md",
                "adnuntius-advertising/admin-ui/advertising/line-items.md",
                "adnuntius-advertising/admin-ui/inventory/sites.md",
                "adnuntius-self-service/marketing-tips.md",
            ),
            group(groups, "docs").paths,
        )
    }

    @Test
    fun `ordering is stable, so a file's search hits keep their line order`() {
        val hits = listOf(
            hit("src/main/kotlin/app/Util.kt", 7),
            hit("src/main/kotlin/app/App.kt", 3),
            hit("src/main/kotlin/app/Util.kt", 2),
            hit("src/main/kotlin/app/Util.kt", 40),
        )
        val groups = PathGrouping.group(hits) { it.path }
        // App.kt's hit sorts ahead of Util.kt's; within Util.kt the hits stay as the scan found them.
        assertEquals(listOf(hits[1], hits[0], hits[2], hits[3]), group(groups, "app").items)
    }

    private fun hit(path: String, line: Int) = SearchHit(path, line, "match on line $line", 0, 5)
}
