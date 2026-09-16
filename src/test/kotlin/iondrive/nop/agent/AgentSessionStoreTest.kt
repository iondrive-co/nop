package iondrive.nop.agent

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * What a project's agent sessions outlive, and what they don't.
 *
 * No session is opened here: the store's whole job is who holds the collection and when it is let
 * go, and asking twice for the same project is the thing that used to lose a running CLI.
 */
class AgentSessionStoreTest {

    @AfterEach
    fun cleanUp() {
        AgentSessionStore.disposeAll()
    }

    /**
     * The bug this exists for. Looking at another project, or another window, tears the composition
     * down and builds it again — and the second look has to find the same sessions, not new ones.
     */
    @Test
    fun `asking twice for one project gives the same sessions back`(@TempDir tmp: Path) {
        val project = tmp.resolve("repo")

        val first = AgentSessionStore.of(root = project, project = project)
        val second = AgentSessionStore.of(root = project, project = project)

        assertSame(first, second)
    }

    @Test
    fun `two projects keep their own sessions`(@TempDir tmp: Path) {
        val one = AgentSessionStore.of(root = tmp.resolve("one"), project = tmp.resolve("one"))
        val two = AgentSessionStore.of(root = tmp.resolve("two"), project = tmp.resolve("two"))

        assertNotSame(one, two)
    }

    /** The same repo reached by two spellings of its path is one project, not two. */
    @Test
    fun `a path is normalised before it names anything`(@TempDir tmp: Path) {
        val direct = AgentSessionStore.of(root = tmp.resolve("repo"), project = tmp.resolve("repo"))
        val roundabout = tmp.resolve("repo/module/..")

        assertSame(direct, AgentSessionStore.of(root = roundabout, project = roundabout))
    }

    /**
     * Parking a window keeps its tabs, and [Workspaces][iondrive.nop.Workspaces] counts them — so an
     * agent carries on working while the window it was started in is put away.
     */
    @Test
    fun `a project that still has a tab somewhere keeps its sessions`(@TempDir tmp: Path) {
        val project = tmp.resolve("repo")
        val sessions = AgentSessionStore.of(root = project, project = project)

        AgentSessionStore.retain(listOf(project, tmp.resolve("elsewhere")))

        assertSame(sessions, AgentSessionStore.of(root = project, project = project))
    }

    /** Closing the last tab on a project is the user saying they are done with it. */
    @Test
    fun `a project no window has a tab on loses its sessions`(@TempDir tmp: Path) {
        val project = tmp.resolve("repo")
        val sessions = AgentSessionStore.of(root = project, project = project)

        AgentSessionStore.retain(listOf(tmp.resolve("elsewhere")))

        assertNotSame(
            sessions,
            AgentSessionStore.of(root = project, project = project),
            "the closed project's collection should have been let go, not handed out again",
        )
    }

    /**
     * A project opened at a subdirectory of its repository is filed under the repo root, while the
     * window list only ever talks about the tab's own path — so the two have to be matched up, or
     * the first tick of the window list would kill the sessions of a project that is plainly open.
     */
    @Test
    fun `a project opened below its repo root is kept by its own path`(@TempDir tmp: Path) {
        val root = tmp.resolve("repo")
        val module = tmp.resolve("repo/module")
        val sessions = AgentSessionStore.of(root = root, project = module)

        AgentSessionStore.retain(listOf(module))

        assertSame(sessions, AgentSessionStore.of(root = root, project = module))
    }

    /** Two tabs on one repo are two ways to the same agents; closing one is not closing them. */
    @Test
    fun `sessions survive while any tab on the repo is open`(@TempDir tmp: Path) {
        val root = tmp.resolve("repo")
        val module = tmp.resolve("repo/module")
        val sessions = AgentSessionStore.of(root = root, project = root)
        assertSame(sessions, AgentSessionStore.of(root = root, project = module))

        AgentSessionStore.retain(listOf(module))

        assertSame(sessions, AgentSessionStore.of(root = root, project = module))
    }

    @Test
    fun `an empty window list lets everything go`(@TempDir tmp: Path) {
        val project = tmp.resolve("repo")
        val sessions = AgentSessionStore.of(root = project, project = project)

        AgentSessionStore.retain(emptyList())

        assertNotSame(sessions, AgentSessionStore.of(root = project, project = project))
    }

    /** Quitting nop is the one thing that ends every session everywhere. */
    @Test
    fun `disposeAll lets go of every project`(@TempDir tmp: Path) {
        val one = AgentSessionStore.of(root = tmp.resolve("one"), project = tmp.resolve("one"))
        val two = AgentSessionStore.of(root = tmp.resolve("two"), project = tmp.resolve("two"))

        AgentSessionStore.disposeAll()

        assertTrue(one !== AgentSessionStore.of(root = tmp.resolve("one"), project = tmp.resolve("one")))
        assertTrue(two !== AgentSessionStore.of(root = tmp.resolve("two"), project = tmp.resolve("two")))
    }
}
