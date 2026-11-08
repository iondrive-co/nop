package iondrive.nop.agent

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.UUID

/** Keeps CLI overrides on a private backend, so the TUI can accept input separately from its keys. */
internal object CodexLaunch {
    const val SOCKET_ENV = "NOP_CODEX_CONTROL_SOCKET"
    private const val DIRECTORY_ENV = "NOP_CODEX_CONTROL_DIRECTORY"

    /** Adopted clients keep their original argv, so they also keep any missing display settings. */
    fun needsTerminalUpgrade(command: AgentCommand): Boolean {
        if (command.env[SOCKET_ENV] == null) return true
        val separator = command.argv.indexOf("--nop-terminal--")
        val limit = "tui.terminal_resize_reflow_max_rows=${iondrive.nop.terminal.NopTerminalSettings.SCROLLBACK_LINES}"
        return separator < 0 || limit !in command.argv.drop(separator + 1)
    }

    fun wrap(command: AgentCommand, socketDirectory: Path? = AgentSocket.socketDir()): AgentCommand {
        if (System.getProperty("os.name").startsWith("Windows", true)) return command
        val dir = socketDirectory ?: return command
        val name = "cx-${UUID.randomUUID().toString().replace("-", "")}.sock"
        var temporary: Path? = null
        var socket = dir.resolve(name)
        if (socket.toString().toByteArray().size >= 104) {
            temporary = Files.createTempDirectory(Path.of("/tmp"), "nop-cx-",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
            socket = temporary.resolve(name)
        }
        val body = javaClass.getResourceAsStream("/agent/codex-terminal.sh")!!.use { it.readBytes() }
        val hash = MessageDigest.getInstance("SHA-256").digest(body).take(6).joinToString("") { "%02x".format(it) }
        val script = Accounts.dataRoot().resolve("bin/codex-terminal-$hash.sh")
        if (!Files.isRegularFile(script)) {
            OwnerOnly.directory(script.parent)
            val temporary = Files.createTempFile(script.parent, ".codex-terminal", ".sh")
            Files.write(temporary, body)
            Files.move(temporary, script, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            OwnerOnly.tighten(script)
        }
        val server = mutableListOf<String>()
        val terminal = mutableListOf<String>()
        var index = 1
        while (index < command.argv.size) {
            val arg = command.argv[index++]
            if (arg == "-c") {
                val setting = command.argv[index++]
                server += listOf(arg, setting)
                // The client reads its own display settings and chooses a fresh thread's sandbox.
                // Config values also work on resume, where permission flags are rejected.
                val key = setting.substringBefore('=')
                if (key == "sandbox_mode" || key.startsWith("tui.") || key.startsWith("notice.")) {
                    terminal += listOf(arg, setting)
                }
            } else terminal += arg
        }
        return command.copy(
            argv = listOf("/bin/bash", script.toString(), socket.toString(), command.argv.first(), temporary?.toString().orEmpty()) + server +
                listOf("--nop-terminal--", "--remote", "unix://$socket") + terminal,
            env = command.env + (SOCKET_ENV to socket.toString()) +
                ("CODEX_HOME" to Path.of(command.env.getValue("HOME")).resolve(".codex").toString()) +
                temporary?.let { mapOf(DIRECTORY_ENV to it.toString()) }.orEmpty(),
        )
    }

    fun cleanup(command: AgentCommand) {
        command.env[DIRECTORY_ENV]?.let { runCatching { Files.deleteIfExists(Path.of(it)) } }
    }
}
