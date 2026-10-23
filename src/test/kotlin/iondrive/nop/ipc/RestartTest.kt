package iondrive.nop.ipc

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit

class RestartTest {

    @Test
    fun `the successor starts only once the old nop has exited`(@TempDir tmp: Path) {
        val ran = tmp.resolve("ran")
        val launcher = tmp.resolve("nop").also {
            Files.writeString(it, "#!/bin/sh\necho started > '$ran'\n")
            Files.setPosixFilePermissions(it, PosixFilePermissions.fromString("rwx------"))
        }
        val old = ProcessBuilder("sleep", "1").start()

        val successor = ProcessBuilder(Restart.successorCommand(launcher, old.pid())).start()

        Thread.sleep(500)
        assertFalse(Files.exists(ran), "the successor must not start while the old nop is still running")
        assertTrue(old.waitFor(5, TimeUnit.SECONDS))
        assertTrue(successor.waitFor(5, TimeUnit.SECONDS), "the successor should start once it has gone")
        assertEquals("started", Files.readString(ran).trim())
    }

    @Test
    fun `the successor gets this nop's environment without any agent's markers or jpackage's own`() {
        val own = mapOf(
            "PATH" to "/usr/bin",
            "DISPLAY" to ":0",
            "_JPACKAGE_LAUNCHER" to "x",
            "CLAUDECODE" to "1",
            "CODEX_THREAD_ID" to "t",
            "ANTIGRAVITY_CONVERSATION_ID" to "c",
            "NOP_AGENT_SESSION" to "tab",
        )

        assertEquals(mapOf("PATH" to "/usr/bin", "DISPLAY" to ":0"), Restart.successorEnvironment(own))
    }

    @Test
    fun `the release to restart into is the launcher under the opt dir, and only a release`(@TempDir tmp: Path) {
        val opt = tmp.resolve("opt")
        val missing = Restart.releaseLauncher(tmp, opt.toString())
        assertTrue(missing.isFailure, "nothing installed is nothing to restart into")

        val image = Files.createDirectories(opt.resolve("nop-1.3.8").resolve("bin"))
        Files.writeString(image.resolve("nop"), "#!/bin/sh\n")
        Files.setPosixFilePermissions(image.resolve("nop"), PosixFilePermissions.fromString("rwx------"))
        Files.createSymbolicLink(opt.resolve("nop"), opt.resolve("nop-1.3.8"))
        assertEquals(opt.resolve("nop").resolve("bin").resolve("nop"), Restart.releaseLauncher(tmp, opt.toString()).getOrThrow())
        // Without NOP_OPT_DIR it is ~/.local/opt.
        assertTrue(Restart.releaseLauncher(tmp, null).isFailure)

        // A build copied there by installLocalCopy is not a release: restarting into it leaves nothing.
        Files.writeString(opt.resolve("nop-1.3.8").resolve(".built-from-checkout"), "/somewhere\n")
        assertTrue(Restart.releaseLauncher(tmp, opt.toString()).isFailure)
    }
}
