package iondrive.nop.git

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class RailGitPollerTest {
    @Test
    fun `the first sweep reports every project and a quiet sweep reports none`(@TempDir tmp: Path) {
        val clean = initRepo(tmp / "clean")
        val dirty = initRepo(tmp / "dirty")
        (dirty / "pending.txt").writeText("uncommitted\n")

        val clock = FakeClock()
        RepoWatcher().use { watcher ->
            RailGitPoller(watcher, nowMs = clock::now).use { poller ->
                poller.retain(listOf(clean, dirty))

                assertEquals(
                    mapOf(clean to false, dirty to true), poller.sweep(active = null),
                    "nothing has been walked yet, so every project is walked once",
                )
                // The point of the whole class: once each tree is watched and quiet, a tick walks
                // nothing at all, however often it fires and however far the clock moves.
                assertTrue(
                    sweepUntilQuiet(poller, clock) > 0,
                    "the poller must stop walking trees that have not changed",
                )
            }
        }
    }

    @Test
    fun `a background project re-walks only once its interval has passed`(@TempDir tmp: Path) {
        val project = initRepo(tmp / "project")
        val clock = FakeClock()
        RepoWatcher().use { watcher ->
            RailGitPoller(watcher, nowMs = clock::now).use { poller ->
                poller.retain(listOf(project))
                poller.sweep(active = null)
                sweepUntilQuiet(poller, clock)

                // Establish a walk at a known point on the clock: the rate limit runs from the last
                // walk, and sweepUntilQuiet leaves the clock well past it.
                (project / "pending.txt").writeText("uncommitted\n")
                assertEquals(
                    mapOf(project to true), awaitSweep(poller, clock),
                    "the first sweep past the interval picks the change up",
                )

                // Inside the interval nothing is walked however much the tree moves — this is what
                // stops a checkout an agent is writing to from costing what the whole rail used to.
                (project / "more.txt").writeText("also uncommitted\n")
                clock.advance(RailGitPoller.BACKGROUND_INTERVAL_MS / 2)
                assertEquals(
                    emptyMap<Path, Boolean>(), poller.sweep(active = null),
                    "a change inside the interval waits its turn",
                )

                // Deferred, not dropped: the change is still pending on the next eligible sweep.
                clock.advance(RailGitPoller.BACKGROUND_INTERVAL_MS)
                assertEquals(
                    mapOf(project to true), poller.sweep(active = null),
                    "and is walked as soon as the interval has passed",
                )
            }
        }
    }

    @Test
    fun `the active project is left to its own panel`(@TempDir tmp: Path) {
        val active = initRepo(tmp / "active")
        val other = initRepo(tmp / "other")
        (active / "pending.txt").writeText("uncommitted\n")

        val clock = FakeClock()
        RepoWatcher().use { watcher ->
            RailGitPoller(watcher, nowMs = clock::now).use { poller ->
                poller.retain(listOf(active, other))

                assertEquals(
                    setOf(other), poller.sweep(active = active).keys,
                    "the project on screen has a fresh status of its own; walking it again is the " +
                        "duplicated work this skip exists to remove",
                )
                assertTrue(
                    watcher.watchCount(active) > 0,
                    "its tree is still watched, though — its panel reads the same watcher",
                )
            }
        }
    }

    @Test
    fun `closing a rail tab releases its repository and watches`(@TempDir tmp: Path) {
        val kept = initRepo(tmp / "kept")
        val closed = initRepo(tmp / "closed")

        val clock = FakeClock()
        RepoWatcher().use { watcher ->
            RailGitPoller(watcher, nowMs = clock::now).use { poller ->
                poller.retain(listOf(kept, closed))
                poller.sweep(active = null)
                assertTrue(watcher.watchCount(closed) > 0, "watched while the tab was open")

                poller.retain(listOf(kept))

                assertEquals(
                    0, watcher.watchCount(closed),
                    "a closed tab must not leave a tree of watches behind it",
                )

                // Change both trees. Only the one still on the rail may come back from a sweep.
                (kept / "pending.txt").writeText("uncommitted\n")
                (closed / "pending.txt").writeText("uncommitted\n")
                assertEquals(
                    setOf(kept), awaitSweep(poller, clock).keys,
                    "the closed project is not walked again, however much its tree moves",
                )
            }
        }
    }

    // ---- helpers -------------------------------------------------------------------------------

    private class FakeClock(private var millis: Long = 1_000_000L) {
        fun now(): Long = millis
        fun advance(by: Long) {
            millis += by
        }
    }

    /**
     * Sweeps with the clock well past the interval each time until one walks nothing, and returns how
     * many it took. The fixtures' own writes are still arriving as watch events when a test starts,
     * so the first few sweeps legitimately have something to look at.
     */
    private fun sweepUntilQuiet(poller: RailGitPoller, clock: FakeClock): Int {
        var sweeps = 0
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            clock.advance(RailGitPoller.BACKGROUND_INTERVAL_MS * 2)
            sweeps++
            if (poller.sweep(active = null).isEmpty()) return sweeps
            Thread.sleep(POLL_MS)
        }
        error("poller never went quiet")
    }

    /** The first sweep past the interval that actually walks something. */
    private fun awaitSweep(poller: RailGitPoller, clock: FakeClock): Map<Path, Boolean> {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            clock.advance(RailGitPoller.BACKGROUND_INTERVAL_MS * 2)
            val swept = poller.sweep(active = null)
            if (swept.isNotEmpty()) return swept
            Thread.sleep(POLL_MS)
        }
        error("poller never walked the change")
    }

    private fun initRepo(dir: Path): Path {
        runShell(dir, "git init -q && git config user.email t@x && git config user.name T")
        (dir / "committed.txt").writeText("v1\n")
        runShell(dir, "git add -A && git commit -q -m init")
        return dir
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
        const val POLL_MS = 25L
    }
}
