package iondrive.nop.git

import org.eclipse.jgit.api.errors.StashApplyFailureException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class GitRepoTest {
    @Test
    fun `discover returns null for non-git directory`(@TempDir tmp: Path) {
        // A plain @TempDir is not enough on its own: Gradle points the test
        // worker's java.io.tmpdir at <project>/build/tmp/test, which lives inside
        // nop's own git working tree, and the sandbox offers no writable directory
        // outside a git tree. GitRepo.discover would otherwise climb past tmp and
        // (correctly) find nop/.git. So we bound the upward walk at tmp: with no
        // .git in tmp or the empty subtree below it, discover must return null.
        val nested = (tmp / "a" / "b").also { it.createDirectories() }
        assertNull(GitRepo.discover(nested, ceiling = tmp),
            "no .git between $nested and the ceiling $tmp, so discover must return null")
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
    fun `stageAndCommit commits additions and removals together`(@TempDir tmp: Path) {
        runShell(tmp, "git init -q && git config user.email t@x && git config user.name T")
        (tmp / "keep.txt").writeText("keep\n")
        (tmp / "edit.txt").writeText("v1\n")
        (tmp / "staged-delete.txt").writeText("bye\n")
        (tmp / "disk-delete.txt").writeText("also bye\n")
        runShell(tmp, "git add -A && git commit -q -m init")

        // One of every kind that reaches stageAndCommit, so the add half and the rm half both carry
        // several paths — the two are staged as one command apiece, no longer interleaved per file.
        (tmp / "edit.txt").writeText("v2\n")                  // MODIFIED
        (tmp / "fresh.txt").writeText("fresh\n")              // UNTRACKED
        runShell(tmp, "git rm -q staged-delete.txt")          // REMOVED
        (tmp / "disk-delete.txt").toFile().delete()           // MISSING

        val repo = GitRepo.discover(tmp)!!
        val before = repo.loadStatus()
        assertEquals(4, before.changes.size, "expected four pending changes, got ${before.changes}")

        val sha = repo.stageAndCommit("mixed", before.changes)
        assertTrue(sha.isNotEmpty(), "expected commit sha")

        val after = repo.loadStatus()
        assertTrue(after.isClean, "working tree should be clean, still pending: ${after.changes}")
        assertEquals("v2\n", repo.readHeadContent("edit.txt"), "the edit should be committed")
        assertEquals("fresh\n", repo.readHeadContent("fresh.txt"), "the new file should be committed")
        assertNull(repo.readHeadContent("staged-delete.txt"), "the staged deletion should be committed")
        assertNull(repo.readHeadContent("disk-delete.txt"), "the on-disk deletion should be committed")
        assertEquals("keep\n", repo.readHeadContent("keep.txt"), "an untouched file should survive")
        repo.close()
    }

    @Test
    fun `stageAndCommit leaves the index untouched when one path cannot be staged`(@TempDir tmp: Path) {
        runShell(tmp, "git init -q && git config user.email t@x && git config user.name T")
        (tmp / "seed.txt").writeText("seed\n")
        runShell(tmp, "git add -A && git commit -q -m init")

        // A file JGit's walk lists but cannot open stands in for the race that actually bites: a
        // file still there when the tree is enumerated and gone by the time its content is read,
        // because something outside the editor is writing into the same tree. Both surface as the
        // same "Exception caught during execution of add command" / FileNotFoundException.
        // It sits between two healthy paths, so a per-file staging loop would already have
        // published the first one by the time the second throws.
        (tmp / "a-one.txt").writeText("one\n")
        (tmp / "m-locked.txt").writeText("nope\n")
        (tmp / "z-two.txt").writeText("two\n")
        val locked = (tmp / "m-locked.txt").toFile()
        locked.setReadable(false)
        assumeTrue(!locked.canRead(), "cannot make a file unreadable (running as root?)")

        val repo = GitRepo.discover(tmp)!!
        try {
            val pending = repo.loadStatus().changes
            assertEquals(3, pending.size, "expected three untracked files, got $pending")
            val head = repo.headSha()

            assertThrows(Exception::class.java) { repo.stageAndCommit("should fail", pending) }

            assertEquals(head, repo.headSha(), "a failed staging pass must not move HEAD")
            val after = repo.loadStatus()
            assertEquals(
                listOf(ChangeKind.UNTRACKED, ChangeKind.UNTRACKED, ChangeKind.UNTRACKED),
                listOf("a-one.txt", "m-locked.txt", "z-two.txt").map { after.byPath[it] },
                "staging is one command, so a path that cannot be read leaves the whole index " +
                    "untouched — none of its neighbours may be left staged for the user to unpick",
            )
        } finally {
            locked.setReadable(true)
            repo.close()
        }
    }

    // The parallel staging path only engages past GitRepo's size thresholds, so these fixtures are
    // deliberately over 64 files rather than the two or three the other tests get by with.
    private fun seedBulkTree(tmp: Path, count: Int = 70) {
        (tmp / "data").createDirectories()
        repeat(count) { i ->
            // Mixed shapes on purpose: git records content and mode, and an encoder that is subtly
            // wrong about either shows up as a different tree hash below.
            val body = when (i % 4) {
                0 -> "plain line $i\n"
                1 -> "no trailing newline $i"
                2 -> "multi\nline\r\nmixed endings $i\n"
                else -> buildString { repeat(200) { append("padding $i ") } }
            }
            (tmp / "data" / "f$i.dat").writeText(body)
        }
        (tmp / "data" / "empty.dat").writeText("")
        (tmp / "data" / "binary.dat").toFile().writeBytes(ByteArray(4096) { (it % 256).toByte() })
    }

    @Test
    fun `parallel staging records the same tree as git itself`(@TempDir tmp: Path) {
        // Two identical working trees: one staged by GitRepo (which goes parallel at this size),
        // one by the real git binary. The tree hash covers every path, blob and mode, so equal
        // hashes mean the hand-rolled encoder agrees with git exactly.
        val mine = (tmp / "mine").also { it.createDirectories() }
        val reference = (tmp / "reference").also { it.createDirectories() }
        for (dir in listOf(mine, reference)) {
            runShell(dir, "git init -q && git config user.email t@x && git config user.name T")
            seedBulkTree(dir)
        }

        val repo = GitRepo.discover(mine, ceiling = tmp)!!
        repo.stageAndCommit("bulk", repo.loadStatus().changes)
        repo.close()
        runShell(reference, "git add -A && git commit -q -m bulk")

        assertEquals(
            gitOutput(reference, "git rev-parse HEAD^{tree}"),
            gitOutput(mine, "git rev-parse HEAD^{tree}"),
            "staging in parallel must produce byte-for-byte what git would have staged",
        )
        assertTrue(gitOutput(mine, "git status --porcelain").isEmpty(), "everything should be committed")
    }

    @Test
    fun `parallel staging matches git for binary files under autocrlf and gitattributes`(@TempDir tmp: Path) {
        // The conditions that actually hold on a developer box, and that a repo-wide filter check
        // gets wrong: autocrlf converting text on check-in, a .gitattributes present, and a mix of
        // binary blobs (which convert to nothing and may be read raw) with text that must not be.
        val mine = (tmp / "mine").also { it.createDirectories() }
        val reference = (tmp / "reference").also { it.createDirectories() }
        for (dir in listOf(mine, reference)) {
            runShell(dir, "git init -q && git config user.email t@x && git config user.name T && git config core.autocrlf input")
            (dir / ".gitattributes").writeText("*.md text\n*.bin -text\n")
            (dir / "data").createDirectories()
            repeat(80) { i ->
                // NUL in the first bytes is git's own binary test, so these take the fast path.
                (dir / "data" / "b$i.bin").toFile()
                    .writeBytes(ByteArray(2048) { j -> ((i + j) % 256).toByte() })
            }
            // ...while CRLF text must still be normalised by git's filter, not copied raw.
            repeat(5) { i -> (dir / "data" / "t$i.md").writeText("alpha\r\nbeta $i\r\n") }
        }

        val repo = GitRepo.discover(mine, ceiling = tmp)!!
        repo.stageAndCommit("mixed", repo.loadStatus().changes)
        val text = repo.readHeadContent("data/t0.md")
        repo.close()
        runShell(reference, "git add -A && git commit -q -m mixed")

        assertEquals(
            gitOutput(reference, "git rev-parse HEAD^{tree}"),
            gitOutput(mine, "git rev-parse HEAD^{tree}"),
            "binary read raw and text put through git's filter must together rebuild git's own tree",
        )
        assertEquals("alpha\nbeta 0\n", text, "CRLF text must not be copied raw by the fast path")
    }

    @Test
    fun `parallel staging declines paths a clean filter applies to`(@TempDir tmp: Path) {
        // The guard the whole fast path rests on, and the one case nothing above reaches: the eol
        // tests pass on this box because core.autocrlf=input is set globally, which is also the
        // answer an iterator with no walk gives — so they hold whether or not .gitattributes was
        // ever read. A clean filter has no such fallback, and it is the one that costs real bytes:
        // hermes stored 39.55 GiB of corpus as raw blobs instead of Git LFS pointers before this
        // was found. `tr` stands in for git-lfs because it is binary-safe and needs no install.
        val mine = (tmp / "mine").also { it.createDirectories() }
        val reference = (tmp / "reference").also { it.createDirectories() }
        for (dir in listOf(mine, reference)) {
            runShell(dir, "git init -q && git config user.email t@x && git config user.name T && " +
                "git config filter.zap.clean 'tr A B' && git config filter.zap.required true")
            (dir / ".gitattributes").writeText("*.bin filter=zap -text\n")
            (dir / "data").createDirectories()
            // A NUL in the first bytes is git's own binary test, so these are precisely the files
            // the fast path reads straight off disk once it believes no filter applies.
            repeat(70) { i ->
                val body = ("\u0000" + "A".repeat(600 + i)).toByteArray(Charsets.ISO_8859_1)
                (dir / "data" / "b$i.bin").toFile().writeBytes(body)
            }
        }

        val repo = GitRepo.discover(mine, ceiling = tmp)!!
        repo.stageAndCommit("filtered", repo.loadStatus().changes)
        val committed = repo.readHeadContent("data/b0.bin")
        repo.close()
        runShell(reference, "git add -A && git commit -q -m filtered")

        assertEquals(
            gitOutput(reference, "git rev-parse HEAD^{tree}"),
            gitOutput(mine, "git rev-parse HEAD^{tree}"),
            "a clean filter must be applied by AddCommand, not bypassed by reading the file raw",
        )
        assertFalse(
            committed.orEmpty().contains("A"),
            "the clean filter never ran — the fast path committed the bytes on disk",
        )
    }

    @Test
    fun `parallel staging preserves the executable bit`(@TempDir tmp: Path) {
        runShell(tmp, "git init -q && git config user.email t@x && git config user.name T")
        seedBulkTree(tmp)
        val script = (tmp / "data" / "run.sh").also { it.writeText("#!/bin/sh\necho hi\n") }
        script.toFile().setExecutable(true)
        assumeTrue(script.toFile().canExecute(), "filesystem does not carry the exec bit")

        val repo = GitRepo.discover(tmp, ceiling = tmp)!!
        repo.stageAndCommit("bulk", repo.loadStatus().changes)
        repo.close()

        assertEquals(
            "100755",
            gitOutput(tmp, "git ls-tree HEAD data/run.sh").split(Regex("\\s+")).firstOrNull(),
            "an executable file must be staged as mode 100755, not 100644",
        )
    }

    @Test
    fun `staging honours gitattributes eol conversion instead of copying raw bytes`(@TempDir tmp: Path) {
        // Reading files directly would put the CRLFs straight into the blob. GitRepo must spot the
        // .gitattributes and hand the whole set back to JGit's AddCommand, which applies the filter.
        runShell(tmp, "git init -q && git config user.email t@x && git config user.name T")
        (tmp / ".gitattributes").writeText("*.dat text eol=lf\n")
        (tmp / "data").createDirectories()
        repeat(70) { i -> (tmp / "data" / "f$i.dat").writeText("alpha\r\nbeta\r\ngamma $i\r\n") }

        val repo = GitRepo.discover(tmp, ceiling = tmp)!!
        repo.stageAndCommit("crlf", repo.loadStatus().changes)
        val committed = repo.readHeadContent("data/f0.dat")
        repo.close()

        assertEquals(
            "alpha\nbeta\ngamma 0\n", committed,
            "text=/eol= must still normalise line endings — the fast path has to decline here",
        )
    }

    /**
     * Files the fast path is guaranteed to take, whatever the machine's git config says. A NUL in
     * the first 8k is git's own binary test, and binary content converts to nothing under
     * autocrlf — which this box sets globally, and which sends plain text to AddCommand instead.
     */
    private fun seedBinaryTree(tmp: Path, count: Int = 70): Long {
        (tmp / "data").createDirectories()
        var total = 0L
        repeat(count) { i ->
            val body = ByteArray(512 + i) { (it % 251).toByte() }
            body[0] = 0
            (tmp / "data" / "b$i.bin").toFile().writeBytes(body)
            total += body.size
        }
        return total
    }

    @Test
    fun `parallel staging reports progress that ends on the whole change set`(@TempDir tmp: Path) {
        // What the commit button's bar is drawn from. The guarantees it relies on are that the
        // reported total covers every staged byte, that the counters only ever climb, and that the
        // last word is a phase past the writing — otherwise a finished commit leaves a bar short
        // of its end.
        runShell(tmp, "git init -q && git config user.email t@x && git config user.name T")
        val expectedBytes = seedBinaryTree(tmp)

        val repo = GitRepo.discover(tmp, ceiling = tmp)!!
        val seen = java.util.Collections.synchronizedList(mutableListOf<CommitProgress>())
        val changes = repo.loadStatus().changes
        repo.stageAndCommit("bulk", changes, startedAtMillis = 1234L) { seen.add(it) }
        repo.close()

        val reports = seen.toList()
        assertTrue(
            reports.any { it.phase == CommitProgress.Phase.WRITING },
            "this change set must go down the parallel path, or there is no progress to report",
        )
        val last = reports.last()
        assertEquals(CommitProgress.Phase.COMMITTING, last.phase, "the last report is the commit itself")
        assertEquals(expectedBytes, last.bytesTotal, "the total must cover every byte staged")
        assertEquals(expectedBytes, last.bytesDone, "every staged byte must be reported as done")
        assertEquals(changes.size, last.filesDone, "every staged file must be reported as done")
        assertEquals(changes.size, last.filesTotal)
        assertEquals(1f, last.fraction, "a finished staging pass must read as a full bar")
        assertTrue(reports.all { it.startedAtMillis == 1234L }, "the caller's clock is carried through")

        for ((before, after) in reports.zipWithNext()) {
            assertTrue(after.bytesDone >= before.bytesDone, "bytes must not go backwards: $before then $after")
            assertTrue(after.filesDone >= before.filesDone, "files must not go backwards: $before then $after")
        }
    }

    @Test
    fun `a commit too small to go parallel reports phases without a byte total`(@TempDir tmp: Path) {
        // AddCommand reports nothing as it walks, so there is no honest total to quote here. The
        // button falls back to elapsed time, which needs the phases to still arrive.
        runShell(tmp, "git init -q && git config user.email t@x && git config user.name T")
        (tmp / "one.txt").writeText("hello\n")

        val repo = GitRepo.discover(tmp, ceiling = tmp)!!
        val seen = mutableListOf<CommitProgress>()
        repo.stageAndCommit("small", repo.loadStatus().changes) { seen.add(it) }
        repo.close()

        assertEquals(
            listOf(CommitProgress.Phase.STAGING, CommitProgress.Phase.COMMITTING),
            seen.map { it.phase },
        )
        assertTrue(seen.all { it.bytesTotal == 0L }, "nothing measurable ran, so nothing may be quoted")
        assertTrue(seen.all { it.fraction == null }, "a bar cannot be drawn from an unknown total")
        assertEquals(1, seen.last().filesTotal)
    }

    @Test
    fun `parallel staging leaves the index untouched when one path cannot be staged`(@TempDir tmp: Path) {
        runShell(tmp, "git init -q && git config user.email t@x && git config user.name T")
        (tmp / "seed.txt").writeText("seed\n")
        runShell(tmp, "git add -A && git commit -q -m init")
        seedBulkTree(tmp)

        val locked = (tmp / "data" / "f13.dat").toFile()
        locked.setReadable(false)
        assumeTrue(!locked.canRead(), "cannot make a file unreadable (running as root?)")

        val repo = GitRepo.discover(tmp, ceiling = tmp)!!
        try {
            val head = repo.headSha()
            val pending = repo.loadStatus().changes
            assertThrows(Exception::class.java) { repo.stageAndCommit("should fail", pending) }

            assertEquals(head, repo.headSha(), "a failed staging pass must not move HEAD")
            // Blobs may well have been written by the workers that did succeed; unreferenced objects
            // are harmless and `git gc` collects them. What must not happen is a published index.
            assertTrue(
                gitOutput(tmp, "git diff --cached --name-only").isEmpty(),
                "no path may be left staged after a failed parallel staging pass",
            )
        } finally {
            locked.setReadable(true)
            repo.close()
        }
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
        val sha = repo.stashCreate("wip — local edit", repo.loadStatus().changes)
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
    fun `stash pop restores untracked files even when the tracked merge conflicts`(@TempDir tmp: Path) {
        runShell(tmp, "git init -q && git config user.email t@x && git config user.name T")
        (tmp / "edit.txt").writeText("v1\n")
        runShell(tmp, "git add -A && git commit -q -m init")

        (tmp / "edit.txt").writeText("stashed\n")
        (tmp / "new.txt").writeText("untracked\n")

        val repo = GitRepo.discover(tmp)!!
        repo.stashCreate("wip", repo.loadStatus().changes)
        assertFalse((tmp / "new.txt").toFile().exists(), "untracked file went onto the shelf")

        // Move HEAD under the shelf so applying it cannot merge cleanly
        (tmp / "edit.txt").writeText("moved on\n")
        runShell(tmp, "git add -A && git commit -q -m moved")

        val shelf = repo.stashList()
        assertEquals(1, shelf.size)
        assertThrows(StashApplyFailureException::class.java) { repo.stashPop(shelf[0]) }

        assertTrue((tmp / "new.txt").toFile().exists(), "untracked file restored despite the conflict")
        assertEquals("untracked\n", (tmp / "new.txt").toFile().readText(), "and with its content")
        assertEquals(1, repo.stashList().size, "a failed pop keeps the entry on the shelf")
        repo.close()
    }

    @Test
    fun `stash create with nothing to stash returns null`(@TempDir tmp: Path) {
        runShell(tmp, "git init -q && git config user.email t@x && git config user.name T")
        (tmp / "a.txt").writeText("a\n")
        runShell(tmp, "git add -A && git commit -q -m init")

        val repo = GitRepo.discover(tmp)!!
        assertEquals(null, repo.stashCreate("nothing", repo.loadStatus().changes), "no changes -> no stash")
        repo.close()
    }

    @Test
    fun `stash drop removes the entry without applying`(@TempDir tmp: Path) {
        runShell(tmp, "git init -q && git config user.email t@x && git config user.name T")
        (tmp / "a.txt").writeText("v1\n")
        runShell(tmp, "git add -A && git commit -q -m init")
        (tmp / "a.txt").writeText("v2\n")

        val repo = GitRepo.discover(tmp)!!
        repo.stashCreate("toss me", repo.loadStatus().changes)
        val shelf = repo.stashList()
        assertEquals(1, shelf.size)

        repo.stashDrop(shelf[0])
        assertTrue(repo.stashList().isEmpty(), "shelf empty after drop")
        assertEquals("v1\n", (tmp / "a.txt").toFile().readText(), "drop should not restore the working tree")
        repo.close()
    }

    @Test
    fun `stash create shelves only the changes it is given`(@TempDir tmp: Path) {
        runShell(tmp, "git init -q && git config user.email t@x && git config user.name T")
        (tmp / "picked.txt").writeText("v1\n")
        (tmp / "left.txt").writeText("v1\n")
        (tmp / "goes-away.txt").writeText("bye\n")
        runShell(tmp, "git add -A && git commit -q -m init")

        // One of each kind on the shelf, one of each kind left behind, so neither the tracked nor
        // the untracked nor the deleted half of the walk can be taking the whole working tree.
        (tmp / "picked.txt").writeText("v2\n")
        (tmp / "picked-new.txt").writeText("fresh\n")
        (tmp / "goes-away.txt").toFile().delete()
        (tmp / "left.txt").writeText("v2\n")
        (tmp / "left-new.txt").writeText("also fresh\n")

        val repo = GitRepo.discover(tmp)!!
        val pending = repo.loadStatus().changes
        assertEquals(5, pending.size, "expected five pending changes, got $pending")
        val picked = setOf("picked.txt", "picked-new.txt", "goes-away.txt")
        val sha = repo.stashCreate("just these three", pending.filter { it.path in picked })
        assertNotNull(sha)

        val after = repo.loadStatus()
        assertEquals(
            setOf("left.txt", "left-new.txt"), after.changes.map { it.path }.toSet(),
            "only the unselected changes should still be pending",
        )
        assertEquals(ChangeKind.MODIFIED, after.byPath["left.txt"])
        assertEquals(ChangeKind.UNTRACKED, after.byPath["left-new.txt"])
        assertEquals("v1\n", (tmp / "picked.txt").toFile().readText(), "the stashed edit should be undone")
        assertEquals("v2\n", (tmp / "left.txt").toFile().readText(), "the edit left behind should survive")
        assertTrue((tmp / "goes-away.txt").toFile().exists(), "the stashed deletion should be undone")
        assertTrue(!(tmp / "picked-new.txt").toFile().exists(), "the stashed new file should be off disk")
        assertTrue((tmp / "left-new.txt").toFile().exists(), "the new file left behind should stay on disk")

        // Real git has to agree about what is on the shelf, both that the entry is well formed
        // enough to list and that it carries the three paths and nothing else.
        assertTrue(
            gitOutput(tmp, "git stash list").contains("just these three"),
            "git stash list should show the entry: ${gitOutput(tmp, "git stash list")}",
        )
        assertEquals(
            listOf("goes-away.txt", "picked-new.txt", "picked.txt"),
            gitOutput(tmp, "git stash show --include-untracked --name-only stash@{0}").lines().sorted(),
            "the entry should carry exactly the selected paths",
        )
        repo.close()
    }

    @Test
    fun `stash pop restores a partial stash alongside what stayed behind`(@TempDir tmp: Path) {
        runShell(tmp, "git init -q && git config user.email t@x && git config user.name T")
        (tmp / "picked.txt").writeText("v1\n")
        (tmp / "left.txt").writeText("v1\n")
        runShell(tmp, "git add -A && git commit -q -m init")
        (tmp / "picked.txt").writeText("v2\n")
        (tmp / "picked-new.txt").writeText("fresh\n")
        (tmp / "left.txt").writeText("v2\n")

        val repo = GitRepo.discover(tmp)!!
        val pending = repo.loadStatus().changes
        repo.stashCreate("half of it", pending.filter { it.path != "left.txt" })

        repo.stashPop(repo.stashList().single())
        assertTrue(repo.stashList().isEmpty(), "shelf empty after pop")
        assertEquals("v2\n", (tmp / "picked.txt").toFile().readText(), "the stashed edit should come back")
        assertEquals("fresh\n", (tmp / "picked-new.txt").toFile().readText(), "the stashed new file should come back")
        assertEquals("v2\n", (tmp / "left.txt").toFile().readText(), "the change left behind should be untouched")
        assertEquals(
            pending.map { it.path }.toSet(), repo.loadStatus().changes.map { it.path }.toSet(),
            "popping should put the working tree back where it started",
        )
        repo.close()
    }

    @Test
    fun `revertFile removes a staged new file that was edited again`(@TempDir tmp: Path) {
        runShell(tmp, "git init -q && git config user.email t@x && git config user.name T")
        (tmp / "kept.txt").writeText("kept\n")
        runShell(tmp, "git add -A && git commit -q -m init")

        (tmp / "fresh.txt").writeText("staged\n")
        runShell(tmp, "git add fresh.txt")
        (tmp / "fresh.txt").writeText("and edited again\n")

        val repo = GitRepo.discover(tmp)!!
        repo.revertFile(repo.loadStatus().changes.single { it.path == "fresh.txt" })
        assertTrue(!(tmp / "fresh.txt").toFile().exists(), "a file HEAD never had should be removed, not checked out")
        assertTrue(repo.loadStatus().isClean, "clean after revert, got ${repo.loadStatus().changes}")
        repo.close()
    }

    @Test
    fun `stash create takes a staged new file that was edited again off disk`(@TempDir tmp: Path) {
        runShell(tmp, "git init -q && git config user.email t@x && git config user.name T")
        (tmp / "old.txt").writeText("old\n")
        runShell(tmp, "git add -A && git commit -q -m init")

        // A rename staged with `git mv`, then rewritten on disk: the new path is an addition in the
        // index AND a modification against it, so status reports it in both buckets.
        runShell(tmp, "git mv old.txt new.txt")
        (tmp / "new.txt").writeText("rewritten\n")

        val repo = GitRepo.discover(tmp)!!
        val pending = repo.loadStatus()
        assertEquals(ChangeKind.ADDED, pending.byPath["new.txt"], "a path absent from HEAD is an addition")

        repo.stashCreate("rename", pending.changes)
        assertTrue(repo.loadStatus().isClean, "clean after stash, got ${repo.loadStatus().changes}")
        assertTrue(!(tmp / "new.txt").toFile().exists(), "the staged new path should be off disk")
        assertEquals("old\n", (tmp / "old.txt").toFile().readText(), "the rename should be undone")

        // The pop has to write the new path back. Left on disk by the stash, it would sit in the
        // way of that checkout as an untracked file, and the pop would fail against it instead.
        repo.stashPop(repo.stashList().single())
        assertEquals("rewritten\n", (tmp / "new.txt").toFile().readText(), "the rename should come back")
        assertTrue(!(tmp / "old.txt").toFile().exists(), "the old path should be gone again")
        repo.close()
    }

    @Test
    fun `stash create leaves a staged path it was not given out of the shelf`(@TempDir tmp: Path) {
        runShell(tmp, "git init -q && git config user.email t@x && git config user.name T")
        (tmp / "picked.txt").writeText("v1\n")
        (tmp / "staged.txt").writeText("v1\n")
        runShell(tmp, "git add -A && git commit -q -m init")

        // staged.txt is staged from outside nop, so it differs from HEAD in the index as well as on
        // disk — the case where a stash built from the index as it stands would swallow it.
        (tmp / "picked.txt").writeText("v2\n")
        (tmp / "staged.txt").writeText("v2\n")
        runShell(tmp, "git add staged.txt")

        val repo = GitRepo.discover(tmp)!!
        val pending = repo.loadStatus().changes
        repo.stashCreate("only picked", pending.filter { it.path == "picked.txt" })

        assertEquals(
            listOf("picked.txt"),
            gitOutput(tmp, "git stash show --name-only stash@{0}").lines(),
            "the shelved entry should not mention the staged path",
        )
        assertEquals("v2\n", (tmp / "staged.txt").toFile().readText(), "the staged edit should still be on disk")
        assertEquals(
            "v2\n", gitOutput(tmp, "git show :staged.txt") + "\n",
            "the staged edit should still be staged",
        )
        assertEquals(listOf("staged.txt"), repo.loadStatus().changes.map { it.path })
        repo.close()
    }

    @Test
    fun `stash create returns null when nothing it was given is dirty`(@TempDir tmp: Path) {
        runShell(tmp, "git init -q && git config user.email t@x && git config user.name T")
        (tmp / "clean.txt").writeText("v1\n")
        (tmp / "dirty.txt").writeText("v1\n")
        runShell(tmp, "git add -A && git commit -q -m init")
        (tmp / "dirty.txt").writeText("v2\n")

        val repo = GitRepo.discover(tmp)!!
        // A stale change list — the panel's copy of a path that has since been reverted. Nothing to
        // shelve, so there must be no entry, and the dirty file must be left where it is.
        val stale = listOf(FileChange("clean.txt", ChangeKind.MODIFIED))
        assertEquals(null, repo.stashCreate("stale", stale), "nothing selected was dirty -> no entry")
        assertTrue(repo.stashList().isEmpty(), "no entry on the shelf")
        assertEquals("v2\n", (tmp / "dirty.txt").toFile().readText(), "the unselected change should be untouched")
        repo.close()
    }

    @Test
    fun `stash create does not delete a selected file it could not shelve`(@TempDir tmp: Path) {
        runShell(tmp, "git init -q && git config user.email t@x && git config user.name T")
        (tmp / "tracked.txt").writeText("v1\n")
        (tmp / ".gitignore").writeText("secret.txt\n")
        runShell(tmp, "git add -A && git commit -q -m init")
        (tmp / "tracked.txt").writeText("v2\n")
        (tmp / "secret.txt").writeText("do not lose me\n")

        val repo = GitRepo.discover(tmp)!!
        // An ignored path can't reach a stash — the walk never yields it — so a change list naming
        // one (a stale panel copy, from before a .gitignore rule caught up) must not get its file
        // deleted as though it had been shelved.
        val stale = repo.loadStatus().changes + FileChange("secret.txt", ChangeKind.UNTRACKED)
        assertNotNull(repo.stashCreate("tracked only", stale))

        assertEquals("v1\n", (tmp / "tracked.txt").toFile().readText(), "the tracked edit should be shelved")
        assertEquals(
            "do not lose me\n", (tmp / "secret.txt").toFile().readText(),
            "an ignored file that never reached the shelf must still be on disk",
        )
        repo.close()
    }

    @Test
    fun `stageAndCommit holds back a staged path the change set leaves out`(@TempDir tmp: Path) {
        runShell(tmp, "git init -q && git config user.email t@x && git config user.name T")
        (tmp / "picked.txt").writeText("v1\n")
        (tmp / "staged.txt").writeText("v1\n")
        runShell(tmp, "git add -A && git commit -q -m init")

        // Staged outside nop, then unticked in the panel: the commit has to leave it out rather
        // than take the index whole.
        (tmp / "picked.txt").writeText("v2\n")
        (tmp / "staged.txt").writeText("v2\n")
        runShell(tmp, "git add staged.txt")

        val repo = GitRepo.discover(tmp)!!
        val pending = repo.loadStatus().changes
        assertEquals(2, pending.size, "expected two pending changes, got $pending")
        repo.stageAndCommit("only picked", pending.filter { it.path == "picked.txt" }, partial = true)

        assertEquals("v2\n", repo.readHeadContent("picked.txt"), "the selected edit should be committed")
        assertEquals("v1\n", repo.readHeadContent("staged.txt"), "the unticked edit should not be")
        assertEquals(
            listOf("staged.txt"), repo.loadStatus().changes.map { it.path },
            "the unticked edit should still be pending",
        )
        assertEquals(
            "v2\n", gitOutput(tmp, "git show :staged.txt") + "\n",
            "and should still be staged, exactly as the user left it",
        )
        assertEquals(listOf("picked.txt"), gitOutput(tmp, "git show --name-only --format= HEAD").lines())
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
    fun `headSha follows the branch tip and is null before the first commit`(@TempDir tmp: Path) {
        runShell(tmp, "git init -q && git config user.email t@x && git config user.name T")
        val repo = GitRepo.discover(tmp)!!
        assertNull(repo.headSha(), "an unborn branch points at no commit")

        (tmp / "a.txt").writeText("one\n")
        runShell(tmp, "git add -A && git commit -q -m 'first'")
        val first = repo.headSha()
        (tmp / "a.txt").writeText("one\ntwo\n")
        runShell(tmp, "git add -A && git commit -q -m 'second'")
        val second = repo.headSha()
        // What tells an open diff its left-hand side has moved: the working tree looks identical
        // either side of the commit, and only HEAD says otherwise.
        val afterEdit = run { (tmp / "a.txt").writeText("one\ntwo\nthree\n"); repo.headSha() }
        repo.close()

        assertEquals(40, first?.length)
        assertNotEquals(first, second, "the second commit moved HEAD")
        assertEquals(second, afterEdit, "an uncommitted edit leaves HEAD where it was")
    }

    @Test
    fun `readContentAt returns a file as an older commit left it`(@TempDir tmp: Path) {
        runShell(tmp, "git init -q && git config user.email t@x && git config user.name T")
        (tmp / "a.txt").writeText("one\n")
        runShell(tmp, "git add -A && git commit -q -m 'first'")
        (tmp / "a.txt").writeText("one\ntwo\n")
        (tmp / "later.txt").writeText("new file\n")
        runShell(tmp, "git add -A && git commit -q -m 'second'")
        (tmp / "a.txt").writeText("one\ntwo\nuncommitted\n")

        val repo = GitRepo.discover(tmp)!!
        val log = repo.history("a.txt")
        // What "compare with revision" reads for its left-hand side: the file at the picked commit,
        // untouched by anything committed (or saved) since.
        val first = repo.readContentAt(log[1].sha, "a.txt")
        val head = repo.readContentAt(log[0].sha, "a.txt")
        // A file the picked revision predates isn't in that tree at all.
        val absent = repo.readContentAt(log[1].sha, "later.txt")
        repo.close()

        assertEquals("one\n", first)
        assertEquals("one\ntwo\n", head, "the commit's content, not the working tree's")
        assertNull(absent, "later.txt did not exist at the first commit")
    }

    @Test
    fun `blame attributes each line to the commit that last touched it`(@TempDir tmp: Path) {
        runShell(tmp, "git init -q && git config user.email t@x && git config user.name Alice")
        (tmp / "f.txt").writeText("one\ntwo\nthree\n")
        runShell(tmp, "git add -A && git commit -q -m 'first three lines'")

        // Change only the middle line in a second commit (by a different author).
        (tmp / "f.txt").writeText("one\nTWO-changed\nthree\n")
        runShell(tmp, "git config user.name Bob && git add -A && git commit -q -m 'rewrite line two'")

        val repo = GitRepo.discover(tmp)!!
        val blame = repo.blame("f.txt")
        repo.close()

        assertNotNull(blame)
        assertEquals(3, blame!!.size, "one blame entry per line")
        assertEquals("first three lines", blame[0].summary, "line 1 still from the first commit")
        assertEquals("Alice", blame[0].author)
        assertEquals("rewrite line two", blame[1].summary, "line 2 reattributed to the second commit")
        assertEquals("Bob", blame[1].author)
        assertEquals("first three lines", blame[2].summary, "line 3 untouched since the first commit")
        blame.forEach { assertTrue(it.committed, "all lines are committed: ${it.summary}") }
    }

    @Test
    fun `blame marks uncommitted working-tree edits with a null sha`(@TempDir tmp: Path) {
        runShell(tmp, "git init -q && git config user.email t@x && git config user.name T")
        (tmp / "f.txt").writeText("alpha\nbeta\n")
        runShell(tmp, "git add -A && git commit -q -m init")

        // Edit a line without committing — blame should attribute it to the working tree.
        (tmp / "f.txt").writeText("alpha\nbeta-edited-locally\n")

        val repo = GitRepo.discover(tmp)!!
        val blame = repo.blame("f.txt")
        repo.close()

        assertNotNull(blame)
        assertEquals(2, blame!!.size)
        assertTrue(blame[0].committed, "untouched line stays attributed to its commit")
        assertNull(blame[1].sha, "locally edited line has no commit yet")
        assertEquals(false, blame[1].committed)
    }

    @Test
    fun `blame returns null for a path with no history`(@TempDir tmp: Path) {
        runShell(tmp, "git init -q && git config user.email t@x && git config user.name T")
        (tmp / "a.txt").writeText("a\n")
        runShell(tmp, "git add -A && git commit -q -m init")

        val repo = GitRepo.discover(tmp)!!
        assertNull(repo.blame("never-existed.txt"), "no blame for an unknown path")
        repo.close()
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
    fun `loadStatus returns changes in path order, and the order survives an edit`(@TempDir tmp: Path) {
        runShell(tmp, "git init -q && git config user.email t@x && git config user.name T")
        val paths = listOf(
            "README.md",
            "docs/api/endpoints.md",
            "docs/api/overview.md",
            "docs/ui/buttons.md",
            "guides/start.md",
        )
        for (p in paths) {
            (tmp / p).parent.createDirectories()
            (tmp / p).writeText("v1\n")
        }
        runShell(tmp, "git add -A && git commit -q -m init")
        for (p in paths) (tmp / p).writeText("v2\n")

        val repo = GitRepo.discover(tmp)!!
        assertEquals(paths, repo.loadStatus().changes.map { it.path }, "changes must come back in path order")

        // Editing one file must not move it — or anything else — within the list. JGit hands its
        // paths back out of hash sets, so without an explicit order this is exactly where a file
        // would jump to the top.
        (tmp / "docs/ui/buttons.md").writeText("v3\n")
        assertEquals(paths, repo.loadStatus().changes.map { it.path }, "an edit must not reorder the list")
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

    @Test
    fun `revertFiles discards every kind of change in one pass`(@TempDir tmp: Path) {
        runShell(tmp, "git init -q && git config user.email t@x && git config user.name T")
        (tmp / "modified.txt").writeText("v1\n")
        (tmp / "staged-rm.txt").writeText("keep me\n")
        (tmp / "deleted.txt").writeText("also keep me\n")
        runShell(tmp, "git add -A && git commit -q -m init")

        (tmp / "modified.txt").writeText("v2-local\n")       // MODIFIED
        runShell(tmp, "git rm -q staged-rm.txt")             // REMOVED
        (tmp / "deleted.txt").toFile().delete()              // MISSING
        (tmp / "added.txt").writeText("staged new\n")
        runShell(tmp, "git add added.txt")                   // ADDED
        (tmp / "junk.txt").writeText("scratch\n")            // UNTRACKED

        val repo = GitRepo.discover(tmp)!!
        val changes = repo.loadStatus().changes
        assertEquals(5, changes.size, "all five kinds pending before revert: $changes")

        repo.revertFiles(changes)

        assertEquals("v1\n", (tmp / "modified.txt").toFile().readText(), "modification rolled back")
        assertEquals("keep me\n", (tmp / "staged-rm.txt").toFile().readText(), "staged removal restored")
        assertEquals("also keep me\n", (tmp / "deleted.txt").toFile().readText(), "deleted file restored")
        assertTrue(!(tmp / "added.txt").toFile().exists(), "staged new file deleted")
        assertTrue(!(tmp / "junk.txt").toFile().exists(), "untracked file deleted")
        assertTrue(repo.loadStatus().isClean, "working tree clean after reverting everything")
        repo.close()
    }

    @Test
    fun `revertFiles reverts only the changes it is given`(@TempDir tmp: Path) {
        runShell(tmp, "git init -q && git config user.email t@x && git config user.name T")
        (tmp / "a.txt").writeText("a1\n")
        (tmp / "b.txt").writeText("b1\n")
        runShell(tmp, "git add -A && git commit -q -m init")
        (tmp / "a.txt").writeText("a2\n")
        (tmp / "b.txt").writeText("b2\n")

        val repo = GitRepo.discover(tmp)!!
        repo.revertFiles(listOf(FileChange("a.txt", ChangeKind.MODIFIED)))

        assertEquals("a1\n", (tmp / "a.txt").toFile().readText(), "listed file reverted")
        assertEquals("b2\n", (tmp / "b.txt").toFile().readText(), "unlisted file untouched")
        assertEquals(ChangeKind.MODIFIED, repo.loadStatus().byPath["b.txt"], "b.txt still pending")
        repo.close()
    }

    @Test
    fun `revertFiles on an empty list is a no-op`(@TempDir tmp: Path) {
        runShell(tmp, "git init -q && git config user.email t@x && git config user.name T")
        (tmp / "a.txt").writeText("a1\n")
        runShell(tmp, "git add -A && git commit -q -m init")
        (tmp / "a.txt").writeText("a2\n")

        val repo = GitRepo.discover(tmp)!!
        repo.revertFiles(emptyList())

        assertEquals("a2\n", (tmp / "a.txt").toFile().readText(), "nothing listed, so nothing discarded")
        repo.close()
    }

    @Test
    fun `workingTreeDirs lists non-ignored directories and never the git dir`(@TempDir tmp: Path) {
        runShell(tmp, "git init -q && git config user.email t@x && git config user.name T")
        (tmp / ".gitignore").writeText("node_modules/\nbuild/\n")
        (tmp / "src" / "main").createDirectories()
        (tmp / "docs").createDirectories()
        (tmp / "node_modules" / "dep" / "deep").createDirectories()
        (tmp / "build" / "out").createDirectories()

        val repo = GitRepo.discover(tmp, ceiling = tmp)!!
        val dirs = repo.workingTreeDirs(limit = 100)!!.map { tmp.relativize(it).toString() }.toSet()

        assertEquals(
            setOf("", "src", "src/main", "docs"), dirs,
            "the root and every non-ignored subdirectory, and nothing under node_modules/ or build/",
        )
        assertTrue(dirs.none { it.startsWith(".git") }, "the git directory is not part of the working tree")
        repo.close()
    }

    @Test
    fun `workingTreeDirs keeps an ignored directory holding a tracked file`(@TempDir tmp: Path) {
        runShell(tmp, "git init -q && git config user.email t@x && git config user.name T")
        (tmp / ".gitignore").writeText("build/\n")
        (tmp / "build").createDirectories()
        (tmp / "build" / "keep.txt").writeText("tracked in spite of the rule\n")
        runShell(tmp, "git add -f .gitignore build/keep.txt && git commit -q -m init")

        val repo = GitRepo.discover(tmp, ceiling = tmp)!!
        val dirs = repo.workingTreeDirs(limit = 100)!!.map { tmp.relativize(it).toString() }

        assertTrue(
            "build" in dirs,
            "git goes on tracking what it already tracks, so an edit under build/ still moves " +
                "status and the directory has to be watched: $dirs",
        )
        repo.close()
    }

    @Test
    fun `workingTreeDirs gives up past its limit`(@TempDir tmp: Path) {
        runShell(tmp, "git init -q")
        repeat(5) { (tmp / "d$it").createDirectories() }

        val repo = GitRepo.discover(tmp, ceiling = tmp)!!

        assertNull(
            repo.workingTreeDirs(limit = 3),
            "a tree past the ceiling reports nothing rather than a truncated list — a partial " +
                "answer would read as full coverage",
        )
        assertEquals(6, repo.workingTreeDirs(limit = 100)?.size, "the root plus five subdirectories")
        repo.close()
    }

    @Test
    fun `revertCommit undoes the last commit and leaves it uncommitted`(@TempDir tmp: Path) {
        runShell(tmp, "git init -q && git config user.email t@x && git config user.name T")
        (tmp / "a.txt").writeText("v1\n")
        runShell(tmp, "git add -A && git commit -q -m one")
        (tmp / "a.txt").writeText("v2\n")
        runShell(tmp, "git add -A && git commit -q -m two")
        val head = gitOutput(tmp, "git rev-parse HEAD")

        val repo = GitRepo.discover(tmp)!!
        val outcome = repo.revertCommit(head)

        assertEquals(listOf("a.txt"), outcome.updated, "the path was rewritten, not removed")
        assertEquals("v1\n", (tmp / "a.txt").toFile().readText(), "the second commit's change is undone")
        assertEquals(head, gitOutput(tmp, "git rev-parse HEAD"), "HEAD does not move")
        assertEquals(ChangeKind.MODIFIED, repo.loadStatus().byPath["a.txt"], "lands as a pending change")
        repo.close()
    }

    @Test
    fun `revertCommit keeps work done after the commit it reverses`(@TempDir tmp: Path) {
        runShell(tmp, "git init -q && git config user.email t@x && git config user.name T")
        (tmp / "a.txt").writeText("keep me\n")
        (tmp / "b.txt").writeText("b-v1\n")
        runShell(tmp, "git add -A && git commit -q -m one")
        // The middle commit — the one to reverse — touches b.txt only.
        (tmp / "b.txt").writeText("b-v2-unwanted\n")
        runShell(tmp, "git add -A && git commit -q -m two")
        val middle = gitOutput(tmp, "git rev-parse HEAD")
        // Later work on a *different* file must survive: this is what separates a revert from
        // rolling the tree back to an old revision.
        (tmp / "a.txt").writeText("later work\n")
        runShell(tmp, "git add -A && git commit -q -m three")

        val repo = GitRepo.discover(tmp)!!
        repo.revertCommit(middle)

        assertEquals("b-v1\n", (tmp / "b.txt").toFile().readText(), "the middle commit is undone")
        assertEquals("later work\n", (tmp / "a.txt").toFile().readText(), "the third commit's work is kept")
        repo.close()
    }

    @Test
    fun `revertCommit deletes a file the commit added`(@TempDir tmp: Path) {
        runShell(tmp, "git init -q && git config user.email t@x && git config user.name T")
        (tmp / "kept.txt").writeText("kept\n")
        runShell(tmp, "git add -A && git commit -q -m one")
        (tmp / "added.txt").writeText("added by the commit\n")
        runShell(tmp, "git add -A && git commit -q -m two")

        val repo = GitRepo.discover(tmp)!!
        val outcome = repo.revertCommit(gitOutput(tmp, "git rev-parse HEAD"))

        assertEquals(listOf("added.txt"), outcome.removed, "undoing an addition removes the file")
        assertTrue(!(tmp / "added.txt").toFile().exists(), "file is off disk")
        assertEquals(ChangeKind.REMOVED, repo.loadStatus().byPath["added.txt"], "staged as a deletion")
        repo.close()
    }

    @Test
    fun `revertCommit brings back a file the commit deleted`(@TempDir tmp: Path) {
        runShell(tmp, "git init -q && git config user.email t@x && git config user.name T")
        (tmp / "gone.txt").writeText("still here\n")
        runShell(tmp, "git add -A && git commit -q -m one")
        runShell(tmp, "git rm -q gone.txt && git commit -q -m two")

        val repo = GitRepo.discover(tmp)!!
        val outcome = repo.revertCommit(gitOutput(tmp, "git rev-parse HEAD"))

        assertEquals(listOf("gone.txt"), outcome.updated)
        assertEquals("still here\n", (tmp / "gone.txt").toFile().readText(), "the deletion is undone")
        repo.close()
    }

    @Test
    fun `revertCommit reverses a merge against its first parent`(@TempDir tmp: Path) {
        runShell(tmp, "git init -q && git config user.email t@x && git config user.name T")
        (tmp / "a.txt").writeText("base\n")
        runShell(tmp, "git add -A && git commit -q -m base")
        runShell(tmp, "git checkout -q -b side")
        (tmp / "side.txt").writeText("from the branch\n")
        runShell(tmp, "git add -A && git commit -q -m side")
        runShell(tmp, "git checkout -q -")
        runShell(tmp, "git merge -q --no-ff -m merge side")
        val merge = gitOutput(tmp, "git rev-parse HEAD")

        val repo = GitRepo.discover(tmp)!!
        // git's `-m 1`: what the merge brought in goes away, the mainline stays.
        val outcome = repo.revertCommit(merge)

        assertEquals(listOf("side.txt"), outcome.removed, "the merged branch's file goes")
        assertTrue(!(tmp / "side.txt").toFile().exists())
        assertEquals("base\n", (tmp / "a.txt").toFile().readText(), "the mainline is untouched")
        repo.close()
    }

    @Test
    fun `revertCommit refuses when the change cannot be backed out cleanly`(@TempDir tmp: Path) {
        runShell(tmp, "git init -q && git config user.email t@x && git config user.name T")
        (tmp / "a.txt").writeText("v1\n")
        runShell(tmp, "git add -A && git commit -q -m one")
        (tmp / "a.txt").writeText("v2\n")
        runShell(tmp, "git add -A && git commit -q -m two")
        val second = gitOutput(tmp, "git rev-parse HEAD")
        // A third commit rewrites the same line, so backing out the second no longer applies.
        (tmp / "a.txt").writeText("v3-rewritten\n")
        runShell(tmp, "git add -A && git commit -q -m three")

        val repo = GitRepo.discover(tmp)!!
        val failure = assertThrows(java.io.IOException::class.java) { repo.revertCommit(second) }

        assertTrue(failure.message!!.contains("a.txt"), "the blocking path is named: ${failure.message}")
        assertEquals("v3-rewritten\n", (tmp / "a.txt").toFile().readText(), "working tree left alone")
        assertTrue(repo.loadStatus().isClean, "a refused revert leaves nothing half-applied")
        repo.close()
    }

    @Test
    fun `revertCommit refuses rather than overwrite uncommitted edits`(@TempDir tmp: Path) {
        runShell(tmp, "git init -q && git config user.email t@x && git config user.name T")
        (tmp / "a.txt").writeText("v1\n")
        runShell(tmp, "git add -A && git commit -q -m one")
        (tmp / "a.txt").writeText("v2\n")
        runShell(tmp, "git add -A && git commit -q -m two")
        val head = gitOutput(tmp, "git rev-parse HEAD")
        // Work git has no copy of. Losing it is the one outcome the revert must never produce.
        (tmp / "a.txt").writeText("v2 plus work in progress\n")

        val repo = GitRepo.discover(tmp)!!
        assertThrows(java.io.IOException::class.java) { repo.revertCommit(head) }

        assertEquals(
            "v2 plus work in progress\n", (tmp / "a.txt").toFile().readText(),
            "the uncommitted edit survives untouched",
        )
        repo.close()
    }

    @Test
    fun `revertCommit reports an already reverted commit as nothing to do`(@TempDir tmp: Path) {
        runShell(tmp, "git init -q && git config user.email t@x && git config user.name T")
        (tmp / "a.txt").writeText("v1\n")
        runShell(tmp, "git add -A && git commit -q -m one")
        (tmp / "a.txt").writeText("v2\n")
        runShell(tmp, "git add -A && git commit -q -m two")
        val second = gitOutput(tmp, "git rev-parse HEAD")
        // Already backed out and committed, so there is nothing of it left in the tree.
        runShell(tmp, "git revert --no-edit $second")

        val repo = GitRepo.discover(tmp)!!
        val outcome = repo.revertCommit(second)

        assertTrue(outcome.isEmpty, "nothing left to reverse: $outcome")
        assertTrue(repo.loadStatus().isClean, "and nothing written")
        repo.close()
    }

    @Test
    fun `revertCommit undoes a root commit`(@TempDir tmp: Path) {
        runShell(tmp, "git init -q && git config user.email t@x && git config user.name T")
        (tmp / "a.txt").writeText("the very first file\n")
        runShell(tmp, "git add -A && git commit -q -m root")

        val repo = GitRepo.discover(tmp)!!
        // A root commit started from nothing, so undoing it removes everything it introduced.
        val outcome = repo.revertCommit(gitOutput(tmp, "git rev-parse HEAD"))

        assertEquals(listOf("a.txt"), outcome.removed)
        assertTrue(!(tmp / "a.txt").toFile().exists(), "the first commit's file is gone")
        repo.close()
    }

    @Test
    fun `revertCommit rejects a revision the repository does not have`(@TempDir tmp: Path) {
        runShell(tmp, "git init -q && git config user.email t@x && git config user.name T")
        (tmp / "a.txt").writeText("v1\n")
        runShell(tmp, "git add -A && git commit -q -m one")

        val repo = GitRepo.discover(tmp)!!
        assertThrows(java.io.IOException::class.java) { repo.revertCommit("0".repeat(40)) }
        assertEquals("v1\n", (tmp / "a.txt").toFile().readText(), "working tree untouched")
        repo.close()
    }

    private operator fun Path.div(name: String): Path = resolve(name)

    /** Runs [cmd] in [cwd] and returns its trimmed stdout, for asserting against real git. */
    private fun gitOutput(cwd: Path, cmd: String): String {
        val proc = ProcessBuilder("sh", "-c", cmd)
            .directory(cwd.toFile())
            .redirectErrorStream(true)
            .start()
        val out = proc.inputStream.bufferedReader().readText().trim()
        check(proc.waitFor() == 0) { "Command failed: $cmd\n$out" }
        return out
    }

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

    /**
     * The point of a session base: work the agent committed mid-run must stay on screen. HEAD moves
     * with the commit, so a plain status has nothing to say about it — [GitRepo.changesSince] reads
     * the baseline's tree as well and unions the two.
     */
    @Test
    fun `changesSince carries committed work alongside the working tree`(@TempDir tmp: Path) {
        runShell(tmp, "git init -q && git config user.email t@x && git config user.name T")
        (tmp / "kept.txt").writeText("kept\n")
        runShell(tmp, "git add -A && git commit -q -m init")

        val repo = GitRepo.discover(tmp)!!
        val baseline = repo.headSha()!!

        // Committed during the run, then more work left uncommitted beside it.
        (tmp / "committed.txt").writeText("done\n")
        runShell(tmp, "git add -A && git commit -q -m work")
        (tmp / "kept.txt").writeText("edited\n")
        (tmp / "untracked.txt").writeText("?\n")

        val status = repo.loadStatus()
        val since = repo.changesSince(baseline, status).associate { it.path to it.kind }
        repo.close()

        assertEquals(ChangeKind.ADDED, since["committed.txt"], "the commit's file must still be listed")
        assertEquals(ChangeKind.MODIFIED, since["kept.txt"])
        assertEquals(ChangeKind.UNTRACKED, since["untracked.txt"])
    }

    /** A file the agent committed and then edited again is one change, described as it stands now. */
    @Test
    fun `changesSince prefers the working tree's kind over the commit's`(@TempDir tmp: Path) {
        runShell(tmp, "git init -q && git config user.email t@x && git config user.name T")
        (tmp / "a.txt").writeText("v1\n")
        runShell(tmp, "git add -A && git commit -q -m init")

        val repo = GitRepo.discover(tmp)!!
        val baseline = repo.headSha()!!
        (tmp / "a.txt").writeText("v2\n")
        runShell(tmp, "git add -A && git commit -q -m v2")
        (tmp / "a.txt").writeText("v3\n")

        val since = repo.changesSince(baseline, repo.loadStatus())
        repo.close()

        assertEquals(1, since.size, "one file, one row: $since")
        assertEquals(ChangeKind.MODIFIED, since.first().kind)
    }

    /** With nothing committed since, "session" and "uncommitted" are the same question. */
    @Test
    fun `changesSince at HEAD is the plain status`(@TempDir tmp: Path) {
        runShell(tmp, "git init -q && git config user.email t@x && git config user.name T")
        (tmp / "a.txt").writeText("v1\n")
        runShell(tmp, "git add -A && git commit -q -m init")
        (tmp / "a.txt").writeText("v2\n")

        val repo = GitRepo.discover(tmp)!!
        val status = repo.loadStatus()
        val since = repo.changesSince(repo.headSha()!!, status)
        repo.close()

        assertEquals(status.changes, since)
    }

    /** An unresolvable base is a reason to show the working tree, not to show nothing. */
    @Test
    fun `changesSince falls back to the status for a base that is gone`(@TempDir tmp: Path) {
        runShell(tmp, "git init -q && git config user.email t@x && git config user.name T")
        (tmp / "a.txt").writeText("v1\n")
        runShell(tmp, "git add -A && git commit -q -m init")
        (tmp / "a.txt").writeText("v2\n")

        val repo = GitRepo.discover(tmp)!!
        val status = repo.loadStatus()
        val since = repo.changesSince("0000000000000000000000000000000000000000", status)
        repo.close()

        assertEquals(status.changes, since)
    }

    @Test
    fun `branchBaseSha finds where a branch left its trunk`(@TempDir tmp: Path) {
        runShell(tmp, "git init -q -b main && git config user.email t@x && git config user.name T")
        (tmp / "a.txt").writeText("a\n")
        runShell(tmp, "git add -A && git commit -q -m init")

        val repo = GitRepo.discover(tmp)!!
        val trunk = repo.headSha()

        runShell(tmp, "git checkout -q -b feature")
        (tmp / "b.txt").writeText("b\n")
        runShell(tmp, "git add -A && git commit -q -m feature")

        val base = repo.branchBaseSha()
        repo.close()
        assertEquals(trunk, base, "the base of feature is the commit main is on")
    }

}
