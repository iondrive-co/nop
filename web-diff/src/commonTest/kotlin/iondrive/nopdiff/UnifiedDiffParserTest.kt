package iondrive.nopdiff

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UnifiedDiffParserTest {

    private val twoFileDiff = listOf(
        "diff --git a/src/Main.kt b/src/Main.kt",
        "index 1234567..89abcde 100644",
        "--- a/src/Main.kt",
        "+++ b/src/Main.kt",
        "@@ -1,4 +1,4 @@ fun main()",
        " package app",
        " ",
        "-val x = 1",
        "+val x = 2",
        " println(x)",
        "diff --git a/README.md b/README.md",
        "new file mode 100644",
        "index 0000000..e69de29",
        "--- /dev/null",
        "+++ b/README.md",
        "@@ -0,0 +1,2 @@",
        "+# Title",
        "+body",
    ).joinToString("\n")

    @Test
    fun parsesTwoFiles() {
        val files = UnifiedDiffParser.parse(twoFileDiff)
        assertEquals(2, files.size)
    }

    @Test
    fun extractsPathsAndStripsPrefixes() {
        val files = UnifiedDiffParser.parse(twoFileDiff)
        assertEquals("src/Main.kt", files[0].oldPath)
        assertEquals("src/Main.kt", files[0].newPath)
        assertEquals("src/Main.kt", files[0].displayPath)
        assertEquals("kt", files[0].extension)
        assertFalse(files[0].isNew)
    }

    @Test
    fun parsesHunkHeaderCounts() {
        val h = UnifiedDiffParser.parse(twoFileDiff)[0].hunks.single()
        assertEquals(1, h.oldStart)
        assertEquals(4, h.oldCount)
        assertEquals(1, h.newStart)
        assertEquals(4, h.newCount)
    }

    @Test
    fun assignsLineNumbersAcrossContextAddDelete() {
        val lines = UnifiedDiffParser.parse(twoFileDiff)[0].hunks.single().lines
        // package(ctx) / blank(ctx) / del / add / println(ctx)
        assertEquals(LineType.CONTEXT, lines[0].type)
        assertEquals(1, lines[0].oldLine); assertEquals(1, lines[0].newLine)
        assertEquals("", lines[1].content)               // blank context line keeps its space-stripped content
        assertEquals(LineType.DELETE, lines[2].type)
        assertEquals(3, lines[2].oldLine); assertNull(lines[2].newLine)
        assertEquals(LineType.ADD, lines[3].type)
        assertNull(lines[3].oldLine); assertEquals(3, lines[3].newLine)
        assertEquals(LineType.CONTEXT, lines[4].type)
        assertEquals(4, lines[4].oldLine); assertEquals(4, lines[4].newLine)
    }

    @Test
    fun countsAddedAndDeleted() {
        val f = UnifiedDiffParser.parse(twoFileDiff)[0]
        assertEquals(1, f.addedLines)
        assertEquals(1, f.deletedLines)
    }

    @Test
    fun detectsNewFile() {
        val readme = UnifiedDiffParser.parse(twoFileDiff)[1]
        assertTrue(readme.isNew)
        assertEquals("README.md", readme.newPath)
        assertEquals(2, readme.addedLines)
        assertTrue(readme.hunks.single().lines.all { it.type == LineType.ADD })
    }

    @Test
    fun detectsDeletedFile() {
        val diff = listOf(
            "diff --git a/gone.txt b/gone.txt",
            "deleted file mode 100644",
            "--- a/gone.txt",
            "+++ /dev/null",
            "@@ -1,2 +0,0 @@",
            "-line one",
            "-line two",
        ).joinToString("\n")
        val f = UnifiedDiffParser.parse(diff).single()
        assertTrue(f.isDeleted)
        assertEquals("gone.txt", f.displayPath)
        assertEquals(2, f.deletedLines)
    }

    @Test
    fun detectsBinaryFile() {
        val diff = listOf(
            "diff --git a/logo.png b/logo.png",
            "index 1111111..2222222 100644",
            "Binary files a/logo.png and b/logo.png differ",
        ).joinToString("\n")
        val f = UnifiedDiffParser.parse(diff).single()
        assertTrue(f.isBinary)
        assertTrue(f.hunks.isEmpty())
    }

    @Test
    fun handlesOmittedHunkCounts() {
        // `@@ -1 +1 @@` means count 1 on each side
        val diff = listOf(
            "--- a/f",
            "+++ b/f",
            "@@ -1 +1 @@",
            "-old",
            "+new",
        ).joinToString("\n")
        val h = UnifiedDiffParser.parse(diff).single().hunks.single()
        assertEquals(1, h.oldCount)
        assertEquals(1, h.newCount)
    }

    @Test
    fun emptyInputYieldsNoFiles() {
        assertTrue(UnifiedDiffParser.parse("").isEmpty())
    }

    @Test
    fun bareEmptyLineTreatedAsBlankContextWhenCountsExpectMore() {
        // A blank context line that lost its leading space must not truncate the hunk.
        val diff = listOf(
            "--- a/f", "+++ b/f",
            "@@ -1,3 +1,3 @@",
            " a",
            "",          // blank context, no leading space
            "-b",
            "+c",
        ).joinToString("\n")
        val lines = UnifiedDiffParser.parse(diff).single().hunks.single().lines
        assertEquals(4, lines.size)
        assertEquals(LineType.CONTEXT, lines[1].type)
        assertEquals("", lines[1].content)
        assertEquals(LineType.DELETE, lines[2].type)
        assertEquals(LineType.ADD, lines[3].type)
    }

    @Test
    fun countBoundedHunkDoesNotSwallowNextPlainFileHeader() {
        // Two plain (non-git) unified files; the first hunk must stop at its counts so the second
        // file's "--- a/two" isn't misread as a deleted line.
        val diff = listOf(
            "--- a/one", "+++ b/one",
            "@@ -1 +1 @@",
            "-x",
            "+y",
            "--- a/two", "+++ b/two",
            "@@ -1 +1 @@",
            "-p",
            "+q",
        ).joinToString("\n")
        val files = UnifiedDiffParser.parse(diff)
        assertEquals(2, files.size)
        assertEquals("one", files[0].displayPath)
        assertEquals("two", files[1].displayPath)
        assertEquals(1, files[0].hunks.single().lines.count { it.type == LineType.DELETE })
    }
}
