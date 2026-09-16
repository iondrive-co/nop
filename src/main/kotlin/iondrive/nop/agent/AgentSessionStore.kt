package iondrive.nop.agent

import iondrive.nop.Log
import java.nio.file.Path

/**
 * Every project's agent sessions, held for as long as nop is running.
 *
 * This is the one piece of nop's state that deliberately outlives the composition that draws it, and
 * the reason is what a session *is*. Every other collection behind the tool strip can be rebuilt
 * from disk — a git log is re-read, a run tab comes back waiting to be pressed — but an agent session
 * is a model part-way through a piece of work, and the only thing that can be rebuilt from is the
 * conversation the vendor happens to have kept. So it cannot be owned by a Compose lifetime: nop
 * composes one project tab at a time, inside one window at a time, and a session owned there was
 * killed by looking at another project, moving its tab to another window, or closing the window it
 * was started in. Which is what happened: agents running in tabs disappeared when the user switched
 * windows, and the work in them went with the PTY.
 *
 * Sessions are filed under the project's **repo root**, not the tab that opened them, for two
 * reasons. It is the directory the CLI was launched in and the key its state file is written under
 * (`Settings.loadOpenAgents`), so the live sessions and the ones on disk can't drift apart. And a
 * project tab's id is explicitly a runtime identity that is never persisted (see
 * [Workspace][iondrive.nop.Workspace]), so it could not name the same thing across a restart. Two
 * tabs on one repo therefore share one strip of agents, which is also the honest answer: an agent is
 * working in a checkout, not in a tab. With both of those tabs on screen at once — one repo open in
 * two windows — the session is still only drawn in one of them: a terminal is a heavyweight AWT
 * widget and can only have one parent, so it follows whichever window looked at it last.
 *
 * What kills a session: closing its own tab, closing the last project tab that leads here (see
 * [retain]), or nop exiting ([disposeAll]). Parking a window is none of those — a parked window keeps
 * its tabs, so an agent carries on working while the window it was started in is put away, and it is
 * still there when the window comes back.
 *
 * Synchronised because [retain] is driven off the window list while [of] is called from a
 * composition, and the two are not the same thread in every frame.
 */
object AgentSessionStore {

    /**
     * One repo's sessions, and the project paths whose tabs lead to them.
     *
     * The paths are kept because they are what the window list talks in: [retain] is given the
     * projects that still have a tab somewhere, and a repo opened at a subdirectory has a project
     * path that is not its root.
     */
    private class Entry(val sessions: AgentSessions) {
        val projects: MutableSet<Path> = mutableSetOf()
    }

    private val byRoot = LinkedHashMap<Path, Entry>()

    /**
     * The sessions for the project rooted at [root], creating the collection the first time it is
     * asked for. [project] is the path of the tab asking — the tab and the root are the same
     * directory unless the project was opened at a subdirectory of its repository.
     */
    @Synchronized
    fun of(root: Path, project: Path): AgentSessions {
        val entry = byRoot.getOrPut(norm(root)) { Entry(AgentSessions()) }
        entry.projects.add(norm(project))
        return entry.sessions
    }

    /**
     * Kills the sessions of every project no window has a tab on any more.
     *
     * Driven off the window list rather than off a composition going away, which is the whole point:
     * the list still holds a parked window's tabs, so putting a window away leaves its agents
     * running, while closing the last tab on a project — a deliberate "I am done with this" — ends
     * them. A CLI still running against a checkout nothing on screen can reach is a process the user
     * has no way back to.
     */
    @Synchronized
    fun retain(projects: Collection<Path>) {
        val open = projects.map(::norm).toSet()
        val closed = byRoot.filterValues { entry -> entry.projects.none { it in open } }
        closed.forEach { (root, entry) ->
            Log.info("closing the agent sessions in $root: no window has a tab on it")
            entry.sessions.disposeAll()
            byRoot.remove(root)
        }
    }

    /**
     * Kills everything. nop exiting, and nothing else.
     *
     * A vendor CLI is a real OS process under a PTY, not a daemon thread: without this, quitting nop
     * would leave one agent per open tab running in the background with nothing left that could show
     * them or stop them.
     */
    @Synchronized
    fun disposeAll() {
        if (byRoot.isNotEmpty()) Log.info("closing the agent sessions in ${byRoot.size} project(s)")
        byRoot.values.forEach { it.sessions.disposeAll() }
        byRoot.clear()
    }

    private fun norm(path: Path): Path = path.toAbsolutePath().normalize()
}
