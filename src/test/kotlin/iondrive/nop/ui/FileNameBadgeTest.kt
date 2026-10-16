package iondrive.nop.ui

import iondrive.nop.git.ChangeKind
import iondrive.nop.git.CommitFile
import iondrive.nop.git.CommitFileChange
import iondrive.nop.git.FileChange
import iondrive.nop.git.GitRepo
import org.eclipse.jgit.api.Git
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class FileNameBadgeTest {

    @Test
    fun `a file in the repository is named by its path inside it`(@TempDir dir: File) {
        Git.init().setDirectory(dir).call().close()
        val file = File(dir, "src/app/Main.kt").apply { parentFile.mkdirs(); writeText("") }
        val repo = GitRepo.discover(dir.toPath())!!
        try {
            assertEquals("src/app/Main.kt" to false, fileNameOf(Tab.FileView(file), repo))
            assertEquals("src/app/Main.kt" to false, fileNameOf(Tab.RevisionDiff(file, "abc1234", "abc1234", dir), repo))
            assertEquals("src/app/Main.kt" to false, fileNameOf(Tab.LocalDiff(file, 1_000L), repo))
        } finally {
            repo.close()
        }
    }

    @Test
    fun `a file outside any repository is named by its full path`(@TempDir dir: File) {
        val (path, isNew) = fileNameOf(Tab.FileView(File(dir, "notes.md")), repo = null)!!
        // Absolute, or `~`-relative when the temp directory happens to be under home: never cut short.
        assertTrue(path.startsWith("/") || path.startsWith("~/"), path)
        assertTrue(path.endsWith("/${dir.name}/notes.md"), path)
        assertFalse(isNew)
    }

    @Test
    fun `a working-tree diff is new when the file is added or untracked`(@TempDir dir: File) {
        fun diff(kind: ChangeKind) = fileNameOf(Tab.Diff(FileChange("lib/Util.kt", kind), dir), repo = null)
        assertEquals("lib/Util.kt" to true, diff(ChangeKind.ADDED))
        assertEquals("lib/Util.kt" to true, diff(ChangeKind.UNTRACKED))
        assertEquals("lib/Util.kt" to false, diff(ChangeKind.MODIFIED))
        assertEquals("lib/Util.kt" to false, diff(ChangeKind.REMOVED))
    }

    @Test
    fun `a commit diff is new when the commit added the file`(@TempDir dir: File) {
        fun diff(change: CommitFileChange) =
            fileNameOf(Tab.CommitDiff("abc1234", "abc1234", CommitFile("lib/Util.kt", change), dir), repo = null)
        assertEquals("lib/Util.kt" to true, diff(CommitFileChange.ADDED))
        assertEquals("lib/Util.kt" to false, diff(CommitFileChange.MODIFIED))
        assertEquals("lib/Util.kt" to false, diff(CommitFileChange.DELETED))
    }

    @Test
    fun `a local history list has no badge`(@TempDir dir: File) {
        assertNull(fileNameOf(Tab.LocalHistory(File(dir, "a.kt")), repo = null))
    }
}
