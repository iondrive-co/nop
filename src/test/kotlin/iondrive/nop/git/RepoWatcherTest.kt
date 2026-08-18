package iondrive.nop.git

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class RepoWatcherTest {
    @Test
    fun `an unsynced tree reports UNKNOWN`(@TempDir tmp: Path) {
        val repo = initRepo(tmp)
        RepoWatcher().use { watcher ->
            assertEquals(
                RepoWatcher.UNKNOWN, watcher.generation(tmp),
                "a tree nobody has registered must never read as quiet",
            )
        }
        repo.close()
    }

    @Test
    fun `a quiet tree holds its count and an edit moves it`(@TempDir tmp: Path) {
        val repo = initRepo(tmp)
        (tmp / "a.txt").writeText("v1\n")
        RepoWatcher().use { watcher ->
            val start = settle(watcher, repo)
            assertNotEquals(RepoWatcher.UNKNOWN, start, "a synced tree reports a real count")
            assertTrue(
                stayedQuiet(watcher, repo, start),
                "nothing touched the tree, so the count must not move on its own",
            )

            (tmp / "a.txt").writeText("v2\n")
            assertTrue(awaitChange(watcher, repo, start), "editing a watched file moves the count")
        }
        repo.close()
    }

    @Test
    fun `staging a file moves the count`(@TempDir tmp: Path) {
        val repo = initRepo(tmp)
        (tmp / "a.txt").writeText("v1\n")
        RepoWatcher().use { watcher ->
            val start = settle(watcher, repo)
            // Nothing in the working tree changes here — only .git/index — so this is the watch on
            // the git directory being exercised, not the tree.
            runShell(tmp, "git add a.txt")
            assertTrue(awaitChange(watcher, repo, start), "staging changes what status reports")
        }
        repo.close()
    }

    @Test
    fun `a write inside an ignored directory does not move the count`(@TempDir tmp: Path) {
        val repo = initRepo(tmp)
        (tmp / ".gitignore").writeText("logs/\n")
        (tmp / "logs").createDirectories()
        RepoWatcher().use { watcher ->
            val start = settle(watcher, repo)
            (tmp / "logs" / "run.txt").writeText("noise\n")
            assertTrue(
                stayedQuiet(watcher, repo, start),
                "an ignored tree cannot change what status reports, so it must not cost a walk",
            )
        }
        repo.close()
    }

    @Test
    fun `a directory created after the sync gets its own watch`(@TempDir tmp: Path) {
        val repo = initRepo(tmp)
        RepoWatcher().use { watcher ->
            val start = settle(watcher, repo)
            val watchesBefore = watcher.watchCount(tmp)

            (tmp / "fresh").createDirectories()
            assertTrue(awaitChange(watcher, repo, start), "a new directory is itself a change")

            val afterCreate = settle(watcher, repo)
            assertEquals(
                watchesBefore + 1, watcher.watchCount(tmp),
                "the new directory is registered, not left as a hole in the coverage",
            )
            (tmp / "fresh" / "f.txt").writeText("x\n")
            assertTrue(
                awaitChange(watcher, repo, afterCreate),
                "so a write inside the new directory is seen too",
            )
        }
        repo.close()
    }

    @Test
    fun `a tree past the ceiling is left unwatched and reports UNKNOWN`(@TempDir tmp: Path) {
        val repo = initRepo(tmp)
        repeat(4) { (tmp / "d$it").createDirectories() }
        RepoWatcher(maxDirs = 2).use { watcher ->
            watcher.sync(repo)
            assertEquals(
                RepoWatcher.UNKNOWN, watcher.generation(tmp),
                "a tree too large to watch must always send the caller back to walking it",
            )
            assertEquals(0, watcher.watchCount(tmp), "and none of it should have been registered")
        }
        repo.close()
    }

    @Test
    fun `unwatch releases the watches and goes back to UNKNOWN`(@TempDir tmp: Path) {
        val repo = initRepo(tmp)
        RepoWatcher().use { watcher ->
            settle(watcher, repo)
            assertTrue(watcher.watchCount(tmp) > 0, "the tree was watched to begin with")

            watcher.unwatch(tmp)

            assertEquals(0, watcher.watchCount(tmp), "watches released")
            assertEquals(RepoWatcher.UNKNOWN, watcher.generation(tmp), "and no longer vouched for")
        }
        repo.close()
    }

    // ---- helpers -------------------------------------------------------------------------------

    /**
     * Syncs until the count stops moving, then returns it — the fixture's own `git init` and file
     * writes are still landing as events when a test starts, and a test that read the count once
     * would be racing them. Each pass re-syncs, which is what a poller does every tick.
     */
    private fun settle(watcher: RepoWatcher, repo: GitRepo): Long {
        var last = RepoWatcher.UNKNOWN
        var stable = 0
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            watcher.sync(repo)
            val now = watcher.generation(repo.rootDir)
            if (now != RepoWatcher.UNKNOWN && now == last) {
                if (++stable >= 4) return now
            } else {
                stable = 0
                last = now
            }
            Thread.sleep(POLL_MS)
        }
        error("watcher never settled on a count for ${repo.rootDir}")
    }

    /** True once the count leaves [from] — UNKNOWN counts, it also means "walk again". */
    private fun awaitChange(watcher: RepoWatcher, repo: GitRepo, from: Long): Boolean {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            watcher.sync(repo)
            val now = watcher.generation(repo.rootDir)
            if (now == RepoWatcher.UNKNOWN || now != from) return true
            Thread.sleep(POLL_MS)
        }
        return false
    }

    /** True if the count stays at [from] for long enough that a real event would have arrived. */
    private fun stayedQuiet(watcher: RepoWatcher, repo: GitRepo, from: Long): Boolean {
        val deadline = System.currentTimeMillis() + QUIET_MS
        while (System.currentTimeMillis() < deadline) {
            watcher.sync(repo)
            if (watcher.generation(repo.rootDir) != from) return false
            Thread.sleep(POLL_MS)
        }
        return true
    }

    private fun initRepo(dir: Path): GitRepo {
        runShell(dir, "git init -q && git config user.email t@x && git config user.name T")
        // Bounded at dir: Gradle puts the test worker's tmpdir inside nop's own working tree, so an
        // unbounded discover would climb out of the fixture and find nop's .git instead.
        return checkNotNull(GitRepo.discover(dir, ceiling = dir)) { "no repository at $dir" }
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

    private companion object {
        const val TIMEOUT_MS = 15_000L
        const val QUIET_MS = 1_500L
        const val POLL_MS = 25L
    }
}
