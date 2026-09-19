package iondrive.nop.ui

import iondrive.nop.Log
import java.nio.file.Path

/**
 * Every project's terminals, held for as long as nop is running: the shells behind the Term tabs and
 * the launcher runs behind the Run tab.
 *
 * They used to be owned by the project's composition. nop composes one project tab at a time, so
 * switching to another project killed every shell and run in the one left behind, along with its
 * scrollback and whatever it was running. Coming back opened a fresh Term. These are the same terms
 * as [AgentSessionStore][iondrive.nop.agent.AgentSessionStore], for the same reason: a terminal is a
 * live process the user has not closed.
 *
 * Filed under the repo root, like the agent sessions and like the run tabs' state file
 * (`Settings.loadOpenRuns`). Two tabs on one repo therefore share their terminals. If both are on
 * screen at once, a terminal's widget follows whichever window looked at it last, because a Swing
 * component has one parent.
 *
 * What kills them: closing their own tab, closing the last project tab that leads here ([retain]),
 * or nop exiting ([disposeAll]). A parked window keeps its tabs, so its terminals keep running.
 */
object TerminalStore {

    /** One repo's terminals: the shells behind the Term tabs, and the launcher runs behind Run. */
    class Terminals(val shells: RunSessions, val runs: RunSessions) {
        /**
         * Which collection the session pane was showing when the project was last looked at: Term,
         * Run, Agent, or null for the agent picker. Without it, coming back to the project showed
         * the picker, and a Term left running looked as though it had been lost.
         */
        var sessionTab: ToolTab? = null

        fun disposeAll() {
            shells.disposeAll()
            runs.disposeAll()
        }
    }

    private class Entry(val terminals: Terminals) {
        /** The project paths whose tabs lead here, which is what [retain] is told about. */
        val projects: MutableSet<Path> = mutableSetOf()
    }

    private val byRoot = LinkedHashMap<Path, Entry>()

    /**
     * The terminals for the project rooted at [root], built by [create] the first time it is asked
     * for. [project] is the path of the tab asking, which differs from [root] only for a project
     * opened at a subdirectory of its repository.
     */
    @Synchronized
    fun of(root: Path, project: Path, create: () -> Terminals): Terminals {
        val entry = byRoot.getOrPut(norm(root)) { Entry(create()) }
        entry.projects.add(norm(project))
        return entry.terminals
    }

    /** Kills the terminals of every project no window has a tab on any more. */
    @Synchronized
    fun retain(projects: Collection<Path>) {
        val open = projects.map(::norm).toSet()
        val closed = byRoot.filterValues { entry -> entry.projects.none { it in open } }
        closed.forEach { (root, entry) ->
            Log.info("closing the terminals in $root: no window has a tab on it")
            entry.terminals.disposeAll()
            byRoot.remove(root)
        }
    }

    /** Kills everything. nop exiting, and nothing else. */
    @Synchronized
    fun disposeAll() {
        byRoot.values.forEach { it.terminals.disposeAll() }
        byRoot.clear()
    }

    private fun norm(path: Path): Path = path.toAbsolutePath().normalize()
}
