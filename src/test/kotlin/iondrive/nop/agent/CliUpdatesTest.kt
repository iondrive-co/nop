package iondrive.nop.agent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

@EnabledOnOs(OS.LINUX, OS.MAC)
class CliUpdatesTest {
    private fun installer(dir: Path, body: String): Path = dir.resolve("vendor-cli").also {
        Files.writeString(it, "#!/bin/sh\n$body\n")
        check(it.toFile().setExecutable(true))
    }

    @Test
    fun `updater receives update and EOF with its launcher directory on PATH`(@TempDir dir: Path) {
        val binary = installer(dir, """
            [ "${'$'}1" = update ] || exit 7
            if read answer; then exit 8; fi
            printf '%s' "${'$'}PATH"
        """.trimIndent())

        val result = CliUpdates.update(binary)

        assertTrue(result.success, result.detail)
        assertTrue(result.detail.startsWith("exit 0: $dir:"), result.detail)
    }

    @Test
    fun `large output cannot block the installer and only its tail is returned`(@TempDir dir: Path) {
        val binary = installer(dir, """
            awk 'BEGIN { for (i = 0; i < 20000; i++) print "downloading"; print "installed" }'
        """.trimIndent())

        val result = CliUpdates.update(binary)

        assertTrue(result.success, result.detail)
        assertTrue(result.detail.endsWith("installed"))
        assertTrue(result.detail.length < 4200)
    }

    @Test
    fun `vendor failures are reported with stderr`(@TempDir dir: Path) {
        val binary = installer(dir, "echo 'update unavailable' >&2\nexit 5")

        val result = CliUpdates.update(binary)

        assertFalse(result.success)
        assertEquals("exit 5: update unavailable", result.detail)
    }

    @Test
    fun `a stalled installer is stopped within the timeout`(@TempDir dir: Path) {
        val binary = installer(dir, "sleep 30 &\nwait")
        val start = System.nanoTime()

        val result = CliUpdates.update(binary, Duration.ofMillis(200))

        assertFalse(result.success)
        assertTrue(result.detail.startsWith("timed out"))
        assertTrue(Duration.ofNanos(System.nanoTime() - start) < Duration.ofSeconds(5))
    }
}
