package iondrive.nop.agent

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit

/**
 * The socket the agents message each other through, and `nop-msg`, the helper they use it with —
 * down to running the real helper against the real socket, the way an agent in a tab would.
 */
class AgentSocketTest {
    private val opened = mutableListOf<AgentSessions>()

    @AfterEach
    fun cleanUp() {
        AgentMessages.sessions = { AgentSessionStore.all() }
        opened.forEach { state ->
            state.sessions.forEach { runCatching { Files.deleteIfExists(it.log.file) } }
            state.disposeAll()
        }
    }

    private fun tabs(dir: Path, vararg titles: String): List<AgentSession> {
        val state = AgentSessions().also { opened += it }
        val made = titles.map { title ->
            state.open(dir.toFile(), Account("acct-$title", Provider.Antigravity, "/homes/acct-$title")).also { it.rename(title) }
        }
        AgentMessages.sessions = { state.sessions }
        return made
    }

    private class Recorder : AgentSocket.Tools {
        val sent = mutableListOf<Pair<String, String>>()
        override fun list(caller: AgentSession) = AgentMessages.Outcome("the list")
        override fun send(caller: AgentSession, to: String, message: String): AgentMessages.Outcome {
            sent += to to message
            return AgentMessages.Outcome("queued")
        }
        val memory = mutableListOf<Triple<Boolean, SharedMemory.Scope, String>>()
        override fun memory(caller: AgentSession, forget: Boolean, scope: SharedMemory.Scope, text: String): AgentMessages.Outcome {
            memory += Triple(forget, scope, text)
            return AgentMessages.Outcome("remembered")
        }
    }

    @Test
    fun `remember has to say who the entry is for`(@TempDir dir: Path) {
        val (tab) = tabs(dir, "a")
        val tools = Recorder()
        assertTrue(AgentSocket.handle("${tab.agentTicket}\nremember\nproject\nbuild with -x\n", { tab }, tools).first)
        assertTrue(AgentSocket.handle("${tab.agentTicket}\nremember\nall user\nlikes scripts\n", { tab }, tools).first)
        assertTrue(AgentSocket.handle("${tab.agentTicket}\nforget\nall software\nold line", { tab }, tools).first)
        assertEquals(
            listOf(
                Triple(false, SharedMemory.Scope.Project, "build with -x\n"),
                Triple(false, SharedMemory.Scope.All(SharedMemory.Category.User), "likes scripts\n"),
                Triple(true, SharedMemory.Scope.All(SharedMemory.Category.Software), "old line"),
            ),
            tools.memory,
        )
        assertFalse(AgentSocket.handle("${tab.agentTicket}\nremember\nall\nno category\n", { tab }, tools).first)
        assertFalse(AgentSocket.handle("${tab.agentTicket}\nremember\nall misc\nwrong category\n", { tab }, tools).first)
        assertFalse(AgentSocket.handle("${tab.agentTicket}\nremember\nsomewhere\nno scope\n", { tab }, tools).first)
    }

    @Test
    fun `a request without a live tab's ticket is refused`(@TempDir dir: Path) {
        val (tab) = tabs(dir, "a")
        val identify = { t: String -> tab.takeIf { t == tab.agentTicket } }
        val (ok, text) = AgentSocket.handle("guess\nlist\n", identify, Recorder())
        assertFalse(ok)
        assertTrue("inside one of nop's agent tabs" in text, text)
        assertFalse(AgentSocket.handle("\nlist\n", identify, Recorder()).first)
    }

    @Test
    fun `send carries the whole message, however many lines it has`(@TempDir dir: Path) {
        val (tab) = tabs(dir, "a")
        val tools = Recorder()
        val (ok, _) = AgentSocket.handle("${tab.agentTicket}\nsend\nKafka shadow\nline one\nline two\n", { tab }, tools)
        assertTrue(ok)
        assertEquals(listOf("Kafka shadow" to "line one\nline two\n"), tools.sent)
    }

    @Test
    fun `an unknown request is an error`(@TempDir dir: Path) {
        val (tab) = tabs(dir, "a")
        assertFalse(AgentSocket.handle("${tab.agentTicket}\nrm -rf\n", { tab }, Recorder()).first)
    }

    @Test
    fun `only a real directory nobody else can enter counts as private`(@TempDir tmp: Path) {
        val closed = Files.createDirectory(tmp.resolve("closed"), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        val open = Files.createDirectory(tmp.resolve("open"))
        Files.setPosixFilePermissions(open, PosixFilePermissions.fromString("rwxr-xr-x"))
        val link = Files.createSymbolicLink(tmp.resolve("link"), closed)
        assertTrue(AgentSocket.isPrivate(closed))
        assertFalse(AgentSocket.isPrivate(open))
        assertFalse(AgentSocket.isPrivate(link), "a link could be pointed somewhere else later")
    }

    @Test
    fun `the helper is valid sh`(@TempDir tmp: Path) {
        val script = Files.writeString(tmp.resolve("nop-msg"), AgentSocket.HELPER)
        val check = ProcessBuilder("sh", "-n", script.toString()).redirectErrorStream(true).start()
        val out = check.inputStream.readAllBytes().toString(Charsets.UTF_8)
        assertTrue(check.waitFor(10, TimeUnit.SECONDS))
        assertEquals(0, check.exitValue(), out)
    }

    @Test
    fun `the real helper reaches the real socket from inside a tab`(@TempDir dir: Path) {
        assumeTrue(File("/usr/bin/python3").canExecute() || File("/bin/python3").canExecute(), "the helper needs python3 here")
        val (me, other) = tabs(dir, "me", "other")
        val env = AgentSocket.runEnv(me.agentTicket)
        assumeTrue(env.isNotEmpty(), "the socket could not be opened on this machine")

        fun helper(ticket: String, vararg args: String, stdin: String = ""): Pair<Int, String> {
            val pb = ProcessBuilder(listOf("sh", AgentSocket.helperPath().toString()) + args).redirectErrorStream(true)
            pb.environment().putAll(env)
            pb.environment()[AgentSocket.TICKET_ENV] = ticket
            val p = pb.start()
            p.outputStream.use { it.write(stdin.toByteArray()) }
            val out = p.inputStream.readAllBytes().toString(Charsets.UTF_8)
            assertTrue(p.waitFor(20, TimeUnit.SECONDS))
            return p.exitValue() to out
        }

        val (listed, list) = helper(me.agentTicket, "list")
        assertEquals(0, listed, list)
        assertTrue(list.lines().single { me.shortId in it }.endsWith("(you)"), list)
        assertTrue(list.lines().any { other.shortId in it }, list)

        val (sent, reply) = helper(me.agentTicket, "send", other.shortId, "hello", "there")
        assertEquals(0, sent, reply)
        assertTrue(reply.startsWith("Held for the user"), reply)

        val (piped, pipedReply) = helper(me.agentTicket, "send", "other", stdin = "first line\nsecond line\n")
        assertEquals(0, piped, pipedReply)
        assertEquals(listOf("hello there", "first line\nsecond line"), other.inbox.map { it.body })
        assertTrue(other.inbox.none { it.approved }, "nothing goes in without the user")

        val (refused, why) = helper("not-a-ticket", "list")
        assertEquals(1, refused)
        assertTrue("inside one of nop's agent tabs" in why, why)
    }
}
