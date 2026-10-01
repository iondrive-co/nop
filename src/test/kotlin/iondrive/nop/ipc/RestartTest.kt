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
}
