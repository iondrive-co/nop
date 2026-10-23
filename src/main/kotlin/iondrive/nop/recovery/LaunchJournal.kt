package iondrive.nop.recovery

import iondrive.nop.Log
import iondrive.nop.Settings
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * How the last launch ended, for the failures [WindowRecovery] never hears about.
 *
 * A window's own failures are caught as they happen. What is not: the JVM running out of memory, a
 * crash in native code, the user killing a nop that had stopped answering. Each of those can be
 * caused by a tab, and the tab comes back on the next launch and does it again. So a launch writes
 * down that it is starting, and that it is up once it has been for a while; finding "starting" left
 * behind by the last one means it died on the way up, and the tabs it had on screen then
 * ([WindowRecovery.showingFile]) are held back this time — see [Quarantine].
 *
 * One line, `state pid`. Rewritten whole on every change.
 */
object LaunchJournal {
    enum class State {
        /** On the way up: windows opening, tabs being restored. */
        STARTING,

        /** Has been up for long enough to count as having started. */
        UP,

        /** Restarting in place because a window kept failing — see [WindowRecovery]. */
        RECOVERING,

        /** Ended the ordinary way: quit, or restarted. */
        STOPPED,
    }

    /** What the last launch left behind, as this one starts. */
    enum class Previous {
        /** Nothing amiss, or nothing on record. */
        CLEAN,

        /** It never got up. */
        DIED_STARTING,

        /** It restarted to recover from a window that would not stay up. */
        RECOVERING,
    }

    /** Where the journal lives: beside nop's other state, unless a test points it somewhere of its own. */
    internal var fileOverride: Path? = null
    private val file: Path get() = fileOverride ?: Settings.configRoot.resolve("nop").resolve("launch")

    /**
     * This launch is starting, as the nop that owns the windows. Returns what became of the last one.
     *
     * Left behind "starting" counts against the last launch unless its process is still alive under
     * another pid — a nop that is up but has not yet said so, which a launch forwarding to it never
     * gets this far to see, so that is only the window between two launches racing.
     */
    fun begin(pid: Long = ProcessHandle.current().pid(), alive: (Long) -> Boolean = ::isAlive): Previous {
        val last = read()
        write(State.STARTING, pid)
        return when (last?.first) {
            State.STARTING -> if (last.second != pid && alive(last.second)) Previous.CLEAN else Previous.DIED_STARTING
            State.RECOVERING -> Previous.RECOVERING
            else -> Previous.CLEAN
        }
    }

    fun up(pid: Long = ProcessHandle.current().pid()) = write(State.UP, pid)

    fun recovering(pid: Long = ProcessHandle.current().pid()) = write(State.RECOVERING, pid)

    fun stopped(pid: Long = ProcessHandle.current().pid()) = write(State.STOPPED, pid)

    internal fun read(): Pair<State, Long>? = runCatching {
        if (!Files.exists(file)) return null
        val parts = Files.readString(file).trim().split(' ')
        State.valueOf(parts[0]) to parts[1].toLong()
    }.getOrNull()

    private fun write(state: State, pid: Long) {
        runCatching {
            Files.createDirectories(file.parent)
            val tmp = file.resolveSibling("${file.fileName}.tmp")
            Files.writeString(tmp, "${state.name} $pid\n")
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.onFailure { Log.error("could not write $file", it) }
    }

    private fun isAlive(pid: Long): Boolean = ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
}

/**
 * At startup, before any window opens: loads what nop is holding back, records that this launch is
 * starting, and — if the last one died on its way up, or restarted to recover — holds back what it
 * had on screen as well, so a tab that kills nop at startup does it once and not on every launch.
 */
fun recoverFromLastLaunch(pid: Long = ProcessHandle.current().pid()): LaunchJournal.Previous {
    Quarantine.load()
    val previous = LaunchJournal.begin(pid)
    when (previous) {
        LaunchJournal.Previous.DIED_STARTING -> {
            Log.warn("the last nop died before it was up; holding back what it had on screen")
            Quarantine.add(WindowRecovery.lastShowing(), "nop did not start cleanly last time while this tab was on screen")
        }
        LaunchJournal.Previous.RECOVERING -> {
            WindowRecovery.restartedToRecover = true
            Quarantine.add(WindowRecovery.lastShowing(), "nop restarted to recover a failing window while this tab was on screen")
        }
        LaunchJournal.Previous.CLEAN -> {}
    }
    return previous
}
