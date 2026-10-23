package iondrive.nop.ipc

import iondrive.nop.terminal.InheritedEnvironment
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * The running nop starting the next one, which is how `nop --restart` ends once the user agrees.
 *
 * The successor is started by this nop and not by whoever asked, so it gets this nop's environment
 * (the one the desktop gave it) rather than an agent's. It waits for this process to exit before it
 * starts, so it finds no running nop to forward to or take over from, and puts back every tab this
 * one saved on the way out, the same as a start from the desktop.
 */
object Restart {

    /**
     * The launcher this nop was started from, which is where a rebuild puts the new build. Null when
     * nop runs some other way (`./gradlew run`, a bare `java`), with no launcher to start again.
     */
    fun launcher(): Path? =
        System.getProperty("jpackage.app-path")
            ?.let { runCatching { Paths.get(it) }.getOrNull() }
            ?.takeIf { Files.isExecutable(it) }

    /**
     * The launcher of the release installed under the user's home — `~/.local/opt/nop/bin/nop`, or
     * under `$NOP_OPT_DIR`, as scripts/install-release.sh lays it out — for a restart that moves a nop
     * run from a checkout's build back onto releases. Returns why not instead when there is none
     * there, or what is there is itself a build: restarting into that would not leave the build.
     */
    fun releaseLauncher(
        userHome: Path = Paths.get(System.getProperty("user.home")),
        optDir: String? = System.getenv("NOP_OPT_DIR"),
    ): Result<Path> {
        val home = (optDir?.takeIf { it.isNotBlank() }?.let { Paths.get(it) } ?: userHome.resolve(".local/opt")).resolve("nop")
        val launcher = home.resolve("bin").resolve("nop")
        return when {
            !Files.isExecutable(launcher) -> Result.failure(
                IllegalStateException("no release is installed at $home; install one with scripts/install-release.sh"),
            )
            iondrive.nop.update.Installation.isOwnBuild(launcher) -> Result.failure(
                IllegalStateException("$home holds a build from a checkout, not a release; scripts/install-release.sh --replace-build installs one"),
            )
            else -> Result.success(launcher)
        }
    }

    /**
     * Starts [launcher] once this process has exited. Throws when it cannot be started, in which case
     * the caller should not quit either.
     */
    fun startSuccessor(launcher: Path) {
        val builder = ProcessBuilder(successorCommand(launcher, ProcessHandle.current().pid()))
        builder.environment().clear()
        builder.environment().putAll(successorEnvironment(System.getenv()))
        // This nop's own stdin, stdout and stderr, which outlive it (/dev/null or the session's log
        // when the desktop started it). Pipes would close when it exits, and the next write would end
        // the successor.
        builder.inheritIO().start()
    }

    /**
     * Replaces this process with [launcher], given [args], before it has done anything that matters:
     * how a nop starting up hands over to a newer version of itself (see
     * [iondrive.nop.update.SelfUpdate.redirectAtStart]). Returns the errno only if the exec failed,
     * and this is then still the nop it was, which carries on starting.
     */
    fun execNow(launcher: Path, args: List<String>): Int {
        iondrive.nop.terminal.Posix.keepOnlyAcrossExec(emptyList())
        return iondrive.nop.terminal.Posix.exec(launcher.toString(), listOf(launcher.toString()) + args, successorEnvironment(System.getenv()))
    }

    /**
     * `sh` waiting for [pid] to be gone, then becoming [launcher]. A minute at most: a nop that has
     * not exited by then is stuck, and the successor's own start deals with it (it takes over a
     * running nop of another build, and forwards to one of its own).
     */
    internal fun successorCommand(launcher: Path, pid: Long): List<String> =
        listOf("/bin/sh", "-c", WAIT_THEN_EXEC, "nop-restart", launcher.toString(), pid.toString())

    /**
     * This nop's environment as the successor gets it. The agent markers are gone whatever this nop
     * was started with, so the successor never takes itself for an agent's launch. So is the
     * variable jpackage's launcher set for this process: inherited, it turns the next launcher's
     * start into a bare `java` that prints its usage and exits.
     */
    internal fun successorEnvironment(own: Map<String, String>): Map<String, String> =
        InheritedEnvironment.of(own).apply { remove("_JPACKAGE_LAUNCHER") }

    private const val WAIT_THEN_EXEC =
        "i=0; while kill -0 \"$2\" 2>/dev/null && [ \"\$i\" -lt 600 ]; do sleep 0.1; i=\$((i + 1)); done; exec \"$1\""
}
