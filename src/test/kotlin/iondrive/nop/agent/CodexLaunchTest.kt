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
