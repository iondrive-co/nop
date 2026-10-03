package iondrive.nop

import iondrive.nop.agent.Account
import iondrive.nop.agent.AgentSessionStore
import iondrive.nop.agent.handoverTarget
import iondrive.nop.agent.resumesAfterReset
import iondrive.nop.ipc.Handover
import iondrive.nop.ipc.Restart
import iondrive.nop.terminal.Posix
import iondrive.nop.ui.TerminalStore
import iondrive.nop.ui.ToolTab
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * nop's restart, end to end: what is handed over on the way out, and where it goes on the way back
 * in. The mechanism — the exec, the manifest, the checks — is [Handover]'s; this is the part that
 * knows about nop's windows and tabs.
 *
 * In three steps, because the windows have to come down in between. [prepare] runs when the user
 * agrees to the restart, on the UI thread: it writes the agent tabs down and takes every live agent
 * and shell off its terminal. The windows then close the ordinary way, so everything that saves on
 * the way out (editor buffers, state files) does. Then [carryOut], from `main` once the application
 * has ended, writes the manifest and execs the new build in this process.
 */
object RestartInPlace {

    /** A restart the user has agreed to, waiting for the windows to close. */
    class Pending(val launcher: Path, val out: Handover.Outgoing)

    @Volatile
    var pending: Pending? = null
        private set

    private val carried = AtomicBoolean(false)

    /**
     * Gets this nop ready to be replaced by [launcher]: writes down every project's agent tabs, then
     * stops every running agent and shell reading its terminal so it can be handed over (see
     * [iondrive.nop.agent.AgentSession.handOff]). On the UI thread, before the windows close.
     *
     * Arms a watchdog too: if the windows have not finished closing in [WATCHDOG_MS], the exec goes
     * ahead without them, because every terminal is already waiting on it.
     */
    fun prepare(launcher: Path) {
        if (!Handover.supported) {
            Log.info("restarting: $launcher starts once this nop has exited")
            pending = Pending(launcher, Handover.Outgoing())
            return
        }
        for ((root, _, sessions) in AgentSessionStore.byRoot()) {
            // The rows are what puts back the tabs that are not running, and the order of all of them.
            // Normally saved as they change, on a coroutine; written here as well so a change from the
            // last moment cannot still be in flight when the process is replaced.
            Settings.saveOpenAgents(root, sessions.sessions.mapNotNull { it.asOpenAgent() })
        }
        val out = Handover.Outgoing()
        for ((root, projects, sessions) in AgentSessionStore.byRoot()) {
            for (s in sessions.sessions) {
                s.handOff(root, projects, selected = sessions.selectedId == s.sessionId)?.let { out.agents += it }
            }
        }
        for ((root, projects, terminals) in TerminalStore.byRoot()) {
            for (run in terminals.shells.sessions) {
                val pty = run.session.handOff() ?: continue
                out.shells += Handover.ShellRecord(
                    root = root.toString(),
                    projects = projects.map { it.toString() },
                    dir = root.toString(),
                    title = run.title,
                    selected = terminals.shells.selectedId == run.id,
                ) to pty
            }
            terminals.sessionTab?.let { out.views[root.toString()] = it.name }
        }
        Log.info("restarting: handing over ${out.agents.size} agent tab(s) and ${out.shells.size} terminal(s) to $launcher")
        pending = Pending(launcher, out)
        thread(isDaemon = true, name = "restart-watchdog") {
            Thread.sleep(WATCHDOG_MS)
            Log.warn("restarting: the windows have not closed after ${WATCHDOG_MS / 1000}s; going ahead without them")
            carryOut()
        }
    }

    /**
     * Replaces this process with the pending build. Never returns: either the exec happens, or it
     * fails and the new build is started the ordinary way while this one exits — the terminals then
     * hang up as they would at any exit, and the new nop resumes each agent from its transcript.
     */
    fun carryOut(): Nothing {
        val p = pending ?: exitProcess(0)
        if (!carried.compareAndSet(false, true)) {
            // The other caller is already on its way into the exec.
            while (true) Thread.sleep(1_000)
        }
        val handed = p.out.pids
        val strays = ProcessHandle.current().children().map { it.pid() }.filter { it !in handed }.toList()
        val manifest = runCatching { Handover.write(p.out, strays) }
            .onFailure { Log.error("restarting: could not write the handover", it) }
            .getOrNull()
        if (manifest != null && Handover.supported) {
            Handover.exec(p.launcher, manifest, p.out.fds, Restart.successorEnvironment(System.getenv()))
        }
        runCatching { Restart.startSuccessor(p.launcher) }.onFailure { Log.error("restart: could not start ${p.launcher}", it) }
        exitProcess(0)
    }

    /**
     * Puts the tabs the last nop handed over back where they were, before any window opens, and
     * starts reading their terminals. On the UI thread, since that is where a terminal's widget is
     * made. A tab whose account has since gone, or whose terminal no tab claims, is hung up.
     */
    fun place(incoming: Handover.Incoming, accounts: List<Account>) {
        for ((record, _) in incoming.agents.toList()) {
            val account = accounts.firstOrNull { it.name == record.account && it.provider.id == record.provider }
            if (account == null) {
                Log.warn("restart: not carrying over agent tab ${record.title}: no account called ${record.account}")
                continue
            }
            val (_, adopted) = Handover.claimAgent(record.sessionId) ?: continue
            val root = Path.of(record.root)
            val projects = record.projects.map { Path.of(it) }.ifEmpty { listOf(root) }
            val sessions = projects.map { AgentSessionStore.of(root, it) }.last()
            // Until the project's own window is up to push the live settings in, the ones on disk.
            sessions.handoverTarget = { from -> accounts.handoverTarget(from) }
            sessions.resumesAfterReset = { from -> accounts.resumesAfterReset(from) }
            val session = sessions.adopt(record, adopted, root.toFile(), account)
            runCatching { session.session.getOrCreateWidget() }
                .onFailure { Log.error("restart: could not attach agent tab ${record.title}", it) }
        }
        val roots = (incoming.shells.map { it.first.root to it.first.projects } +
            incoming.views.keys.map { it to emptyList() }).groupBy({ it.first }, { it.second })
        for ((root, projectLists) in roots) {
            val rootPath = Path.of(root)
            val projects = projectLists.flatten().distinct().map { Path.of(it) }.ifEmpty { listOf(rootPath) }
            val terminals = projects.map { TerminalStore.of(rootPath, it) { TerminalStore.Terminals.forProject(rootPath) } }.last()
            incoming.views[root]?.let { name -> terminals.sessionTab = ToolTab.entries.firstOrNull { it.name == name } }
            for (run in terminals.shells.sessions) {
                if (run.session.isStarted) continue
                if (run.session.isAdopted) runCatching { run.session.getOrCreateWidget() }
                    .onFailure { Log.error("restart: could not attach a terminal in $root", it) }
            }
        }
        Handover.hangUpUnclaimed()
    }

    /** The launcher runs a restart would stop, by tab name: they are put back waiting to be run again. */
    fun runsThatStop(): List<String> =
        TerminalStore.byRoot().flatMap { (_, _, terminals) -> terminals.runs.sessions.filter { it.session.running }.map { it.title } }

    /**
     * Picks up the process's other children once the new build is running: the git and search
     * processes the last nop had going when it was replaced. They are this process's children still,
     * and one that exits unreaped stays a zombie for as long as nop runs.
     */
    fun reapStrays(pids: List<Long>) {
        if (pids.isEmpty()) return
        thread(isDaemon = true, name = "restart-strays") { pids.forEach { Posix.waitFor(it.toInt()) } }
    }

    private const val WATCHDOG_MS = 30_000L
}
