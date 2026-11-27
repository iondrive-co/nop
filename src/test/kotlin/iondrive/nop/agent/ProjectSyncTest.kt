package iondrive.nop.agent

import iondrive.nop.Settings
import iondrive.nop.agent.transcript.ClaudeTailer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Keeping a project's agent tabs on a remote: the lease that says which machine holds the project,
 * the copies each machine sends, and one machine taking the project from another.
 *
 * The remote is a folder under a temporary directory, reached through a stand-in for ssh that runs
 * the command it is given there, so the real `sh`, `flock` and `rsync` do the work.
 */
class ProjectSyncTest {

    private val seatA = "a1a1a1a1a1a1a1a1"
    private val seatB = "b2b2b2b2b2b2b2b2"
    private val key = SyncIdentity.projectKey("git@example.com:team/shop.git")

    private fun onPath(name: String): Boolean =
        System.getenv("PATH").orEmpty().split(':').any { it.isNotEmpty() && Files.isExecutable(Path.of(it, name)) }

    private fun needTools() {
        assumeTrue(onPath("rsync") && onPath("flock"), "rsync and flock are needed")
    }

    /** A remote whose home is `tmp/remote`, reached by a script that stands in for ssh. */
    private fun fakeRemote(tmp: Path): SyncRemote {
        val home = Files.createDirectories(tmp.resolve("remote"))
        val script = tmp.resolve("fake-ssh")
        Files.writeString(
            script,
            """
            #!/bin/sh
            while [ ${'$'}# -gt 0 ]; do
              case "${'$'}1" in
                -o|-i|-p|-l) shift 2 ;;
                --) shift; break ;;
                -*) shift ;;
                *) break ;;
              esac
            done
            shift
            cd '$home' || exit 255
            exec sh -c "${'$'}*"
            """.trimIndent() + "\n",
        )
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"))
        return SyncRemote(target = "dev@remote", ssh = script.toString())
    }

    private fun remoteCopy(tmp: Path, seat: String): Path =
        tmp.resolve("remote/${SyncRemote.DEFAULT_BASE}/projects/$key/machines/$seat")

    private fun write(file: Path, text: String): Path {
        Files.createDirectories(file.parent)
        Files.writeString(file, text)
        return file
    }

    private fun waitFor(what: String, seconds: Long = 20, check: () -> Boolean) {
        val until = System.nanoTime() + seconds * 1_000_000_000
        while (System.nanoTime() < until) {
            if (check()) return
            Thread.sleep(50)
        }
        throw AssertionError("timed out waiting for $what")
    }

    // ---- the remote ------------------------------------------------------------------------------

    @Test
    fun `a ping reaches the remote`(@TempDir tmp: Path) {
        needTools()
        val reply = SyncLink(fakeRemote(tmp), null).ping()
        assertTrue(reply.ok)
        assertNotNull(reply.now)
    }

    @Test
    fun `the first claim takes the project and a stale one is lost`(@TempDir tmp: Path) {
        needTools()
        val link = SyncLink(fakeRemote(tmp), null)
        assertNull(link.read(key).lease)

        val first = link.claim(key, 0, seatA, "alpha-box", "/home/dev/shop", "abc123", false)
        assertTrue(first.ok)
        assertEquals(1L, first.lease?.gen)
        assertEquals(seatA, first.lease?.seat)
        assertEquals("alpha-box", first.lease?.host)
        assertEquals("abc123", first.lease?.head)

        val stale = link.claim(key, 0, seatB, "bravo-box", "/home/dev/shop", "abc123", false)
        assertEquals("lost", stale.result)
        assertEquals(seatA, stale.lease?.seat)

        val taken = link.claim(key, 1, seatB, "bravo-box", "/home/dev/shop", "def456", true)
        assertTrue(taken.ok)
        assertEquals(2L, taken.lease?.gen)
        assertEquals(seatB, taken.lease?.seat)
        assertEquals(true, taken.lease?.dirty)
    }

    @Test
    fun `a check-in holds only for the holder at its generation`(@TempDir tmp: Path) {
        needTools()
        val link = SyncLink(fakeRemote(tmp), null)
        link.claim(key, 0, seatA, "alpha-box", "/home/dev/shop", "abc123", false)

        assertEquals("lost", link.beat(key, 1, seatB, "abc123", false).result)
        assertEquals("lost", link.beat(key, 2, seatA, "abc123", false).result)

        val beat = link.beat(key, 1, seatA, "def456", true)
        assertTrue(beat.ok)
        assertEquals(1L, beat.lease?.gen)
        assertEquals("def456", beat.lease?.head)
        assertEquals(true, beat.lease?.dirty)
        assertEquals("alpha-box", beat.lease?.host)
    }

    @Test
    fun `a read says when the holder has sent its tabs`(@TempDir tmp: Path) {
        needTools()
        val link = SyncLink(fakeRemote(tmp), null)
        link.claim(key, 0, seatA, "alpha-box", "/home/dev/shop", "abc123", false)
        assertFalse(link.read(key).pushed)

        val out = tmp.resolve("out")
        write(out.resolve("agents"), "tabs\n")
        link.push(key, seatA, out)
        assertTrue(link.read(key).pushed)
    }

    @Test
    fun `a copy carries plain files only, never a link or credentials`(@TempDir tmp: Path) {
        needTools()
        val link = SyncLink(fakeRemote(tmp), null)
        link.claim(key, 0, seatA, "alpha-box", "/home/dev/shop", "abc123", false)

        val elsewhere = write(tmp.resolve("private/notes.txt"), "not a conversation\n")
        val out = tmp.resolve("out")
        write(out.resolve("agents"), "tabs\n")
        write(out.resolve("logs/s1.jsonl"), "{\"type\":\"session_started\"}\n")
        Files.createSymbolicLink(out.resolve("logs/s2.jsonl"), elsewhere)
        Files.createSymbolicLink(out.resolve("handoffs"), elsewhere.parent)
        write(out.resolve(".credentials.json"), "{\"secret\":1}\n")
        write(out.resolve("native/s1/codex/auth.json"), "{\"secret\":2}\n")
        write(out.resolve("native/s1/codex/history.db-wal"), "journal\n")
        link.push(key, seatA, out)

        val there = remoteCopy(tmp, seatA)
        assertTrue(Files.isRegularFile(there.resolve("logs/s1.jsonl"), LinkOption.NOFOLLOW_LINKS))
        assertFalse(Files.exists(there.resolve("logs/s2.jsonl"), LinkOption.NOFOLLOW_LINKS))
        assertFalse(Files.exists(there.resolve("handoffs"), LinkOption.NOFOLLOW_LINKS))
        assertFalse(Files.exists(there.resolve(".credentials.json")))
        assertFalse(Files.exists(there.resolve("native/s1/codex/auth.json")))
        assertFalse(Files.exists(there.resolve("native/s1/codex/history.db-wal")))
        val open = setOf(
            PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE,
        )
        assertTrue(Files.getPosixFilePermissions(there.resolve("agents")).none { it in open })

        // A remote that has been tampered with: links where conversations are expected.
        Files.createSymbolicLink(there.resolve("logs/s3.jsonl"), elsewhere)
        Files.createSymbolicLink(there.resolve("native/s1/claude"), elsewhere.parent)
        val into = tmp.resolve("in")
        write(into.resolve("left-over"), "from another machine\n")
        link.pull(key, seatA, into)
        assertEquals("tabs\n", Files.readString(into.resolve("agents")))
        assertEquals("{\"type\":\"session_started\"}\n", Files.readString(into.resolve("logs/s1.jsonl")))
        assertFalse(Files.exists(into.resolve("logs/s3.jsonl"), LinkOption.NOFOLLOW_LINKS))
        assertFalse(Files.exists(into.resolve("native/s1/claude"), LinkOption.NOFOLLOW_LINKS))
        assertFalse(Files.exists(into.resolve(".credentials.json")))
        assertFalse(Files.exists(into.resolve("left-over")))
    }

    @Test
    fun `a copy is compressed and kept to the speed cap both ways, unless the cap is off`(@TempDir tmp: Path) {
        needTools()
        val inner = fakeRemote(tmp)
        val seen = tmp.resolve("seen")
        val script = tmp.resolve("noting-ssh")
        Files.writeString(script, "#!/bin/sh\necho \"${'$'}*\" >> '$seen'\nexec '${inner.ssh}' \"${'$'}@\"\n")
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"))
        val out = tmp.resolve("out")
        write(out.resolve("agents"), "tabs\n")

        fun copies(rateLimit: Int): List<String> {
            Files.deleteIfExists(seen)
            val link = SyncLink(inner.copy(ssh = script.toString(), rateLimit = rateLimit), null)
            link.claim(key, 0, seatA, "alpha-box", "/home/dev/shop", "abc123", false)
            link.push(key, seatA, out)
            link.pull(key, seatA, tmp.resolve("in"))
            return Files.readAllLines(seen).filter { "rsync --server" in it }
        }

        val capped = copies(250)
        assertEquals(2, capped.size, "$capped")
        assertTrue(capped.any { "--sender" in it }, "$capped")
        capped.forEach {
            assertTrue("--bwlimit=250" in it, it)
            assertTrue(Regex("""--server (--sender )?-[A-Za-z]*z[A-Za-z]*\.""").containsMatchIn(it), it)
        }
        copies(0).forEach { assertFalse("--bwlimit" in it, it) }
        assertEquals("tabs\n", Files.readString(tmp.resolve("in/agents")))
    }

    @Test
    fun `ssh is told to refuse a host it does not know, whatever its config says`(@TempDir tmp: Path) {
        val seen = tmp.resolve("seen")
        val script = tmp.resolve("noting-ssh")
        Files.writeString(script, "#!/bin/sh\necho \"${'$'}@\" >> '$seen'\ncat > /dev/null\necho result=ok\n")
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"))
        assertTrue(SyncLink(SyncRemote(target = "dev@remote", ssh = script.toString()), null).ping().ok)
        assertTrue("-o StrictHostKeyChecking=yes" in Files.readString(seen))
    }

    @Test
    fun `a program that prints without end is cut off`() {
        val ran = SyncLink.run(listOf("sh", "-c", "head -c 4000000 /dev/zero"), null, 20.seconds)
        assertEquals(-2, ran.exit)
        assertTrue(ran.out.length <= 256 * 1024)
    }

    @Test
    fun `a closed link runs nothing more`(@TempDir tmp: Path) {
        needTools()
        val link = SyncLink(fakeRemote(tmp), null)
        assertTrue(link.ping().ok)
        link.close()
        val refused = runCatching { link.ping() }.exceptionOrNull()
        assertTrue(refused is SyncFailure, "$refused")
    }

    @Test
    fun `a remote that cannot be used is refused before anything runs`(@TempDir tmp: Path) {
        assertNotNull(SyncLink.problem(SyncRemote(target = " ")))
        assertNotNull(SyncLink.problem(SyncRemote(target = "-oProxyCommand=x")))
        assertNotNull(SyncLink.problem(SyncRemote(target = "dev@remote box")))
        assertNotNull(SyncLink.problem(SyncRemote(target = "dev@remote", base = "/abs")))
        assertNotNull(SyncLink.problem(SyncRemote(target = "dev@remote", base = "a/../b")))
        assertNotNull(SyncLink.problem(SyncRemote(target = "dev@remote", ssh = "/usr/bin/s'sh")))
        assertNotNull(SyncLink.problem(SyncRemote(target = "dev@remote", identity = tmp.resolve("missing").toString())))
        val identity = write(tmp.resolve("id_test"), "key\n")
        assertNull(SyncLink.problem(SyncRemote(target = "dev@remote", identity = identity.toString())))
        assertNull(SyncLink.problem(SyncRemote(target = "dev@10.0.0.7", base = "sync/nop")))
        assertNotNull(SyncLink.problem(SyncRemote(target = "dev@remote", rateLimit = -1)))
        assertNull(SyncLink.problem(SyncRemote(target = "dev@remote", rateLimit = 0)))
    }

    // ---- identity --------------------------------------------------------------------------------

    @Test
    fun `every form of one origin is the same project`() {
        val forms = listOf(
            "git@Example.com:team/shop.git",
            "https://example.com/team/shop",
            "https://dev@example.com/team/shop.git/",
            "ssh://git@example.com:22/team/shop.git",
        )
        forms.forEach { assertEquals("example.com/team/shop", SyncIdentity.normalizeOrigin(it), it) }
        assertEquals(1, forms.map(SyncIdentity::projectKey).toSet().size)
        assertNotEquals(SyncIdentity.projectKey(forms[0]), SyncIdentity.projectKey("git@example.com:team/shed.git"))
        assertEquals("/home/dev/repos/shop", SyncIdentity.normalizeOrigin("/home/dev/repos/shop.git"))
    }

    @Test
    fun `another port on the same host is another project`() {
        assertEquals("example.com:8443/team/shop", SyncIdentity.normalizeOrigin("https://Example.com:8443/team/shop.git"))
        assertEquals("example.com/team/shop", SyncIdentity.normalizeOrigin("https://example.com:443/team/shop"))
        assertNotEquals(
            SyncIdentity.projectKey("git@example.com:team/shop.git"),
            SyncIdentity.projectKey("ssh://git@example.com:2222/team/shop.git"),
        )
    }

    @Test
    fun `an origin that is a path is a project on this machine only`(@TempDir tmp: Path) {
        val data = tmp.resolve("data")
        val one = Files.createDirectories(tmp.resolve("one/shop"))
        val two = Files.createDirectories(tmp.resolve("two/shop"))
        val bare = Files.createDirectories(tmp.resolve("bare/shop.git")).toString()
        assertTrue(SyncIdentity.isLocal("../shop.git"))
        assertTrue(SyncIdentity.isLocal("file://$bare"))
        assertFalse(SyncIdentity.isLocal("git@example.com:team/shop.git"))
        // The same relative origin in two checkouts is two repositories.
        assertNotEquals(SyncIdentity.projectKey("../origin.git", one, data), SyncIdentity.projectKey("../origin.git", two, data))
        // Two checkouts of one path on one machine are one project, and not the same one on another machine.
        assertEquals(SyncIdentity.projectKey(bare, one, data), SyncIdentity.projectKey(bare, two, data))
        assertNotEquals(SyncIdentity.projectKey(bare, one, data), SyncIdentity.projectKey(bare, one, tmp.resolve("other-data")))
    }

    @Test
    fun `a checkout keeps its seat and another checkout has its own`(@TempDir tmp: Path) {
        val data = tmp.resolve("data")
        val shop = tmp.resolve("work/shop")
        val seat = SyncIdentity.seat(shop, data, "system-one")
        assertTrue(Regex("[0-9a-f]{16}").matches(seat))
        assertEquals(seat, SyncIdentity.seat(shop, data, "system-one"))
        assertNotEquals(seat, SyncIdentity.seat(tmp.resolve("work/shed"), data, "system-one"))
        assertNotEquals(seat, SyncIdentity.seat(shop, data, "system-two"))
        assertNotEquals(seat, SyncIdentity.seat(shop, tmp.resolve("other-data"), "system-one"))
    }

    // ---- the files ---------------------------------------------------------------------------------

    private class Machine(base: Path) {
        val root: Path = Files.createDirectories(base.resolve("shop"))
        val data: Path = base.resolve("data")
        val claude = Account("claude-alpha", Provider.Anthropic, base.resolve("homes/claude-alpha").toString())
        val codex = Account("codex-bravo", Provider.OpenAI, base.resolve("homes/codex-bravo").toString())
        var accounts = listOf(claude)
        val files = SyncFiles(root, data, { accounts }, { _, _ -> null })

        fun transcript(id: String): Path =
            claude.homePath.resolve("projects").resolve(ClaudeTailer.slug(root)).resolve("$id.jsonl")

        fun log(sid: String): Path = data.resolve("sessions/$sid.jsonl")

        fun started(): String = "{\"type\":\"session_started\",\"projectPath\":\"$root\",\"at\":1}\n"
    }

    private fun tab(sid: String, account: String = "claude-alpha", provider: String = "anthropic", native: String = "c-$sid") =
        Settings.OpenAgent(sid, provider, account, native, "Fix it", false)

    private fun runStarted(provider: String, account: String, native: String, vararg argv: String): String {
        val words = argv.joinToString(",") { "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" }
        return "{\"type\":\"run_started\",\"provider\":\"$provider\",\"account\":\"$account\",\"nativeSessionId\":\"$native\",\"argv\":[$words],\"at\":2}\n"
    }

    @Test
    fun `staging copies the tabs, their logs and their conversations`(@TempDir tmp: Path) {
        val m = Machine(tmp.resolve("a"))
        Settings.saveOpenAgents(m.root, listOf(tab("s1", native = "c1"), tab("s2", account = "claude-gone", native = "c2")))
        write(m.log("s1"), "{\"type\":\"session_started\"}\n")
        write(m.log("s2"), "{\"type\":\"session_started\"}\n")
        write(m.transcript("c1"), "{\"type\":\"user\"}\n")
        write(m.transcript("c1").resolveSibling("c1/subagents/agent-1.jsonl"), "{}\n")
        write(m.transcript("c2"), "{\"type\":\"user\"}\n")

        val print = m.files.stage()
        val out = m.files.outDir
        assertTrue(Files.isRegularFile(out.resolve("agents"), LinkOption.NOFOLLOW_LINKS))
        assertTrue(Files.isRegularFile(out.resolve("logs/s1.jsonl"), LinkOption.NOFOLLOW_LINKS))
        assertEquals(Files.readString(m.log("s1")), Files.readString(out.resolve("logs/s1.jsonl")))
        assertEquals(Files.readString(m.transcript("c1")), Files.readString(out.resolve("native/s1/claude/c1.jsonl")))
        assertTrue(Files.isRegularFile(out.resolve("native/s1/claude/c1/subagents/agent-1.jsonl"), LinkOption.NOFOLLOW_LINKS))
        // A tab on an account this machine does not have still sends its log, but not a conversation it cannot find.
        assertTrue(Files.exists(out.resolve("logs/s2.jsonl")))
        assertFalse(Files.exists(out.resolve("native/s2")))

        assertEquals(print, m.files.stage())
        Files.writeString(m.log("s1"), "{\"type\":\"session_started\"}\n{\"type\":\"user_message\"}\n")
        Files.writeString(m.transcript("c1"), "{\"type\":\"user\"}\n{\"type\":\"assistant\"}\n")
        assertNotEquals(print, m.files.stage())
        assertEquals(Files.readString(m.log("s1")), Files.readString(out.resolve("logs/s1.jsonl")))
        assertEquals(Files.readString(m.transcript("c1")), Files.readString(out.resolve("native/s1/claude/c1.jsonl")))

        // A conversation replaced by a longer one under the same name is copied again, not added to.
        Files.writeString(m.transcript("c1"), "{\"type\":\"other\"}\n{\"type\":\"assistant\"}\n{\"type\":\"user\"}\n")
        m.files.stage()
        assertEquals(Files.readString(m.transcript("c1")), Files.readString(out.resolve("native/s1/claude/c1.jsonl")))

        // A closed tab's files leave with it.
        Settings.saveOpenAgents(m.root, listOf(tab("s2", account = "claude-gone", native = "c2")))
        m.files.stage()
        assertFalse(Files.exists(out.resolve("logs/s1.jsonl")))
        assertFalse(Files.exists(out.resolve("native")))
        assertTrue(Files.exists(out.resolve("logs/s2.jsonl")))
    }

    @Test
    fun `staging never follows a link out of a conversation`(@TempDir tmp: Path) {
        val m = Machine(tmp.resolve("a"))
        val secret = write(tmp.resolve("private/key.txt"), "kept here\n")
        Settings.saveOpenAgents(m.root, listOf(tab("s1", native = "c1")))
        write(m.log("s1"), "{\"type\":\"session_started\"}\n")
        write(m.transcript("c1"), "{\"type\":\"user\"}\n")
        write(m.data.resolve("handoffs/s1/handoff-1.md"), "summary\n")
        Files.createSymbolicLink(m.data.resolve("handoffs/s1/handoff-2.md"), secret)
        Files.createDirectories(m.transcript("c1").resolveSibling("c1"))
        Files.createSymbolicLink(m.transcript("c1").resolveSibling("c1/more"), secret.parent)
        Files.createSymbolicLink(m.data.resolve("inboxes").also { Files.createDirectories(it) }.resolve("s1.json"), secret)

        m.files.stage()
        val staged = Files.walk(m.files.outDir).use { all -> all.filter { !Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }.toList() }
        assertTrue(staged.all { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) })
        assertEquals(
            setOf("agents", "logs/s1.jsonl", "handoffs/s1/handoff-1.md", "native/s1/claude/c1.jsonl"),
            staged.map { m.files.outDir.relativize(it).toString() }.toSet(),
        )
        assertTrue(staged.none { Files.readString(it) == Files.readString(secret) })
    }

    @Test
    fun `a staged log has no MCP server settings in its launch commands`(@TempDir tmp: Path) {
        val m = Machine(tmp.resolve("a"))
        Settings.saveOpenAgents(m.root, listOf(tab("s1", account = "codex-bravo", provider = "openai", native = "r1")))
        val server = "mcp_servers.tracker={url=\"https://tracker.example/mcp\", http_headers={Authorization=\"Bearer tr-9f31\"}, default_tools_approval_mode=\"approve\"}"
        val kept = "mcp_servers.ledger.default_tools_approval_mode=\"approve\""
        val before = m.started()
        val after = "{\"type\":\"user_message\",\"text\":\"mcp_servers. are mentioned here\",\"at\":3}\n"
        write(m.log("s1"), before + runStarted("openai", "codex-bravo", "r1", "codex", "-c", server, "-c", kept, "resume", "r1") + after)

        m.files.stage()
        val sent = Files.readString(m.files.outDir.resolve("logs/s1.jsonl"))
        assertFalse("tr-9f31" in sent)
        assertFalse("tracker.example" in sent)
        assertTrue(sent.startsWith(before))
        assertTrue(sent.endsWith(after))
        val run = EventLog.read(m.files.outDir.resolve("logs/s1.jsonl")).filterIsInstance<AgentEvent.RunStarted>().single()
        assertEquals(listOf("codex", "-c", "mcp_servers.tracker=\"<redacted>\"", "-c", kept, "resume", "r1"), run.argv)

        // Lines added later are redacted as they are added, and a line still being written waits.
        Files.writeString(m.log("s1"), Files.readString(m.log("s1")) + runStarted("openai", "codex-bravo", "r1", "codex", "-c", server) + "{\"type\":\"user_mes")
        m.files.stage()
        val grown = Files.readString(m.files.outDir.resolve("logs/s1.jsonl"))
        assertFalse("tr-9f31" in grown)
        assertTrue(grown.startsWith(sent))
        assertEquals(2, EventLog.read(m.files.outDir.resolve("logs/s1.jsonl")).filterIsInstance<AgentEvent.RunStarted>().size)
        assertTrue(grown.endsWith("]}\n") || grown.endsWith("}\n"))
        assertFalse("user_mes" in grown.removePrefix(sent))
    }

    @Test
    fun `a new launch is logged without its MCP server settings`() {
        val server = "mcp_servers.tracker={command=\"tracker-mcp\", env={TRACKER_TOKEN=\"tr-9f31\"}}"
        val kept = "mcp_servers.ledger.default_tools_approval_mode=\"approve\""
        assertEquals(
            listOf("codex", "-c", "mcp_servers.tracker=\"<redacted>\"", "-c", kept, "-m", "gpt-next", "resume", "r1"),
            McpServers.redactArgv(listOf("codex", "-c", server, "-c", kept, "-m", "gpt-next", "resume", "r1")),
        )
    }

    /** What another machine sent, laid out in [m]'s incoming folder as a fetch leaves it. */
    private fun fetched(tmp: Path, m: Machine, tabs: List<Settings.OpenAgent>) {
        val agents = tmp.resolve("sent-agents-${System.nanoTime()}")
        writeTabs(agents, tabs)
        Files.createDirectories(m.files.inDir)
        Files.copy(agents, m.files.inDir.resolve("agents"), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }

    @Test
    fun `placing keeps each tab on its own account and keeps what it replaces`(@TempDir tmp: Path) {
        val m = Machine(tmp.resolve("b"))
        val inDir = m.files.inDir
        fetched(tmp, m, listOf(
            tab("s1", native = "c1"),
            tab("s2", account = "claude-gamma", native = "c2"),
            tab("s3", account = "codex-bravo", provider = "openai", native = "r3"),
        ))
        write(inDir.resolve("logs/s1.jsonl"), "{\"type\":\"session_started\"}\n{\"type\":\"user_message\"}\n")
        write(inDir.resolve("logs/s2.jsonl"), "{\"type\":\"session_started\"}\n")
        write(inDir.resolve("logs/s3.jsonl"), "{\"type\":\"session_started\"}\n")
        write(inDir.resolve("native/s1/claude/c1.jsonl"), "{\"type\":\"user\"}\n")
        write(inDir.resolve("native/s1/claude/.credentials.json"), "{\"secret\":1}\n")
        write(m.log("s1"), m.started())

        assertEquals(setOf("anthropic/claude-gamma", "openai/codex-bravo"), m.files.unmatchedAccounts().keys)
        assertEquals(listOf("claude-alpha"), m.files.accountsFor(tab("s2", account = "claude-gamma")))
        assertEquals(emptyList<String>(), m.files.accountsFor(tab("s3", account = "codex-bravo", provider = "openai")))

        val placed = m.files.place(now = Instant.parse("2026-01-02T03:04:05Z"))
        assertEquals(1, placed.rows)
        assertEquals(2, placed.dropped)
        assertEquals(0, placed.failed)
        assertEquals(2, placed.reasons.size)

        val tabs = Settings.loadOpenAgents(m.root)
        assertEquals(listOf("s1"), tabs.map { it.sessionId })
        assertEquals(Files.readString(inDir.resolve("logs/s1.jsonl")), Files.readString(m.log("s1")))
        assertFalse(Files.exists(m.log("s2")))
        assertFalse(Files.exists(m.log("s3")))
        assertEquals("{\"type\":\"user\"}\n", Files.readString(m.transcript("c1")))
        assertFalse(Files.exists(m.transcript("c1").resolveSibling(".credentials.json")))
        val aside = Files.walk(m.files.asideDir).use { s -> s.filter { Files.isRegularFile(it) }.toList() }
        assertEquals(listOf(m.started()), aside.map { Files.readString(it) })
    }

    @Test
    fun `a tab of an account this machine lacks runs only on the account chosen for it`(@TempDir tmp: Path) {
        val m = Machine(tmp.resolve("b"))
        fetched(tmp, m, listOf(tab("s2", account = "claude-gamma", native = "c2"), tab("s3", account = "codex-charlie", provider = "openai", native = "r3")))
        write(m.files.inDir.resolve("native/s2/claude/c2.jsonl"), "{\"type\":\"user\"}\n")

        // A choice of another kind of account is no choice.
        val placed = m.files.place(mapOf("anthropic/claude-gamma" to "claude-alpha", "openai/codex-charlie" to "claude-alpha"))
        assertEquals(1, placed.rows)
        assertEquals(1, placed.dropped)
        val tabs = Settings.loadOpenAgents(m.root)
        assertEquals(listOf("s2" to "claude-alpha"), tabs.map { it.sessionId to it.account })
        assertEquals("{\"type\":\"user\"}\n", Files.readString(m.transcript("c2")))
    }

    @Test
    fun `placing takes nothing that would have an agent act unasked`(@TempDir tmp: Path) {
        val m = Machine(tmp.resolve("b"))
        val inDir = m.files.inDir
        fetched(tmp, m, listOf(
            tab("s1", native = "c1"),
            tab("s4", native = "").copy(pendingStart = "Delete the release branch"),
            tab("s5", native = "--dangerous"),
            tab("s6", native = "c6"),
        ))
        write(inDir.resolve("logs/s6.jsonl"), runStarted("anthropic", "claude-alpha", "c6", "claude", "--resume", "--settings=/tmp/x"))
        val message = iondrive.nop.ipc.Handover.HeldRecord(
            "Planner", "s9", "/home/dev/shop", "Ship it", "[Message]\nShip it", 7, approved = true, id = "m1", attemptedAt = 9, attempts = 2,
        )
        AgentInboxStore.save("s1", listOf(message), inDir.resolve("inbox"))

        val placed = m.files.place()
        assertEquals(1, placed.rows)
        assertEquals(3, placed.dropped)
        val tabs = Settings.loadOpenAgents(m.root)
        assertEquals(listOf("s1"), tabs.map { it.sessionId })
        assertTrue(tabs.all { it.pendingStart == null })
        assertFalse(Files.exists(m.log("s6")))
        val held = AgentInboxStore.load("s1", m.data.resolve("inboxes"))!!
        assertEquals(listOf("Ship it"), held.map { it.body })
        assertTrue(held.none { it.approved })
        assertTrue(held.all { it.attempts == 0 })
    }

    @Test
    fun `placing takes only the files of a tab's own conversation`(@TempDir tmp: Path) {
        val m = Machine(tmp.resolve("b"))
        m.accounts = listOf(m.claude, m.codex)
        val inDir = m.files.inDir
        val sessions = m.codex.homePath.resolve(".codex/sessions")
        fetched(tmp, m, listOf(tab("s1", native = "c1"), tab("s3", account = "codex-bravo", provider = "openai", native = "r3")))
        write(inDir.resolve("native/s1/claude/c1.jsonl"), "{\"type\":\"user\"}\n")
        write(inDir.resolve("native/s1/claude/c1/tool-results/one.txt"), "result\n")
        write(inDir.resolve("native/s1/claude/c8.jsonl"), "{\"type\":\"planted\"}\n")
        write(inDir.resolve("native/s3/codex/2026/03/04/rollout-2026-03-04T05-06-07-r3.jsonl"), "{\"type\":\"turn\"}\n")
        write(inDir.resolve("native/s3/codex/2026/03/04/rollout-2026-03-04T05-06-07-r8.jsonl"), "{\"type\":\"planted\"}\n")
        write(inDir.resolve("native/s3/codex/history.jsonl"), "{\"type\":\"planted\"}\n")
        val others = write(sessions.resolve("2026/03/04/rollout-2026-03-04T01-01-01-r8.jsonl"), "{\"type\":\"another project\"}\n")

        val placed = m.files.place()
        assertEquals(2, placed.rows)
        assertEquals(2, placed.failed)
        assertEquals("{\"type\":\"user\"}\n", Files.readString(m.transcript("c1")))
        assertEquals("result\n", Files.readString(m.transcript("c1").resolveSibling("c1/tool-results/one.txt")))
        assertFalse(Files.exists(m.transcript("c8")))
        assertEquals("{\"type\":\"turn\"}\n", Files.readString(sessions.resolve("2026/03/04/rollout-2026-03-04T05-06-07-r3.jsonl")))
        assertEquals("{\"type\":\"another project\"}\n", Files.readString(others))
        assertFalse(Files.exists(sessions.resolve("history.jsonl")))
        assertFalse(Files.exists(sessions.resolve("2026/03/04/rollout-2026-03-04T05-06-07-r8.jsonl")))
    }

    @Test
    fun `placing never replaces a conversation or a tab that is not this project's`(@TempDir tmp: Path) {
        val m = Machine(tmp.resolve("b"))
        val inDir = m.files.inDir
        fetched(tmp, m, listOf(tab("s1", native = "c1"), tab("s7", native = "c7"), tab("s8", native = "c8")))
        // c1 is a conversation already here that this machine's record of the tab does not name.
        write(m.transcript("c1"), "{\"type\":\"mine\"}\n")
        write(inDir.resolve("native/s1/claude/c1.jsonl"), "{\"type\":\"theirs\"}\n{\"type\":\"more\"}\n")
        // s7 is a tab of another project on this machine.
        val foreign = "{\"type\":\"session_started\",\"projectPath\":\"/home/dev/shed\",\"at\":1}\n"
        write(m.log("s7"), foreign)
        write(inDir.resolve("logs/s7.jsonl"), "{\"type\":\"session_started\"}\n")
        // c8 is a conversation already here that only got longer there.
        write(m.transcript("c8"), "{\"type\":\"user\"}\n")
        write(inDir.resolve("native/s8/claude/c8.jsonl"), "{\"type\":\"user\"}\n{\"type\":\"assistant\"}\n")

        val placed = m.files.place()
        assertEquals(listOf("s1", "s8"), Settings.loadOpenAgents(m.root).map { it.sessionId })
        assertEquals(1, placed.dropped)
        assertEquals(1, placed.failed)
        assertEquals("{\"type\":\"mine\"}\n", Files.readString(m.transcript("c1")))
        assertEquals(foreign, Files.readString(m.log("s7")))
        assertEquals("{\"type\":\"user\"}\n{\"type\":\"assistant\"}\n", Files.readString(m.transcript("c8")))

        // Once a tab has been placed here it is this project's: the conversation its record names may be replaced.
        write(m.log("s1"), "{\"type\":\"session_started\",\"projectPath\":\"/home/other/shop\",\"at\":1}\n" + runStarted("anthropic", "claude-alpha", "c1", "claude", "--resume", "c1"))
        fetched(tmp, m, listOf(tab("s1", native = "c1")))
        write(inDir.resolve("logs/s1.jsonl"), Files.readString(m.log("s1")) + "{\"type\":\"user_message\"}\n")
        val again = m.files.place()
        assertEquals(1, again.rows)
        assertEquals(0, again.failed)
        assertEquals("{\"type\":\"theirs\"}\n{\"type\":\"more\"}\n", Files.readString(m.transcript("c1")))
    }

    @Test
    fun `a link the remote sent is removed before anything reads it`(@TempDir tmp: Path) {
        val m = Machine(tmp.resolve("b"))
        val elsewhere = tmp.resolve("sent-agents")
        writeTabs(elsewhere, listOf(tab("s1", native = "c1")))
        Files.createDirectories(m.files.inDir.resolve("logs"))
        Files.createSymbolicLink(m.files.inDir.resolve("agents"), elsewhere)
        Files.createSymbolicLink(m.files.inDir.resolve("logs/s1.jsonl"), elsewhere)
        Files.createSymbolicLink(m.files.inDir.resolve("native"), tmp)
        val print = m.files.pulledPrint()

        assertEquals(3, m.files.dropPulledLinks())
        assertTrue(m.files.viewedRows().isEmpty())
        assertNull(m.files.viewedLog("s1"))
        assertEquals(print, m.files.pulledPrint())
        assertTrue(Files.exists(elsewhere))
    }

    @Test
    fun `what was fetched from one remote is forgotten for another`(@TempDir tmp: Path) {
        val m = Machine(tmp.resolve("a"))
        val first = SyncFiles(m.root, m.data, { m.accounts }, { _, _ -> null }, remote = "dev@one\nnop-sync")
        first.cacheLease(SyncLease(3, seatB, "bravo-box", "/home/dev/shop", "abc123", false, 1_700_000_000))
        first.setPulledSeat(seatB)
        write(first.inDir.resolve("agents"), "tabs\n")
        assertNotNull(SyncFiles(m.root, m.data, { m.accounts }, { _, _ -> null }, remote = "dev@one\nnop-sync").cachedLease())

        val second = SyncFiles(m.root, m.data, { m.accounts }, { _, _ -> null }, remote = "dev@two\nnop-sync")
        assertNull(second.cachedLease())
        assertNull(second.pulledSeat())
        assertFalse(Files.exists(second.inDir.resolve("agents")))
    }

    @Test
    fun `the lease last seen is kept`(@TempDir tmp: Path) {
        val m = Machine(tmp.resolve("a"))
        assertNull(m.files.cachedLease())
        val lease = SyncLease(3, seatA, "alpha-box", "/home/dev/shop", "abc123", true, 1_700_000_000)
        m.files.cacheLease(lease)
        assertEquals(lease, m.files.cachedLease())
        assertNull(m.files.pulledSeat())
        m.files.setPulledSeat(seatB)
        assertEquals(seatB, m.files.pulledSeat())
    }

    private fun writeTabs(file: Path, tabs: List<Settings.OpenAgent>) {
        val scratch = file.resolveSibling(file.fileName.toString() + "-project")
        Files.createDirectories(scratch)
        Settings.saveOpenAgents(scratch, tabs)
        Files.copy(Settings.projectDataDir(scratch).resolve("agents"), file)
    }

    // ---- two machines ------------------------------------------------------------------------------

    @Test
    fun `a second machine views the first one's tabs until it takes them over`(@TempDir tmp: Path) {
        needTools()
        val remote = fakeRemote(tmp)
        val a = Machine(tmp.resolve("a"))
        val b = Machine(tmp.resolve("b"))
        Settings.saveOpenAgents(a.root, listOf(tab("s1", native = "c1")))
        write(a.log("s1"), "{\"type\":\"session_started\"}\n")
        write(a.transcript("c1"), "{\"type\":\"user\"}\n")

        val stopsA = AtomicInteger()
        val stopsB = AtomicInteger()
        fun sync(m: Machine, seat: String, host: String, stops: AtomicInteger) = ProjectSync(
            root = m.root, link = SyncLink(remote, null), key = key, seat = seat, host = host, files = m.files,
            target = remote.target, head = { "abc123" }, dirty = { false }, stopSessions = { stops.incrementAndGet() },
            post = { it() }, interval = 250.milliseconds,
        )
        val syncA = sync(a, seatA, "alpha-box", stopsA)
        val syncB = sync(b, seatB, "bravo-box", stopsB)
        try {
            syncA.start()
            waitFor("the first machine to send its tabs") {
                Files.isRegularFile(remoteCopy(tmp, seatA).resolve("native/s1/claude/c1.jsonl"))
            }
            assertTrue(syncA.owning)
            assertEquals(SyncRole.Owner, syncA.view.role)
            assertEquals(0, stopsA.get())

            syncB.start()
            waitFor("the second machine to show the first one's tabs") { syncB.view.rows.map { it.sessionId } == listOf("s1") }
            assertFalse(syncB.owning)
            assertEquals(SyncRole.Viewer, syncB.view.role)
            assertEquals("alpha-box", syncB.view.lease?.host)
            assertTrue(stopsB.get() >= 1)
            assertEquals(Files.readString(a.log("s1")), Files.readString(b.files.viewedLog("s1")!!))
            assertTrue(Settings.loadOpenAgents(b.root).isEmpty())

            Files.writeString(a.log("s1"), "{\"type\":\"session_started\"}\n{\"type\":\"user_message\"}\n")
            waitFor("the newer log to reach the second machine") {
                b.files.viewedLog("s1")?.let(Files::readString) == Files.readString(a.log("s1"))
            }

            runBlocking { syncB.takeOver().join() }
            assertTrue(syncB.owning)
            assertEquals(SyncRole.Owner, syncB.view.role)
            assertNull(syncB.view.error)
            assertEquals(seatB, syncB.view.lease?.seat)
            assertEquals(listOf("s1"), Settings.loadOpenAgents(b.root).map { it.sessionId })
            assertEquals(Files.readString(a.log("s1")), Files.readString(b.log("s1")))
            assertEquals("{\"type\":\"user\"}\n", Files.readString(b.transcript("c1")))

            waitFor("the first machine to learn it was taken over") { !syncA.owning && syncA.view.takenOverBy == "bravo-box" }
            assertEquals(SyncRole.Viewer, syncA.view.role)
            assertEquals(1, stopsA.get())
            waitFor("the first machine to show the second one's tabs") { syncA.view.rows.map { it.sessionId } == listOf("s1") }
            assertEquals(seatB, a.files.pulledSeat())
            assertTrue(syncB.owning)
        } finally {
            syncA.finish(Instant.now().plusSeconds(10))
            syncB.finish(Instant.now().plusSeconds(10))
        }
    }

    @Test
    fun `a sync that is abandoned sends nothing more`(@TempDir tmp: Path) {
        needTools()
        val remote = fakeRemote(tmp)
        val a = Machine(tmp.resolve("a"))
        Settings.saveOpenAgents(a.root, listOf(tab("s1", native = "c1")))
        write(a.log("s1"), "{\"type\":\"session_started\"}\n")
        val sync = ProjectSync(
            root = a.root, link = SyncLink(remote, null), key = key, seat = seatA, host = "alpha-box", files = a.files,
            target = remote.target, head = { "abc123" }, dirty = { false }, stopSessions = {},
            post = { it() }, interval = 250.milliseconds,
        )
        val sent = remoteCopy(tmp, seatA).resolve("logs/s1.jsonl")
        sync.start()
        waitFor("the machine to send its tabs") { Files.isRegularFile(sent) }
        val before = Files.readString(sent)

        sync.abandon()
        Files.writeString(a.log("s1"), before + "{\"type\":\"user_message\"}\n")
        Thread.sleep(1_000)
        sync.finish(Instant.now().plusSeconds(5))
        assertEquals(before, Files.readString(sent))
    }
}
