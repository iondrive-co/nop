package iondrive.nop.agent

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

/**
 * The agent sessions open in one project, and which of them the Agent tab is showing.
 *
 * The sibling of [RunSessions][iondrive.nop.ui.RunSessions], and held in the same place for the
 * same reason: the tool panel composes one tab at a time, so a session that lived inside the panel
 * would be killed the moment the user looked at the commit list. It is created per project in
 * `App`, and [disposeAll] runs when that project's composition goes away.
 *
 * Unlike the terminals, nothing is opened eagerly. A vendor CLI is a real account and real quota,
 * so it starts when the user picks one — with no session, the Agent tab shows the picker.
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
    ): AgentSession {
        val session = AgentSession(dir, account, seed, resumeId)
        _sessions.add(session)
        selectedId = session.sessionId
        pickerTabVisible = true
        return session
    }

    fun select(id: String?) {
        if (id == null || _sessions.any { it.sessionId == id }) selectedId = id
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
     * Whether the strip carries a tab for the picker when no session is running.
     *
     * Closing it takes it out, exactly as closing the last terminal leaves the strip with only its
     * "+". Pressing "+" puts it back, and so does opening a session.
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

    /** Kills every session — a project-tab switch, or a closed window. */
    fun disposeAll() {
        _sessions.forEach { it.dispose() }
        _sessions.clear()
        selectedId = null
    }
}
