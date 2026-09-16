package iondrive.nop.agent

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import iondrive.nop.Log
import iondrive.nop.Settings
import java.io.File

/**
 * The agent sessions open in one project, and which of them the Agent tab is showing.
 *
 * A cousin of [RunSessions][iondrive.nop.ui.RunSessions] rather than a sibling, and the difference
 * is the lifetime. The runs are owned by the composition that draws them, so they die with it; these
 * are handed out by [AgentSessionStore] and live as long as nop does, because a model half-way
 * through a refactor is not something to throw away because the user looked at another window. What
 * ends a session is closing its tab, closing the project, or quitting nop — see [disposeAll].
 *
 * Unlike the terminals, nothing is opened eagerly. A vendor CLI is a real account and real quota,
 * so it starts when the user picks one — with no session, the Agent tab shows the picker. The tabs
 * that were open when nop last exited are the one exception, and even they run nothing until looked
 * at: see [restore].
 */
class AgentSessions {
    private val _sessions = mutableStateListOf<AgentSession>()
    val sessions: List<AgentSession> get() = _sessions

    /**
     * The session on screen, or null for the picker. Null is a normal state, not an empty one: it
     * is how you get back to the list to start a second session beside the first.
     */
    var selectedId: String? by mutableStateOf(null)
        private set

    val selected: AgentSession? get() = _sessions.firstOrNull { it.sessionId == selectedId }

    /** Launches [account] at [dir] as a new session and shows it. */
    fun open(
        dir: File,
        account: Account,
        seed: String? = null,
        resumeId: String? = null,
        /** HEAD as the caller sees it now — see [AgentSession.baselineSha]. */
        baselineSha: String? = null,
    ): AgentSession {
        val session = AgentSession(dir, account, seed, resumeId, baselineSha = baselineSha)
        _sessions.add(session)
        selectedId = session.sessionId
        pickerTabVisible = true
        return session
    }

    fun select(id: String?) {
        if (id == null || _sessions.any { it.sessionId == id }) selectedId = id
    }

    /**
     * Whether [restore] has already run for this project. Once per nop run, not once per look at the
     * project: this collection outlives the composition that draws it (see [AgentSessionStore]), so
     * coming back to the project — or the accounts being re-read behind the settings dialog — must
     * not put a second copy of every tab in the strip.
     */
    private var restored = false

    /**
     * Puts back the agent tabs [rows] records, in order, each resuming the conversation it was in.
     *
     * Nothing is selected afterwards, for the same reason a restored run or git log isn't: bringing
     * the strip back is one claim, and deciding which session the user wants to be looking at first
     * thing after a start is a different and worse one.
     *
     * Nothing is spawned here either, and that is not an accident of the implementation — a session
     * starts no PTY until the panel asks it for a widget, so the CLI behind a restored tab is run
     * when the user opens that tab and not before. Six sleeping tabs cost six event-log handles.
     *
     * A row whose account is no longer configured is skipped: the row names which quota to spend,
     * and an account the user has since deleted is not one nop may pick a replacement for.
     */
    fun restore(rows: List<Settings.OpenAgent>, dir: File, accounts: List<Account>) {
        if (restored) return
        restored = true
        // Logged even when there is nothing, because "no tabs came back" has two causes that look
        // identical on screen — a state file with no rows in it, and a restore that never ran — and
        // only one of them is a bug.
        Log.info("restoring ${rows.size} agent tab(s) in ${dir.name}")
        rows.forEach { row ->
            if (_sessions.any { it.sessionId == row.sessionId }) return@forEach
            val account = accounts.firstOrNull {
                it.name == row.account && it.provider.id == row.provider
            }
            if (account == null) {
                Log.warn("not restoring agent tab ${row.title}: no account called ${row.account}")
                return@forEach
            }
            Log.info("restoring agent tab ${row.title} on ${row.account}")
            _sessions.add(
                AgentSession(
                    projectDir = dir,
                    account = account,
                    resumeId = row.nativeSessionId,
                    sessionId = row.sessionId,
                    restoredTitle = row.title,
                    titleByUser = row.titleIsUsers,
                    baselineSha = row.baselineSha.takeIf { it.isNotBlank() },
                ),
            )
        }
    }

    /**
     * Renames the tab behind [id]. The terminals have had this since they arrived, and an agent tab
     * needs it more: "claude-main" says which account is running, never which piece of work it is
     * doing, and two tabs on one account are otherwise indistinguishable.
     */
    fun rename(id: String, title: String) {
        _sessions.firstOrNull { it.sessionId == id }?.rename(title)
    }

    /**
     * Whether the strip carries a tab for the picker — whether or not a session is running.
     *
     * It is drawn beside the sessions rather than only in place of them, because the picker is the
     * one place an *earlier* session can be resumed from and the "+" starts a new one instead of
     * opening it. Closing it takes it out, exactly as closing the last terminal leaves the strip
     * with only its "+". Pressing "+" puts it back, and so does opening a session.
     */
    var pickerTabVisible: Boolean by mutableStateOf(true)
        private set

    /** Shows the picker without disturbing any running session. */
    fun showPicker() {
        selectedId = null
        pickerTabVisible = true
    }

    /** Takes the picker's tab out of the strip. The "+" is how it comes back. */
    fun hidePickerTab() {
        pickerTabVisible = false
    }

    /** Kills the session behind [id] and drops it from the strip. */
    fun close(id: String) {
        val idx = _sessions.indexOfFirst { it.sessionId == id }
        if (idx < 0) return
        _sessions.removeAt(idx).dispose()
        // Fall back to the picker rather than to a neighbour: after closing a session the useful
        // next thing is almost always starting another, and the picker is where that lives.
        if (selectedId == id) selectedId = null
    }

    /**
     * Kills every session in the project — the project's last tab closed, or nop exiting.
     *
     * Not a look at another window or another project tab, which is what this used to mean and what
     * made a running agent something the user could lose by clicking on the wrong thing. See
     * [AgentSessionStore] for what calls this now.
     */
    fun disposeAll() {
        _sessions.forEach { it.dispose() }
        _sessions.clear()
        selectedId = null
    }
}
