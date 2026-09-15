package iondrive.nop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import iondrive.nop.terminal.TerminalSession
import java.io.File
import javax.swing.JPanel
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.SimpleTabContent
import org.jetbrains.jewel.ui.component.TabData
import org.jetbrains.jewel.ui.component.TabStrip
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.theme.defaultTabStyle

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
 * Held in [App] rather than inside the panel because the tool panel composes one tab at a time: a
 * run has to keep going — and keep its scrollback — while the user is reading the commit list or
 * searching, and it must survive being switched away from and back to.
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
     * Kills every run. Called when the project's composition goes away (a project-tab switch, a
     * closed window) — a PTY nothing can reach any more would otherwise keep its child process,
     * and whatever ports it holds, for the rest of the session.
     */
    fun disposeAll() {
        _sessions.forEach { it.session.dispose() }
        _sessions.clear()
        selectedId = null
    }
}

/**
 * The Run tool tab: a strip of the terminals started from the launcher menu, over the selected
 * one's output.
 *
 * The strip is a second, closeable tier inside a tool tab that is itself not closeable — the Run
 * tab is always there, what is in it comes and goes. Closing a run here is what stops it, so the
 * strip's × is a kill, not a hide.
 */
@Composable
fun RunPanel(state: RunSessions, cards: JPanel) {
    val selected = state.selected
    if (selected == null) {
        ToolPanelMessage("Run a script from the ▶ menu above the project tree to see its output here")
        return
    }

    val tabs = state.sessions.map { run ->
        TabData.Default(
            selected = run.id == selected.id,
            closable = true,
            onClose = { state.close(run.id) },
            onClick = { state.select(run.id) },
            content = { tabState -> SimpleTabContent(label = run.title, state = tabState) },
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TabStrip(tabs = tabs, style = JewelTheme.defaultTabStyle)
        Box(modifier = Modifier.fillMaxSize()) {
            TerminalView(selected, cards)
        }
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
private fun ToolPanelMessage(text: String) {
    Box(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text)
    }
}
