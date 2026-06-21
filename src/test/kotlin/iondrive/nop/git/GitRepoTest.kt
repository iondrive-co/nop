package iondrive.nop.git

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class GitRepoTest {
    @Test
    fun `discover returns null for non-git directory`(@TempDir tmp: Path) {
        val repo = GitRepo.discover(tmp)
        assertNull(repo)
    }

    @Test
    fun `loadStatus reports modified untracked added removed`(@TempDir tmp: Path) {
        runShell(tmp, "git init -q && git config user.email t@x && git config user.name T")

        // Initial commit so we have HEAD
        (tmp / "kept.txt").writeText("kept\n")
        (tmp / "to-modify.txt").writeText("v1\n")
        (tmp / "to-delete.txt").writeText("bye\n")
        runShell(tmp, "git add -A && git commit -q -m init")

        // Now create the four kinds of change
        (tmp / "to-modify.txt").writeText("v2\n")             // MODIFIED
        (tmp / "added.txt").writeText("new staged\n")
        runShell(tmp, "git add added.txt")                    // ADDED (staged)
        runShell(tmp, "git rm -q to-delete.txt")              // REMOVED
        (tmp / "untracked.txt").writeText("?\n")              // UNTRACKED

        val repo = GitRepo.discover(tmp)
        assertNotNull(repo)
        val status = repo!!.loadStatus()
        repo.close()

        val byPath = status.byPath
        assertEquals(ChangeKind.MODIFIED, byPath["to-modify.txt"], "to-modify.txt should be modified")
        assertEquals(ChangeKind.ADDED, byPath["added.txt"], "added.txt should be added")
        assertEquals(ChangeKind.REMOVED, byPath["to-delete.txt"], "to-delete.txt should be removed")
        assertEquals(ChangeKind.UNTRACKED, byPath["untracked.txt"], "untracked.txt should be untracked")
        assertTrue(status.changes.size >= 4, "expected >=4 changes, got ${status.changes}")
    }

    @Test
    fun `stageAndCommit stages selected changes and produces a clean status`(@TempDir tmp: Path) {
        runShell(tmp, "git init -q && git config user.email t@x && git config user.name T")
        (tmp / "a.txt").writeText("a\n")
        runShell(tmp, "git add -A && git commit -q -m init")

        // Make two changes; commit one of them
        (tmp / "a.txt").writeText("a-modified\n")
        (tmp / "b.txt").writeText("new file\n")

        val repo = GitRepo.discover(tmp)!!
        val before = repo.loadStatus()
        assertEquals(2, before.changes.size)

        // Commit only a.txt
        val onlyA = before.changes.filter { it.path == "a.txt" }
        val sha = repo.stageAndCommit("touch a", onlyA)
        assertTrue(sha.isNotEmpty(), "expected commit sha")

        val after = repo.loadStatus()
        // b.txt should remain untracked; a.txt committed
        assertEquals(1, after.changes.size)
        assertEquals(ChangeKind.UNTRACKED, after.byPath["b.txt"])

        // Verify the committed file content reads back via HEAD
        val headContent = repo.readHeadContent("a.txt")
        assertEquals("a-modified\n", headContent)
        repo.close()
    }

    @Test
    fun `readHeadContent returns null for path not in HEAD`(@TempDir tmp: Path) {
        runShell(tmp, "git init -q && git config user.email t@x && git config user.name T")
        (tmp / "a.txt").writeText("a\n")
        runShell(tmp, "git add -A && git commit -q -m init")

        val repo = GitRepo.discover(tmp)!!
        assertNull(repo.readHeadContent("never-existed.txt"))
        repo.close()
    }

    @Test
    fun `stash create then pop round-trips changes through the shelf`(@TempDir tmp: Path) {
        runShell(tmp, "git init -q && git config user.email t@x && git config user.name T")
        (tmp / "kept.txt").writeText("kept\n")
        (tmp / "edit.txt").writeText("v1\n")
        runShell(tmp, "git add -A && git commit -q -m init")

        // Local change to stash
        (tmp / "edit.txt").writeText("v2\n")
        (tmp / "new.txt").writeText("untracked\n")

        val repo = GitRepo.discover(tmp)!!
        assertEquals(2, repo.loadStatus().changes.size, "dirty before stash")
        val sha = repo.stashCreate("wip — local edit")
        assertNotNull(sha)
        assertTrue(repo.loadStatus().isClean, "clean after stash")

        val shelf = repo.stashList()
        assertEquals(1, shelf.size)
        assertTrue(shelf[0].message.contains("wip"), "stash message preserved; got '${shelf[0].message}'")

        repo.stashPop(shelf[0])
        assertEquals(2, repo.loadStatus().changes.size, "changes restored after pop")
        assertTrue(repo.stashList().isEmpty(), "shelf empty after pop")
        repo.close()
    }

    @Test
    fun `stash create with nothing to stash returns null`(@TempDir tmp: Path) {
        runShell(tmp, "git init -q && git config user.email t@x && git config user.name T")
        (tmp / "a.txt").writeText("a\n")
        runShell(tmp, "git add -A && git commit -q -m init")

        val repo = GitRepo.discover(tmp)!!
        assertEquals(null, repo.stashCreate("nothing"), "no changes -> no stash")
        repo.close()
    }

    @Test
    fun `stash drop removes the entry without applying`(@TempDir tmp: Path) {
        runShell(tmp, "git init -q && git config user.email t@x && git config user.name T")
        (tmp / "a.txt").writeText("v1\n")
        runShell(tmp, "git add -A && git commit -q -m init")
        (tmp / "a.txt").writeText("v2\n")

        val repo = GitRepo.discover(tmp)!!
        repo.stashCreate("toss me")
        val shelf = repo.stashList()
        assertEquals(1, shelf.size)

        repo.stashDrop(shelf[0])
        assertTrue(repo.stashList().isEmpty(), "shelf empty after drop")
        assertEquals("v1\n", (tmp / "a.txt").toFile().readText(), "drop should not restore the working tree")
        repo.close()
    }

    @Test
    fun `history returns commits touching the requested path only`(@TempDir tmp: Path) {
        runShell(tmp, "git init -q && git config user.email t@x && git config user.name T")
        (tmp / "a.txt").writeText("a1\n")
        (tmp / "b.txt").writeText("b1\n")
        runShell(tmp, "git add -A && git commit -q -m 'init both'")

        (tmp / "a.txt").writeText("a2\n")
        runShell(tmp, "git add a.txt && git commit -q -m 'tweak a'")

        (tmp / "b.txt").writeText("b2\n")
        runShell(tmp, "git add b.txt && git commit -q -m 'tweak b'")

        val repo = GitRepo.discover(tmp)!!
        val aLog = repo.history("a.txt")
        val all = repo.history(null)
        repo.close()

        assertEquals(listOf("tweak a", "init both"), aLog.map { it.shortMessage },
            "a.txt history should only include commits that touch a.txt")
        assertEquals(listOf("tweak b", "tweak a", "init both"), all.map { it.shortMessage },
            "no-path history should include every commit")
        assertEquals(7, aLog[0].shortSha.length, "shortSha is the first 7 chars of the SHA")
    }

    @Test
    fun `clean repo reports no changes`(@TempDir tmp: Path) {
        runShell(tmp, "git init -q && git config user.email t@x && git config user.name T")
        (tmp / "a.txt").writeText("a\n")
        runShell(tmp, "git add -A && git commit -q -m init")

        val repo = GitRepo.discover(tmp)!!
        val status = repo.loadStatus()
        repo.close()

        assertTrue(status.isClean, "expected clean, got ${status.changes}")
    }

    @Test
    fun `loadStatus picks up files added after an earlier snapshot`(@TempDir tmp: Path) {
        runShell(tmp, "git init -q && git config user.email t@x && git config user.name T")
        (tmp / "a.txt").writeText("a\n")
        runShell(tmp, "git add -A && git commit -q -m init")
        (tmp / "a.txt").writeText("modified\n")

        val repo = GitRepo.discover(tmp)!!
        val snapshot = repo.loadStatus()
        assertEquals(setOf("a.txt"), snapshot.byPath.keys, "only a.txt known at snapshot time")

        // New file appears after the snapshot (simulates working while nop hasn't refreshed)
        (tmp / "b.txt").writeText("new\n")

        val fresh = repo.loadStatus()
        val newPaths = fresh.changes.map { it.path }.toSet() - snapshot.changes.map { it.path }.toSet()
        assertEquals(setOf("b.txt"), newPaths, "fresh load must detect the newly appeared file")
        repo.close()
    }

    @Test
    fun `softResetHead un-commits the last commit but keeps its changes staged`(@TempDir tmp: Path) {
        runShell(tmp, "git init -q && git config user.email t@x && git config user.name T")
        (tmp / "a.txt").writeText("a\n")
        runShell(tmp, "git add -A && git commit -q -m root")
        (tmp / "b.txt").writeText("b\n")
        runShell(tmp, "git add -A && git commit -q -m 'add b'")

        val repo = GitRepo.discover(tmp)!!
        assertTrue(repo.canSoftResetHead(), "HEAD has a parent before reset")
        assertTrue(repo.loadStatus().isClean, "clean working tree before reset")

        assertTrue(repo.softResetHead(), "soft reset back one revision succeeds")

        val after = repo.loadStatus()
        assertEquals(ChangeKind.ADDED, after.byPath["b.txt"], "b.txt returns as a staged addition")
        assertEquals("b\n", (tmp / "b.txt").toFile().readText(), "working-tree file is left untouched")
        assertNull(repo.readHeadContent("b.txt"), "b.txt is no longer part of HEAD")
        assertEquals(listOf("root"), repo.recentCommitMessages(), "log no longer shows the undone commit")
        repo.close()
    }

    @Test
    fun `softResetHead is a no-op on a root commit`(@TempDir tmp: Path) {
        runShell(tmp, "git init -q && git config user.email t@x && git config user.name T")
        (tmp / "a.txt").writeText("a\n")
        runShell(tmp, "git add -A && git commit -q -m root")

        val repo = GitRepo.discover(tmp)!!
        assertEquals(false, repo.canSoftResetHead(), "a root commit has no parent")
        assertEquals(false, repo.softResetHead(), "soft reset refuses when there is no parent")
        assertTrue(repo.loadStatus().isClean, "nothing changed")
        repo.close()
    }

    @Test
    fun `recentCommitMessages returns messages newest first and de-duplicated`(@TempDir tmp: Path) {
        runShell(tmp, "git init -q && git config user.email t@x && git config user.name T")
        (tmp / "a.txt").writeText("1\n")
        runShell(tmp, "git add -A && git commit -q -m first")
        (tmp / "a.txt").writeText("2\n")
        runShell(tmp, "git add -A && git commit -q -m second")
        (tmp / "a.txt").writeText("3\n")
        runShell(tmp, "git add -A && git commit -q -m second") // same message as the previous commit
        (tmp / "a.txt").writeText("4\n")
        runShell(tmp, "git add -A && git commit -q -m third")

        val repo = GitRepo.discover(tmp)!!
        val msgs = repo.recentCommitMessages()
        repo.close()

        assertEquals(listOf("third", "second", "first"), msgs,
            "newest first, with the repeated 'second' collapsed to one entry")
    }

    @Test
    fun `recentCommitMessages and canSoftResetHead are safe on an unborn branch`(@TempDir tmp: Path) {
        runShell(tmp, "git init -q && git config user.email t@x && git config user.name T")
        val repo = GitRepo.discover(tmp)!!
        assertTrue(repo.recentCommitMessages().isEmpty(), "no commits -> no messages")
        assertEquals(false, repo.canSoftResetHead(), "unborn HEAD has no parent")
        repo.close()
    }

    @Test
    fun `revertFile restores a modified file to its HEAD content`(@TempDir tmp: Path) {
        runShell(tmp, "git init -q && git config user.email t@x && git config user.name T")
        (tmp / "a.txt").writeText("v1\n")
        runShell(tmp, "git add -A && git commit -q -m init")
        (tmp / "a.txt").writeText("v2-local-edit\n")

        val repo = GitRepo.discover(tmp)!!
        assertEquals(ChangeKind.MODIFIED, repo.loadStatus().byPath["a.txt"], "modified before revert")

        repo.revertFile(FileChange("a.txt", ChangeKind.MODIFIED))

        assertEquals("v1\n", (tmp / "a.txt").toFile().readText(), "working tree restored to HEAD")
        assertTrue(repo.loadStatus().isClean, "clean after revert")
        repo.close()
    }

    @Test
    fun `revertFile also discards a staged modification`(@TempDir tmp: Path) {
        runShell(tmp, "git init -q && git config user.email t@x && git config user.name T")
        (tmp / "a.txt").writeText("v1\n")
        runShell(tmp, "git add -A && git commit -q -m init")
        // Modified and staged, then modified again in the working tree.
        (tmp / "a.txt").writeText("staged\n")
        runShell(tmp, "git add a.txt")
        (tmp / "a.txt").writeText("staged-plus-more\n")

        val repo = GitRepo.discover(tmp)!!
        repo.revertFile(FileChange("a.txt", ChangeKind.MODIFIED))

        assertEquals("v1\n", (tmp / "a.txt").toFile().readText(), "both staged and working changes discarded")
        assertTrue(repo.loadStatus().isClean, "index and working tree both clean after revert")
        repo.close()
    }

    @Test
    fun `revertFile deletes a staged new file`(@TempDir tmp: Path) {
        runShell(tmp, "git init -q && git config user.email t@x && git config user.name T")
        (tmp / "a.txt").writeText("a\n")
        runShell(tmp, "git add -A && git commit -q -m init")
        (tmp / "added.txt").writeText("new\n")
        runShell(tmp, "git add added.txt")

        val repo = GitRepo.discover(tmp)!!
        assertEquals(ChangeKind.ADDED, repo.loadStatus().byPath["added.txt"], "added before revert")

        repo.revertFile(FileChange("added.txt", ChangeKind.ADDED))

        assertTrue(!(tmp / "added.txt").toFile().exists(), "new file deleted from disk")
        assertTrue(repo.loadStatus().isClean, "clean after revert — nothing left staged")
        repo.close()
    }

    @Test
    fun `revertFile deletes an untracked file`(@TempDir tmp: Path) {
        runShell(tmp, "git init -q && git config user.email t@x && git config user.name T")
        (tmp / "a.txt").writeText("a\n")
        runShell(tmp, "git add -A && git commit -q -m init")
        (tmp / "junk.txt").writeText("scratch\n")

        val repo = GitRepo.discover(tmp)!!
        assertEquals(ChangeKind.UNTRACKED, repo.loadStatus().byPath["junk.txt"], "untracked before revert")

        repo.revertFile(FileChange("junk.txt", ChangeKind.UNTRACKED))

        assertTrue(!(tmp / "junk.txt").toFile().exists(), "untracked file deleted")
        assertTrue(repo.loadStatus().isClean, "clean after revert")
        repo.close()
    }

    @Test
    fun `revertFile restores a file staged for removal`(@TempDir tmp: Path) {
        runShell(tmp, "git init -q && git config user.email t@x && git config user.name T")
        (tmp / "gone.txt").writeText("keep me\n")
        runShell(tmp, "git add -A && git commit -q -m init")
        runShell(tmp, "git rm -q gone.txt")

        val repo = GitRepo.discover(tmp)!!
        assertEquals(ChangeKind.REMOVED, repo.loadStatus().byPath["gone.txt"], "staged for removal before revert")

        repo.revertFile(FileChange("gone.txt", ChangeKind.REMOVED))

        assertEquals("keep me\n", (tmp / "gone.txt").toFile().readText(), "file restored from HEAD")
        assertTrue(repo.loadStatus().isClean, "clean after revert")
        repo.close()
    }

    @Test
    fun `revertFile restores a file deleted from the working tree`(@TempDir tmp: Path) {
        runShell(tmp, "git init -q && git config user.email t@x && git config user.name T")
        (tmp / "gone.txt").writeText("keep me\n")
        runShell(tmp, "git add -A && git commit -q -m init")
        (tmp / "gone.txt").toFile().delete() // deleted but not staged -> MISSING

        val repo = GitRepo.discover(tmp)!!
        assertEquals(ChangeKind.MISSING, repo.loadStatus().byPath["gone.txt"], "missing before revert")

        repo.revertFile(FileChange("gone.txt", ChangeKind.MISSING))

        assertEquals("keep me\n", (tmp / "gone.txt").toFile().readText(), "file restored from HEAD")
        assertTrue(repo.loadStatus().isClean, "clean after revert")
        repo.close()
    }

    private operator fun Path.div(name: String): Path = resolve(name)

    private fun runShell(cwd: Path, cmd: String) {
        cwd.createDirectories()
        val proc = ProcessBuilder("sh", "-c", cmd)
            .directory(cwd.toFile())
            .redirectErrorStream(true)
            .start()
        val out = proc.inputStream.bufferedReader().readText()
        val exit = proc.waitFor()
        check(exit == 0) { "Command failed (exit=$exit): $cmd\n$out" }
    }
}
