package iondrive.nop.agent

import org.junit.jupiter.api.AfterEach
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
    @AfterEach
    fun resetStatus() {
        CliUpdates.status = CliUpdates.Status.Idle
    }
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

    @Test
    fun `manual update checks all installed updatable providers, refreshes models, and publishes results`() {
        val checked = mutableListOf<Path>()
        var refreshedAccounts: List<Account>? = null
        val configuredAccounts = listOf(Account("codex-alpha", Provider.OpenAI, "/home/dev/.accounts/codex-alpha"))

        CliUpdates.runUpdates(
            configuredOnly = false,
            accountsProvider = { configuredAccounts },
            locateBinary = { provider ->
                when (provider) {
                    Provider.OpenAI -> Path.of("/bin/codex")
                    Provider.Anthropic, Provider.Local -> Path.of("/bin/claude")
                    Provider.Antigravity -> Path.of("/bin/agy")
                }
            },
            runUpdate = { binary ->
                checked.add(binary)
                if (binary.fileName.toString() == "codex") {
                    CliUpdates.Outcome(true, "exit 0: Codex CLI 0.169.1 installed successfully.")
                } else {
                    CliUpdates.Outcome(false, "exit 1: network error")
                }
            },
            refreshModels = { refreshedAccounts = it },
        )

        assertEquals(listOf(Path.of("/bin/codex"), Path.of("/bin/claude")), checked)
        assertEquals(configuredAccounts, refreshedAccounts)
        val done = CliUpdates.status as CliUpdates.Status.Done
        assertEquals(2, done.results.size)
        assertEquals(Provider.OpenAI, done.results[0].provider)
        assertTrue(done.results[0].outcome.success)
        assertEquals(Provider.Anthropic, done.results[1].provider)
        assertFalse(done.results[1].outcome.success)
    }

    @Test
    fun `scheduled update skips providers without configured accounts`() {
        val checked = mutableListOf<Path>()
        var refreshed = false

        CliUpdates.runUpdates(
            configuredOnly = true,
            accountsProvider = {
                listOf(Account("claude-alpha", Provider.Anthropic, "/home/dev/.accounts/claude-alpha"))
            },
            locateBinary = { provider -> Path.of("/bin/${provider.binary}") },
            runUpdate = { binary ->
                checked.add(binary)
                CliUpdates.Outcome(true, "exit 0: up to date")
            },
            refreshModels = { refreshed = true },
        )

        assertEquals(listOf(Path.of("/bin/claude")), checked)
        assertTrue(refreshed)
        val done = CliUpdates.status as CliUpdates.Status.Done
        assertEquals(listOf(Provider.Anthropic), done.results.map { it.provider })
    }

    @Test
    fun `refreshModels bumps modelCatalogGeneration`() {
        val before = Usage.modelCatalogGeneration
        Usage.refreshModels(emptyList())
        assertEquals(before + 1, Usage.modelCatalogGeneration)
    }
}
