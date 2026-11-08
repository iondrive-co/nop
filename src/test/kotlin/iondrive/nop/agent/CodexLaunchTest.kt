package iondrive.nop.agent

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

@EnabledOnOs(OS.LINUX, OS.MAC)
class CodexLaunchTest {
    @Test
    fun `remote resumes restore permissions without client overrides`(@TempDir tmp: Path) {
        val account = Account(name = "codex-alpha", provider = Provider.OpenAI, home = tmp.toString())
        val command = CodexLaunch.wrap(Spawn.command(account, tmp.toFile(), resumeId = "thread-fern"), tmp)
        try {
            val separator = command.argv.indexOf("--nop-terminal--")
            val server = command.argv.take(separator)
            val terminal = command.argv.drop(separator + 1)
            assertTrue("sandbox_mode=\"danger-full-access\"" in server)
            assertTrue(Spawn.CODEX_APPROVAL_POLICY in server)
            assertTrue("approvals_reviewer=\"user\"" in server)
            assertFalse("--sandbox" in terminal)
            assertFalse("sandbox_mode=\"danger-full-access\"" in terminal)
            assertTrue("tui.terminal_resize_reflow_max_rows=${iondrive.nop.terminal.NopTerminalSettings.SCROLLBACK_LINES}" in terminal)
            assertTrue("notice.hide_rate_limit_model_nudge=true" in terminal)
            assertFalse(Spawn.CODEX_APPROVAL_POLICY in terminal)
            assertEquals(listOf("resume", "thread-fern"), terminal.takeLast(2))
            assertEquals("--remote", terminal.first())
            assertTrue(command.isResume)
            assertFalse(CodexLaunch.needsTerminalUpgrade(command))
            val withoutDisplaySetting = command.argv.toMutableList()
            val limit = withoutDisplaySetting.indexOfLast { it.startsWith("tui.terminal_resize_reflow_max_rows=") }
            withoutDisplaySetting.removeAt(limit)
            withoutDisplaySetting.removeAt(limit - 1)
            assertTrue(CodexLaunch.needsTerminalUpgrade(command.copy(argv = withoutDisplaySetting)),
                "a backend override cannot configure an adopted terminal's display")
        } finally {
            CodexLaunch.cleanup(command)
        }
    }

    @Test
    fun `a fresh remote client chooses the configured sandbox`(@TempDir tmp: Path) {
        val account = Account(name = "codex-alpha", provider = Provider.OpenAI, home = tmp.toString())
        val command = CodexLaunch.wrap(Spawn.command(account, tmp.toFile()), tmp)
        try {
            val terminal = command.argv.drop(command.argv.indexOf("--nop-terminal--") + 1)
            assertTrue("sandbox_mode=\"danger-full-access\"" in terminal)
            assertFalse(Spawn.CODEX_APPROVAL_POLICY in terminal)
            assertFalse("approvals_reviewer=\"user\"" in terminal)
        } finally {
            CodexLaunch.cleanup(command)
        }
    }

    @Test
    fun `a long runtime path gets a short owner-only control socket and can be cleaned up`(@TempDir tmp: Path) {
        val long = tmp.resolve("nested-runtime-".repeat(12))
        val command = CodexLaunch.wrap(AgentCommand(
            listOf("codex", "-c", "developer_instructions=\"literal text\"", "resume", "thread-orchid"),
            mapOf("HOME" to tmp.toString()), "thread-orchid", true,
        ), long)
        val socket = Path.of(command.env.getValue(CodexLaunch.SOCKET_ENV))
        try {
            assertTrue(socket.toString().toByteArray().size < 104)
            assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(socket.parent)))
            assertEquals(tmp.resolve(".codex").toString(), command.env["CODEX_HOME"])
            val separator = command.argv.indexOf("--nop-terminal--")
            assertTrue(command.argv.indexOf("-c") < separator)
            assertTrue(command.argv.indexOf("resume") > separator)
            assertEquals("thread-orchid", command.nativeSessionId)
        } finally {
            CodexLaunch.cleanup(command)
        }
        assertFalse(Files.exists(socket.parent))
    }
}
