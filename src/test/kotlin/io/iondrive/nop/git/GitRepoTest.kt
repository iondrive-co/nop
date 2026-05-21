package io.iondrive.nop.git

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
