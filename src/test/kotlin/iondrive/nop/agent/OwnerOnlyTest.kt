package iondrive.nop.agent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

/**
 * Everything the launcher writes should be readable only by the person who owns it.
 *
 * Not a nicety. A session log carries the prompts typed into it, the model's replies, the commands
 * it ran and slices of the files it read; a handoff summary is that material condensed; an account
 * home is where a vendor CLI keeps its OAuth token. Under the default umask all of that lands
 * `-rw-rw-r--`, which is a weaker posture than the vendors take with their own credentials — and
 * one nop would have chosen on the user's behalf without saying so.
 */
@DisabledOnOs(OS.WINDOWS)
class OwnerOnlyTest {

    private fun modeOf(path: Path): String =
        PosixFilePermissions.toString(Files.getPosixFilePermissions(path))

    @Test
    fun `a created file is readable only by its owner`(@TempDir tmp: Path) {
        val file = OwnerOnly.file(tmp.resolve("log.jsonl"))

        assertEquals("rw-------", modeOf(file))
    }

    @Test
    fun `a created directory is reachable only by its owner`(@TempDir tmp: Path) {
        val dir = OwnerOnly.directory(tmp.resolve("agent"))

        assertEquals("rwx------", modeOf(dir))
    }

    @Test
    fun `every level it has to create is owner-only, not just the last`(@TempDir tmp: Path) {
        val deep = OwnerOnly.directory(tmp.resolve("nop/agent/sessions"))

        assertEquals("rwx------", modeOf(deep))
        assertEquals("rwx------", modeOf(deep.parent), "the middle level was left to the umask")
        assertEquals("rwx------", modeOf(deep.parent.parent))
    }

    /**
     * A file that is created and then chmodded is world-readable in between, which is the whole
     * window an attacker needs. It has to be born with the right mode.
     */
    @Test
    fun `a file is never briefly world-readable on the way to being created`(@TempDir tmp: Path) {
        val file = tmp.resolve("secrets.jsonl")

        OwnerOnly.file(file)

        val permissions = Files.getPosixFilePermissions(file)
        assertTrue(PosixFilePermission.OTHERS_READ !in permissions)
        assertTrue(PosixFilePermission.GROUP_READ !in permissions)
    }

    @Test
    fun `an existing file is left alone, so a second open does not churn it`(@TempDir tmp: Path) {
        val file = OwnerOnly.file(tmp.resolve("log.jsonl"))
        Files.writeString(file, "already here")

        OwnerOnly.file(file)

        assertEquals("already here", Files.readString(file))
    }

    /**
     * An account home may be somewhere the user chose — a directory another tool owns and shares on
     * purpose. Quietly changing the mode of a path nop did not create is not nop's call.
     */
    @Test
    fun `a directory that already exists keeps the permissions it had`(@TempDir tmp: Path) {
        val theirs = tmp.resolve("theirs")
        Files.createDirectory(theirs)
        Files.setPosixFilePermissions(theirs, PosixFilePermissions.fromString("rwxr-xr-x"))

        OwnerOnly.directory(theirs)

        assertEquals("rwxr-xr-x", modeOf(theirs))
    }

    @Test
    fun `tighten narrows a file an atomic rename brought in`(@TempDir tmp: Path) {
        val file = tmp.resolve("agent.json")
        Files.writeString(file, "{}")
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-rw-r--"))

        OwnerOnly.tighten(file)

        assertEquals("rw-------", modeOf(file))
    }

    // -- The files the feature actually writes --

    @Test
    fun `a session log is owner-only`(@TempDir tmp: Path) {
        withDataHome(tmp) {
            val log = EventLog.open("perm-check-${System.nanoTime()}")
            try {
                log.append(AgentEvent.UserMessage("something private", System.currentTimeMillis()))
                assertEquals("rw-------", modeOf(log.file))
                assertEquals("rwx------", modeOf(log.file.parent))
            } finally {
                log.close()
                Files.deleteIfExists(log.file)
            }
        }
    }

    @Test
    fun `a handoff summary is owner-only`(@TempDir tmp: Path) {
        withDataHome(tmp) {
            val written = Handoff.write(
                "perm-check-${System.nanoTime()}",
                listOf(AgentEvent.UserMessage("the task", System.currentTimeMillis())),
                Provider.OpenAI,
            )
            assertEquals("rw-------", modeOf(written.path))
            assertEquals("rwx------", modeOf(written.path.parent))
        }
    }

    @Test
    fun `an account home created for a login is owner-only`(@TempDir tmp: Path) {
        val account = Account("new", Provider.Anthropic, tmp.resolve("homes/new").toString())

        Login.session(account).dispose()

        assertEquals("rwx------", modeOf(account.credentialFile.parent))
    }

    /** Runs [body] with `XDG_DATA_HOME` pointed at [dir], so nothing lands in the real one. */
    private fun withDataHome(dir: Path, body: () -> Unit) {
        val original = System.getProperty("user.home")
        System.setProperty("user.home", dir.toString())
        try {
            body()
        } finally {
            System.setProperty("user.home", original)
        }
    }
}
