package iondrive.nop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import iondrive.nop.Log
import iondrive.nop.Settings
import iondrive.nop.launchers.Launcher
import iondrive.nop.terminal.TerminalSession
import java.io.File
import javax.swing.JPanel
import org.jetbrains.jewel.ui.component.Text

/**
 * One live terminal: a launcher invocation in the Run panel, or a plain shell behind one of the
 * terminal tabs at the head of the tool strip.
 *
 * Each invocation is its own run (the nanoTime suffix keeps re-runs of the same launcher distinct),
 * so starting a script a second time never collapses onto the one already going. The id is also the
 * name its widget is filed under in the shared [CardLayout][java.awt.CardLayout] panel, so it has to
 * be stable for the life of the run.
 */
class RunSession(override val session: TerminalSession) : TerminalTab {
    override val id: String = "run:${session.title}:${System.nanoTime()}"

    /**
     * What the tab is called. Starts as the session's own name and can be renamed from the tab's
     * context menu — the terminals are the user's workspace, and "Term 3" says less about what is
     * running in it than "server" or "logs" does. Held here rather than on the session because the
     * name is a label on the tab, not something the process behind it knows or cares about.
     */
    var title: String by mutableStateOf(session.title)

    /**
     * This tab as a row for the state file, or null for a session with no launcher behind it — a
     * plain shell, which is nothing to put back at the next start because there is no command to
     * put back.
     */
    fun asOpenRun(): Settings.OpenRun? = session.launcher?.let {
        Settings.OpenRun(name = it.name, command = it.command, title = title)
    }
}

/**
 * Anything the shared terminal card panel can draw: a launcher run, one of the project's shells, or
 * an agent session.
 *
 * [TerminalView] hosts every terminal widget in one Swing `CardLayout` keyed by [id], so the only
 * thing it needs of a tab is a stable id and the session behind it. Pulling that out as an
 * interface is what lets the agent tabs share the panel — and the single `SwingPanel` — with the
 * terminals rather than needing a second one, which Compose Desktop cannot composite correctly.
 */
interface TerminalTab {
    /** Stable for the life of the run: it is the card's name in the shared panel. */
    val id: String
    val session: TerminalSession
}

/**
 * A list of live terminals and which one is showing. There are two of these per project: the
 * launcher runs behind the Run tool tab, and the shells behind the terminal tabs at the head of
 * the tool strip.
 *
 * Held in [TerminalStore] rather than inside the panel because the tool panel composes one tab at a
 * time: a run has to keep going — and keep its scrollback — while the user is reading the commit
 * list or searching, and it must survive being switched away from and back to. That includes
 * switching to another project, which rebuilds [App].
 */
class RunSessions {
    private val _sessions = mutableStateListOf<RunSession>()
    val sessions: List<RunSession> get() = _sessions

    var selectedId: String? by mutableStateOf(null)
        private set

    val selected: RunSession? get() = _sessions.firstOrNull { it.id == selectedId }

    /** Starts [session] as a new run and shows it. */
    fun open(session: TerminalSession): RunSession {
        // Breadcrumb for the same reason TabsState.open logs: the last thing started is the most
        // useful piece of context when nop dies with a run on screen.
        Log.info("open run ${session.title}")
        val run = RunSession(session)
        _sessions.add(run)
        selectedId = run.id
        return run
    }

    /**
     * Opens a plain shell at [dir] and shows it. Every one arrives under the same name — see
     * [TerminalSession.shell] — and is told apart by where it sits in the strip, or by whatever the
     * user renames it to.
     */
    fun openShell(dir: File): RunSession = open(TerminalSession.shell(dir))

    /**
     * Puts back the run tabs [runs] recorded, in order, without running any of them.
     *
     * Nothing is selected afterwards. Restoring tabs is about not losing the strip; deciding that
     * the user was last looking at a script — and flipping the tool panel to it over the commit
     * list — is a different claim, and the wrong one first thing after a start.
     *
     * A bad row is skipped rather than fatal: [Launcher] refuses a blank name or command, and a
     * truncated state file must cost at most the tab it describes.
     */
    fun restore(runs: List<Settings.OpenRun>, dir: File) {
        runs.forEach { row ->
            val launcher = runCatching { Launcher(row.name, row.command) }.getOrNull() ?: return@forEach
            val restored = RunSession(TerminalSession.forLauncher(launcher, dir, deferred = true))
            restored.title = row.title
            _sessions.add(restored)
        }
    }

    fun select(id: String) {
        if (_sessions.any { it.id == id }) selectedId = id
    }

    /**
     * Renames the terminal behind [id]. A blank name is ignored rather than applied: a tab with no
     * label is unclickable in practice, and the user who cleared the field meant to cancel.
     */
    fun rename(id: String, title: String) {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        _sessions.firstOrNull { it.id == id }?.title = trimmed
    }

    /** Stops the run behind [id], tears its widget down and drops it from the strip. */
    fun close(id: String) {
        val idx = _sessions.indexOfFirst { it.id == id }
        if (idx < 0) return
        _sessions.removeAt(idx).session.dispose()
        if (selectedId == id) {
            selectedId = (_sessions.getOrNull(idx) ?: _sessions.getOrNull(idx - 1))?.id
        }
    }

    /**
     * Kills every run. Called when no window has a tab on the project any more, and when nop exits
     * (see [TerminalStore]). A PTY nothing can reach any more would otherwise keep its child
     * process, and whatever ports it holds, for the rest of the session.
     */
    fun disposeAll() {
        _sessions.forEach { it.session.dispose() }
        _sessions.clear()
        selectedId = null
    }
}

/**
 * The Run tool tab: the output of whichever launcher run the tool strip has selected.
 *
 * There used to be a second tab strip in here, one tab per run, under the tool strip's own. Two
 * rows of tabs stacked on a panel a few hundred pixels wide is most of the panel gone before any
 * output is drawn, and it made a run the one live process in the window that wasn't reachable from
 * the strip everything else is on. The runs are tabs in the tool strip now, beside the terminals
 * and the agent sessions they are a sibling of — see [ToolTabs].
 */
@Composable
fun RunPanel(state: RunSessions, cards: JPanel) {
    val selected = state.selected
    if (selected == null) {
        ToolPanelMessage("Run a script from the ▶ menu above the project tree to see its output here")
        return
    }
    Box(modifier = Modifier.fillMaxSize()) {
        TerminalView(selected, cards)
    }
}

/**
 * The panel under the terminal tabs: whichever of them the strip has selected.
 *
 * Nothing selected means the user has closed every terminal. The tabs go with them but the "+"
 * that opens another doesn't, so the panel points at it rather than sending the user elsewhere —
 * pressing it puts a fresh shell right back here.
 */
@Composable
fun TerminalTabPanel(state: RunSessions, cards: JPanel) {
    val selected = state.selected
    if (selected == null) {
        ToolPanelMessage("Press + in the tab strip above to open a terminal at the project root")
        return
    }
    TerminalView(selected, cards)
}

/** The centred line a tool panel shows in place of content it hasn't been given yet. */
@Composable
internal fun ToolPanelMessage(text: String) {
    Box(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text)
    }
}
