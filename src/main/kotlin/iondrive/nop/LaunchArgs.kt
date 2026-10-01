package iondrive.nop

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * What nop was asked to do on its command line, decided before anything else happens.
 *
 * A bare launch from a different build is how a rebuilt nop takes over from the one still running
 * (see [iondrive.nop.ipc.SingleInstance]), so an argument read as a bare launch is dangerous: a
 * `nop --help` run to see what the CLI takes would quit the running nop and end every agent tab in
 * it, and a script's `nop "$UNSET"` would forward an empty launch to it. So an argument nop does not
 * understand is an error, and asking for help prints it and touches nothing — no log line, no
 * running instance.
 *
 * A file rather than a directory is accepted and ignored: the desktop entry passes
 * `%F`, so a file dropped on the launcher arrives here, and the launch it means is "show me nop".
 */
sealed interface LaunchArgs {
    /** Open these directories as projects (none: bring nop to the front, or start it). */
    data class Open(val projects: List<Path>) : LaunchArgs

    data object Help : LaunchArgs

    /**
     * Ask the running nop to restart, which it does once the user agrees: how an agent that has
     * rebuilt nop gets the new build loaded without starting one itself (see [AgentLaunchGuard]).
     * Nothing else on the line, since a launch it could also be read as is exactly what it replaces.
     */
    data object Restart : LaunchArgs

    /** Not something nop can do; [why] is what to say before the usage. */
    data class Invalid(val why: String) : LaunchArgs

    companion object {
        fun parse(args: Array<String>, isDirectory: (Path) -> Boolean = Files::isDirectory, exists: (Path) -> Boolean = Files::exists): LaunchArgs {
            if (args.any { it == "-h" || it == "--help" }) return Help
            val projects = mutableListOf<Path>()
            var options = true
            var restart = false
            for (arg in args) {
                when {
                    options && arg == "--" -> options = false
                    options && arg == "--restart" -> restart = true
                    options && arg.startsWith("-") && arg != "-" -> return Invalid("unknown option: $arg")
                    arg.isBlank() -> return Invalid("an empty argument, where a project directory was expected")
                    else -> {
                        val path = runCatching { Paths.get(arg).toAbsolutePath().normalize() }.getOrNull()
                            ?: return Invalid("not a path: $arg")
                        when {
                            isDirectory(path) -> projects.add(path)
                            exists(path) -> Unit
                            else -> return Invalid("no such directory: $arg")
                        }
                    }
                }
            }
            if (restart) return if (args.size == 1) Restart else Invalid("--restart takes nothing else")
            return Open(projects)
        }

        val USAGE: String = """
            |Usage: nop [DIRECTORY...]
            |
            |Opens each DIRECTORY as a project in the nop that is running, or starts nop if none is.
            |With no DIRECTORY, brings the running nop to the front.
            |
            |  -h, --help   print this and exit, without touching a running nop
            |  --restart    ask the running nop to restart; it asks the user first, then starts the
            |               build now on disk from its own environment and puts every tab back
            |
            |There is one nop per config directory (${'$'}XDG_CONFIG_HOME/nop, else ~/.config/nop). Starting
            |a nop built from a different binary than the running one replaces it: the running one quits,
            |ending every agent tab, and the new one puts them back. To run a separate nop beside it, give
            |that one its own XDG_CONFIG_HOME and XDG_DATA_HOME. From inside an agent's session (one of
            |nop's tabs, or Claude Code, Codex or Antigravity anywhere), nop refuses to start against the
            |user's config or one a nop is running with: there, --restart is the way to load a new build.
            |""".trimMargin()
    }
}
