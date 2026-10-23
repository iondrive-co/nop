package iondrive.nop.recovery

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.WindowExceptionHandler
import androidx.compose.ui.window.WindowExceptionHandlerFactory
import iondrive.nop.Log
import iondrive.nop.Settings
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.Executors
import javax.swing.SwingUtilities

/**
 * What a window failing to draw costs: that window, rebuilt, and nothing else.
 *
 * Compose has no error boundary below a window. An exception from composing, measuring or drawing
 * anything in it stops the window's recomposer, and a try/catch around part of the tree cannot
 * catch it — a change re-measures the node that changed directly, not through its parent, and
 * composition errors go to the recomposer, not up the tree. (Both were tried.) What Compose does
 * offer is a handler per window, called with the exception instead of the window going down with
 * it. So that is the boundary: the failed window is thrown away and opened again from its
 * [iondrive.nop.Workspace], fresh. The agents and terminals in it outlive the window by design (see
 * [iondrive.nop.agent.AgentSessionStore]), and every other window has a recomposer of its own.
 *
 * Rebuilding alone would only fail again if what failed was a tab, so the tabs the window had on
 * screen are held back first ([Quarantine]): a tab is the one thing in a window whose content comes
 * from outside nop. A failure with nothing to blame climbs a ladder instead —
 *  1. rebuild once, in case it was passing;
 *  2. restart in place, agents and terminals handed over (see [iondrive.nop.RestartInPlace]), once
 *     per run of failures: state outside the window may be what is wrong;
 *  3. stop, and show the window as a plain error with a way to try again. Never a loop.
 *
 * Everything here runs on the UI thread, except writing [showingFile].
 */
@OptIn(ExperimentalComposeUiApi::class)
object WindowRecovery {
    enum class Outcome { IGNORED, REBUILT, RESTARTING, BROKEN }

    private class Record {
        /** Tabs this window has on screen, by recovery key. */
        val showing = LinkedHashSet<String>()

        /** When it failed, within the last [LADDER_MS]. */
        val failures = ArrayDeque<Long>()

        /** Work to save before the window is thrown away: editor buffers not yet autosaved. */
        val rescues = LinkedHashSet<Rescue>()

        /** The newest generation that has failed; its repeat failures are the same failure. */
        var failedGeneration = -1
    }

    /** A rescue registered by [onRescue], removed again by calling it. */
    class Rescue internal constructor(internal val run: () -> Unit)

    private val records = HashMap<Long, Record>()
    private val generations = mutableStateMapOf<Long, Int>()
    private val broken = mutableStateMapOf<Long, String>()

    /**
     * Restarts nop in place to recover, handing agents and terminals over. Returns false when it
     * cannot without losing something — no launcher, or runs that would stop — and so did not.
     */
    var restart: (() -> Boolean)? = null

    /** This launch is the restart a recovery made; another would only repeat it. See [LaunchJournal]. */
    var restartedToRecover = false

    internal var clock: () -> Long = System::currentTimeMillis

    /** Every window's [Record.showing], for [LaunchJournal]: what to hold back if this launch dies. */
    internal var showingFileOverride: Path? = null
    val showingFile: Path get() = showingFileOverride ?: Settings.configRoot.resolve("nop").resolve("showing")
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "recovery-showing").apply { isDaemon = true } }

    /** Bumped to throw window [id] away and open it again. Snapshot state: key the window on it. */
    fun generation(id: Long): Int = generations[id] ?: 0

    /** Why window [id] was given up on, or null while it is drawn as itself. Snapshot state. */
    fun brokenReason(id: Long): String? = broken[id]

    /** The handler for window [id]'s current generation. Make it at the generation it is for. */
    fun handlerFactory(id: Long): WindowExceptionHandlerFactory {
        val generation = generation(id)
        return WindowExceptionHandlerFactory { WindowExceptionHandler { t -> failed(id, generation, t) } }
    }

    /**
     * Tab [key] is on screen in window [id]. Called from composition, before the tab's content is
     * composed, rather than from an effect: a tab whose first composition throws never runs its
     * effects, and it is exactly the tab to blame.
     */
    fun showing(id: Long, key: String) {
        if (record(id).showing.add(key)) persistShowing()
    }

    fun notShowing(id: Long, key: String) {
        if (records[id]?.showing?.remove(key) == true) persistShowing()
    }

    /** Runs [save] before window [id] is thrown away. Call the result's [remove] when it no longer applies. */
    fun onRescue(id: Long, save: () -> Unit): Rescue = Rescue(save).also { record(id).rescues += it }

    fun remove(id: Long, rescue: Rescue) {
        records[id]?.rescues?.remove(rescue)
    }

    /** The user's "try again" on a window given up on: a fresh window, and a fresh ladder. */
    fun retry(id: Long) {
        broken.remove(id)
        records[id]?.failures?.clear()
        rebuild(id)
    }

    /** Window [id] is gone for good. */
    fun forget(id: Long) {
        records.remove(id)
        generations.remove(id)
        broken.remove(id)
        persistShowing()
    }

    /** Window [id], at [generation], threw [t] while drawing. Decides what that costs — see the class docs. */
    internal fun failed(id: Long, generation: Int, t: Throwable): Outcome {
        val r = record(id)
        if (generation != generation(id) || generation <= r.failedGeneration || id in broken) return Outcome.IGNORED
        r.failedGeneration = generation
        Log.error("window $id failed while drawing; recovering it", t)

        r.rescues.toList().forEach { rescue ->
            runCatching { rescue.run() }.onFailure { Log.error("could not save window $id's work before rebuilding it", it) }
        }
        r.rescues.clear()

        val now = clock()
        while (r.failures.isNotEmpty() && now - r.failures.first() > LADDER_MS) r.failures.removeFirst()
        r.failures.addLast(now)

        val blamed = r.showing.filter { Quarantine.entry(it) == null }
        r.showing.clear()
        persistShowing()
        val reason = describe(t)

        return when {
            r.failures.size > MAX_FAILURES -> giveUp(id, reason)
            blamed.isNotEmpty() -> {
                Quarantine.add(blamed, reason, now)
                rebuild(id)
                Outcome.REBUILT
            }
            r.failures.size == 1 -> {
                rebuild(id)
                Outcome.REBUILT
            }
            !restartedToRecover && restart?.invoke() == true -> {
                Log.warn("window $id failed again with nothing on screen to blame; restarting nop to recover")
                Outcome.RESTARTING
            }
            else -> giveUp(id, reason)
        }
    }

    /** One line naming the error and where in nop it came from, for the user and the quarantine. */
    fun describe(t: Throwable): String {
        val root = generateSequence(t) { it.cause }.last()
        val where = root.stackTrace.firstOrNull { it.className.startsWith("iondrive.nop.") }
            ?.let { " (at ${it.fileName}:${it.lineNumber})" }.orEmpty()
        return "${root.javaClass.simpleName}: ${root.message ?: "no message"}$where"
    }

    internal fun showingIn(id: Long): Set<String> = records[id]?.showing?.toSet().orEmpty()

    /** Waits for [showingFile] to hold everything reported so far. */
    internal fun flushShowing() = writer.submit {}.get()

    internal fun resetForTest() {
        records.clear()
        generations.clear()
        broken.clear()
        restart = null
        restartedToRecover = false
    }

    private fun giveUp(id: Long, reason: String): Outcome {
        Log.warn("window $id keeps failing; showing it as an error until the user retries")
        broken[id] = reason
        rebuild(id)
        return Outcome.BROKEN
    }

    // Later rather than now: the handler runs in the middle of the failed window's frame, and the
    // window list it is keyed in belongs to the application's composition, not the window's.
    private fun rebuild(id: Long) {
        val next = generation(id) + 1
        if (SwingUtilities.isEventDispatchThread()) SwingUtilities.invokeLater { generations[id] = next } else generations[id] = next
    }

    private fun record(id: Long) = records.getOrPut(id) { Record() }

    private fun persistShowing() {
        val body = records.values.flatMap { it.showing }.distinct().joinToString("") { "$it\n" }
        val target = showingFile
        writer.execute {
            runCatching {
                Files.createDirectories(target.parent)
                val tmp = target.resolveSibling("${target.fileName}.tmp")
                Files.writeString(tmp, body)
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            }.onFailure { Log.error("could not write $target", it) }
        }
    }

    /** The tabs the last launch had on screen, from [showingFile]. */
    fun lastShowing(): List<String> = runCatching {
        if (Files.exists(showingFile)) Files.readAllLines(showingFile).filter { it.isNotBlank() } else emptyList()
    }.getOrDefault(emptyList())

    /** Failures closer together than this are one run of them, climbing one ladder. */
    private const val LADDER_MS = 60_000L

    /** However much there is to blame, a window failing this often is given up on. */
    private const val MAX_FAILURES = 5
}

/** The id of the window a composable is in, for reporting what it shows — see [WindowRecovery.showing]. */
val LocalWindowId = compositionLocalOf<Long?> { null }
