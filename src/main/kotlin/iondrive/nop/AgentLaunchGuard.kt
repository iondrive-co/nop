package iondrive.nop

import iondrive.nop.terminal.InheritedEnvironment
import java.nio.file.Path

/**
 * Refuses agent launches against the user's config or a config with a running nop.
 *
 * Session markers from any supported CLI count because they can change how child agents record
 * and resume conversations. `nop --restart` lets the running nop start its successor from its own
 * environment after the user agrees.
 *
 * An isolated config with no running nop can be used for previews.
 */
object AgentLaunchGuard {

    /** What to say instead of starting, or null when this launch may go ahead. */
    fun refusal(env: Map<String, String>, configRoot: Path, usersConfigRoot: Path, running: () -> Boolean): String? {
        val marker = InheritedEnvironment.sessionMarker(env) ?: return null
        val config = configRoot.resolve("nop")
        val users = configRoot.toAbsolutePath().normalize() == usersConfigRoot.toAbsolutePath().normalize()
        val whose = when {
            running() -> "a nop is running with this config ($config)"
            users -> "this is the user's own config ($config)"
            else -> return null
        }
        return "nop: refusing to start from inside an agent's session ($marker is set): $whose. " +
            "A nop started here would carry this session's environment into every tab it runs. " +
            "Don't start, restart or relaunch the user's " +
            "nop yourself. To load a rebuilt nop, run `nop --restart`: the running nop asks the user, " +
            "then restarts itself. For a nop of your own beside it, give that one its own " +
            "XDG_CONFIG_HOME and XDG_DATA_HOME."
    }
}
