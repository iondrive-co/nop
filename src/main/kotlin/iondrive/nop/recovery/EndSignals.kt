package iondrive.nop.recovery

import iondrive.nop.Log
import iondrive.nop.Settings
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * What nop does when it is told to end by a signal — SIGTERM, SIGHUP or SIGINT — and the record it
 * leaves of ending at all.
 *
 * Left alone, a signal ends nop with nothing written anywhere: once the embedded browser is loaded
 * its own handlers stand in for the JVM's, the process is gone within a second or two, and the
 * service manager counts a death by SIGTERM as an ordinary stop and logs nothing either. Every
 * terminal hangs up and every agent's turn is cut off. The sender that matters is a low-memory
 * killer (earlyoom, systemd-oomd), which picks the largest process when memory runs short — and
 * nop is often that.
 *
 * So nop takes these signals itself, says in its log that one came and how much memory there was,
 * and then:
 *
 *  - with memory short, takes it as the low-memory killer and restarts in place, which gives back
 *    what this process held and keeps every agent and terminal ([restart]). When that cannot be
 *    done it stays up. Once per [REPRIEVE_GAP_MS]: a second signal inside that quits, so a nop that
 *    really is what fills memory, or that somebody wants gone, still goes;
 *  - otherwise quits as closing the last window does ([quit]) — `kill`, a logout, the service
 *    being stopped.
 *
 * SIGKILL cannot be caught. That, and anything else that ends nop without a word, is what
 * [LaunchJournal.Previous.DIED_UP] reports at the next start.
 */
object EndSignals {
    /** At or under this much of memory available, a signal is taken as the low-memory killer's. */
    const val LOW_MEMORY_PERCENT = 12.0

    /** The least time between two signals nop answers by staying alive. */
    const val REPRIEVE_GAP_MS = 10L * 60 * 1000

    /** How often the handlers are set again: the embedded browser replaces them when it starts. */
    private const val REASSERT_MS = 10_000L

    /** How long a quit asked by signal may take before nop ends without its windows having closed. */
    private const val QUIT_WAIT_MS = 45_000L

    /** What a JVM exits with when SIGTERM ends it. */
    private const val EXIT_SIGNALLED = 143

    private val NAMES = listOf("TERM", "HUP", "INT")

    enum class Action { RESTART_IN_PLACE, QUIT }

    /** Memory as the kernel reports it, in kB: the machine's, and what this process holds. */
    data class Memory(val availableKb: Long, val totalKb: Long, val rssKb: Long) {
        val availablePercent: Double get() = if (totalKb > 0) availableKb * 100.0 / totalKb else 100.0

        fun said(): String =
            "${availableKb / 1024} MB of ${totalKb / 1024} MB available (${"%.1f".format(availablePercent)}%), nop itself holding ${rssKb / 1024} MB"
    }

    /**
     * Restarts nop in place, handing agents and terminals over. Returns false when it did not: a Run
     * tab would stop, or the UI thread did not answer. Set once the windows exist.
     */
    @Volatile var restart: (() -> Boolean)? = null

    /** Quits nop the way closing its last window does. Set once the windows exist. */
    @Volatile var quit: (() -> Unit)? = null

    internal var clock: () -> Long = System::currentTimeMillis
    internal var memory: () -> Memory? = ::readMemory
    internal var exit: (Int) -> Unit = { exitProcess(it) }
    internal var quitWaitMs = QUIT_WAIT_MS
    internal var stampFileOverride: Path? = null
    private val stampFile: Path get() = stampFileOverride ?: Settings.configRoot.resolve("nop").resolve("signal-reprieve")

    private val installed = AtomicBoolean(false)
    private val handling = AtomicBoolean(false)
    private val quitting = AtomicBoolean(false)

    /**
     * Takes the signals, and logs the JVM going down however that comes about. For the nop that owns
     * the windows only: a launch that forwards to a running nop should end the way any command does.
     */
    fun install() {
        if (!installed.compareAndSet(false, true)) return
        Runtime.getRuntime().addShutdownHook(
            Thread({ Log.info("nop ending — pid=${ProcessHandle.current().pid()}, journal says ${LaunchJournal.read()?.first ?: "nothing"}") }, "end-trace"),
        )
        if (!claim(report = true)) return
        thread(isDaemon = true, name = "end-signals") {
            while (true) {
                Thread.sleep(REASSERT_MS)
                claim(report = false)
            }
        }
    }

    /**
     * Takes the signals back straight after something that replaces the handlers — the embedded
     * browser starting — rather than up to [REASSERT_MS] later. Nothing before [install].
     */
    fun reclaim() {
        if (installed.get()) claim(report = false)
    }

    /** Makes nop's handler the one each signal reaches. Returns false where signals cannot be taken at all. */
    private fun claim(report: Boolean): Boolean {
        var taken = false
        for (name in NAMES) {
            try {
                sun.misc.Signal.handle(sun.misc.Signal(name)) { received(it.name) }
                taken = true
            } catch (_: IllegalArgumentException) {
                // No such signal on this system, or the JVM was told to leave it alone (-Xrs).
            } catch (t: Throwable) {
                // A runtime image without jdk.unsupported has no sun.misc.Signal.
                if (report) Log.error("cannot take SIG$name: a signal will end nop without a trace", t)
                return false
            }
        }
        return taken
    }

    /** What to do about a signal, given memory now and how long ago the last one was answered by staying alive. */
    internal fun decide(memory: Memory?, sinceReprieveMs: Long?): Action = when {
        memory == null || memory.availablePercent > LOW_MEMORY_PERCENT -> Action.QUIT
        sinceReprieveMs != null && sinceReprieveMs in 0 until REPRIEVE_GAP_MS -> Action.QUIT
        else -> Action.RESTART_IN_PLACE
    }

    /** Signal [name] (without "SIG") arrived. On a thread of the JVM's own, never the UI thread. */
    internal fun received(name: String) {
        val now = memory()
        val said = now?.said() ?: "memory not known"
        if (quitting.get() || !handling.compareAndSet(false, true)) {
            Log.warn("received SIG$name again while acting on the last signal — $said")
            return
        }
        try {
            val time = clock()
            when (decide(now, lastReprieve()?.let { time - it })) {
                Action.RESTART_IN_PLACE -> {
                    Log.warn(
                        "received SIG$name with memory short — $said. Taken as a low-memory killer's: " +
                            "restarting in place to give memory back, keeping agents and terminals",
                    )
                    // Written first: the restart replaces this process, and the next has to know.
                    runCatching {
                        Files.createDirectories(stampFile.parent)
                        Files.writeString(stampFile, time.toString())
                    }
                    val restartInPlace = restart
                    if (restartInPlace == null || !restartInPlace()) {
                        Log.warn(
                            "could not restart in place (nop is not up yet, a Run tab would stop, or the UI did not answer); " +
                                "staying up. Another signal within ${REPRIEVE_GAP_MS / 60_000} min quits nop",
                        )
                    }
                }
                Action.QUIT -> {
                    Log.warn("received SIG$name — $said. Quitting")
                    end()
                }
            }
        } finally {
            handling.set(false)
        }
    }

    private fun end() {
        if (!quitting.compareAndSet(false, true)) return
        val quitNow = quit
        if (quitNow == null) {
            // No windows yet, so nothing to close or save.
            endNow()
            return
        }
        quitNow()
        val wait = quitWaitMs
        quitWatch = thread(isDaemon = true, name = "end-signals-quit") {
            try {
                Thread.sleep(wait)
            } catch (_: InterruptedException) {
                return@thread
            }
            Log.warn("the windows have not closed ${wait / 1000}s after the signal; ending without them")
            endNow()
        }
    }

    /** The thread that ends nop when a quit asked by signal does not finish. */
    @Volatile private var quitWatch: Thread? = null

    private fun endNow() {
        // Asked to end and ending: not a death for the next launch to answer for. Only where this
        // launch has got as far as writing the journal — before that it is still the last one's.
        if (LaunchJournal.read()?.second == ProcessHandle.current().pid()) LaunchJournal.stopped()
        exit(EXIT_SIGNALLED)
    }

    private fun lastReprieve(): Long? = runCatching { Files.readString(stampFile).trim().toLong() }.getOrNull()

    private fun readMemory(): Memory? = runCatching {
        parseMemory(Files.readString(Path.of("/proc/meminfo")), Files.readString(Path.of("/proc/self/status")))
    }.getOrNull()

    /** [meminfo] and [status] as /proc/meminfo and /proc/self/status read; null when the machine's figures are not in them. */
    internal fun parseMemory(meminfo: String, status: String): Memory? {
        fun kb(text: String, key: String): Long? =
            text.lineSequence().firstOrNull { it.startsWith("$key:") }
                ?.substringAfter(':')?.trim()?.substringBefore(' ')?.toLongOrNull()
        val available = kb(meminfo, "MemAvailable") ?: return null
        val total = kb(meminfo, "MemTotal")?.takeIf { it > 0 } ?: return null
        return Memory(available, total, kb(status, "VmRSS") ?: 0)
    }

    internal fun resetForTest() {
        // First, and waited for: a watch left over from one test must not end the JVM under the next.
        quitWatch?.let { it.interrupt(); it.join(5_000) }
        quitWatch = null
        restart = null
        quit = null
        clock = System::currentTimeMillis
        memory = ::readMemory
        exit = { exitProcess(it) }
        quitWaitMs = QUIT_WAIT_MS
        stampFileOverride = null
        handling.set(false)
        quitting.set(false)
    }
}
