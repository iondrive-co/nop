package iondrive.nop.ui

import iondrive.nop.git.ChangeKind
import iondrive.nop.git.GitRepo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class StatusLoadsTest {
    @Test
    fun `load populates last and returns status`(@TempDir tmp: Path) = runBlocking {
        initGitRepo(tmp)
        (tmp / "change.txt").writeText("dirty\n")

        val status = StatusLoads.load(tmp)
        assertEquals(1, status.changes.size)
        assertEquals("change.txt", status.changes[0].path)
        assertEquals(ChangeKind.UNTRACKED, status.changes[0].kind)

        val cached = StatusLoads.last(tmp)
        assertNotNull(cached)
        assertEquals(status, cached!!.status)
        assertNotNull(cached.headSha)
    }

    @Test
    fun `cancelled caller leaves background walk running to populate last`(@TempDir tmp: Path) = runBlocking {
        initGitRepo(tmp)
        (tmp / "file.txt").writeText("hello\n")

        // Start a load in a child coroutine
        val job = async(Dispatchers.Default) {
            StatusLoads.load(tmp)
        }
        // Wait until load has actually started before cancelling the caller
        var startWaitMs = 0
        while (!StatusLoads.isInFlight(tmp) && StatusLoads.last(tmp) == null) {
            delay(5)
            startWaitMs += 5
            check(startWaitMs < 2000) { "Status load did not start" }
        }
        job.cancel(CancellationException("tab switched away"))

        // Wait until the background task completes
        var waitedMs = 0
        while (StatusLoads.isInFlight(tmp) || StatusLoads.last(tmp) == null) {
            delay(20)
            waitedMs += 20
            check(waitedMs < 5000) { "Background status load timed out" }
        }

        assertFalse(StatusLoads.isInFlight(tmp))
        val cached = StatusLoads.last(tmp)
        assertNotNull(cached)
        assertEquals(1, cached!!.status.changes.size)
        assertEquals("file.txt", cached.status.changes[0].path)
    }

    @Test
    fun `concurrent loads join the same in-flight task`(@TempDir tmp: Path) = runBlocking {
        initGitRepo(tmp)
        (tmp / "concurrent.txt").writeText("data\n")

        val first = async(Dispatchers.Default) { StatusLoads.load(tmp) }
        val second = async(Dispatchers.Default) { StatusLoads.load(tmp) }

        val status1 = first.await()
        val status2 = second.await()

        assertEquals(status1, status2)
        assertEquals(1, status1.changes.size)
        assertEquals("concurrent.txt", status1.changes[0].path)
    }

    private fun initGitRepo(dir: Path) {
        runShell(dir, "git init -q && git config user.email dev@test.local && git config user.name Dev")
        (dir / "initial.txt").writeText("seed\n")
        runShell(dir, "git add -A && git commit -q -m init")
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
