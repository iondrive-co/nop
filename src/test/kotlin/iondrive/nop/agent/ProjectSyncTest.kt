package iondrive.nop.agent

import iondrive.nop.Settings
import iondrive.nop.agent.transcript.ClaudeTailer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds

/**
 * Keeping a project's agent tabs in an S3-compatible store: the lease that says which machine holds
 * the project, the copies each machine sends, and one machine taking the project from another.
 *
 * The store is [FakeS3], an in-memory stand-in served on 127.0.0.1, so the real HTTP requests,
 * conditional writes, sealing and chunking all run.
 */
class ProjectSyncTest {

    private val seatA = "a1a1a1a1a1a1a1a1"
    private val seatB = "b2b2b2b2b2b2b2b2"
    private val key = SyncIdentity.projectKey("git@example.com:team/shop.git")
    private val syncKey = SyncCrypto.generate()
    private val crypto = SyncCrypto.parse(syncKey)!!

    private fun remote(store: FakeS3, sync: String = syncKey) = SyncRemote(
        endpoint = store.endpoint, bucket = FakeS3.BUCKET, accessKey = ACCESS_KEY, secretKey = SECRET_KEY,
        syncKey = sync, rateLimit = 0,
    )

    private fun machine(seat: String) = "projects/$key/machines/$seat"

    /** The plain bytes of the object at [rel] below the folder, or null when there is none. */
    private fun open(store: FakeS3, rel: String, limit: Int): ByteArray? =
        store.body("nop-sync/$rel")?.let { SyncCrypto.unpack(crypto.open(it, rel), limit) }

    /** The list of files [seat] last sent, or null when it sent none. */
    private fun manifest(store: FakeS3, seat: String): JsonObject? =
        open(store, "${machine(seat)}/manifest", 64 shl 20)
            ?.let { Json.parseToJsonElement(it.toString(Charsets.UTF_8)).jsonObject }

    /** What [seat] sent as [path], put back together from its chunks, or null when it sent no such file. */
    private fun sent(store: FakeS3, seat: String, path: String): String? {
        val files = manifest(store, seat)?.get("files")?.jsonArray ?: return null
        val entry = files.map { it.jsonObject }.firstOrNull { it["path"]?.jsonPrimitive?.content == path } ?: return null
        val out = ByteArrayOutputStream()
        for (chunk in entry["chunks"]!!.jsonArray) {
            val rel = "${machine(seat)}/blobs/${chunk.jsonPrimitive.content}"
            out.write(open(store, rel, SyncLink.CHUNK) ?: throw AssertionError("$rel is missing"))
        }
        return out.toString(Charsets.UTF_8)
    }

    /** The chunks the store holds for [seat]. */
    private fun blobs(store: FakeS3, seat: String): Set<String> =
        store.keys().filter { it.startsWith("nop-sync/${machine(seat)}/blobs/") }.mapTo(HashSet()) { it.substringAfterLast('/') }

    /** The chunks [seat]'s list names. */
    private fun named(store: FakeS3, seat: String): Set<String> =
        manifest(store, seat)!!["files"]!!.jsonArray.flatMapTo(HashSet()) { file ->
            file.jsonObject["chunks"]!!.jsonArray.map { it.jsonPrimitive.content }
        }

    /** Puts [manifest] in the store as [seat]'s list, sealed as [with] would. */
    private fun forge(store: FakeS3, seat: String, manifest: String, with: SyncCrypto = crypto) {
        val rel = "${machine(seat)}/manifest"
        store.replace("nop-sync/$rel", with.seal(SyncCrypto.pack(manifest.toByteArray()), rel))
    }

    /** The regular files under [dir], by their path below it. */
    private fun files(dir: Path): List<String> =
        if (!Files.isDirectory(dir)) emptyList()
        else Files.walk(dir).use { paths ->
            paths.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }.map { dir.relativize(it).joinToString("/") }.sorted().toList()
        }

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

    // ---- the store -------------------------------------------------------------------------------

    @Test
    fun `a store that keeps its promises answers a ping with its clock`() {
        FakeS3().use { store ->
            SyncLink(remote(store)).use { link ->
                val reply = link.ping()
                assertTrue(reply.ok)
                assertTrue(abs(reply.now!! - Instant.now().epochSecond) < 5, "the store's clock is ${reply.now}")
            }
            assertEquals(listOf("nop-sync/key-check"), store.keys())
            assertTrue(store.authorizations.isNotEmpty())
            assertTrue(store.authorizations.all { it.startsWith("AWS4-HMAC-SHA256 Credential=$ACCESS_KEY/") && "/auto/s3/aws4_request" in it })
            assertTrue(store.authorizations.none { SECRET_KEY in it })
        }
    }

    @Test
    fun `a lease is claimed once per generation and kept only by its holder`() {
        FakeS3().use { store ->
            SyncLink(remote(store)).use { link ->
                assertNull(link.read(key).lease)

                val first = link.claim(key, 0, seatA, "alpha-box", "/work/shop", "abc123", false)
                assertTrue(first.ok)
                assertEquals(1L, first.lease?.gen)
                assertEquals(seatA, first.lease?.seat)
                assertEquals("alpha-box", first.lease?.host)
                assertEquals("abc123", first.lease?.head)
                assertFalse(first.pushed)

                val stale = link.claim(key, 0, seatB, "bravo-box", "/home/b/shop", "abc123", false)
                assertEquals("lost", stale.result)
                assertEquals(seatA, stale.lease?.seat)

                val second = link.claim(key, 1, seatB, "bravo-box", "/home/b/shop", "fedcba9", true)
                assertTrue(second.ok)
                assertEquals(2L, second.lease?.gen)
                assertEquals(seatB, second.lease?.seat)
                assertTrue(second.lease!!.dirty)

                assertEquals("lost", link.beat(key, 2, seatA, "abc123", false).result)
                assertEquals("lost", link.beat(key, 1, seatB, "abc123", false).result)
                val beat = link.beat(key, 2, seatB, "0a1b2c3", false)
                assertTrue(beat.ok)
                assertEquals("0a1b2c3", beat.lease?.head)
                assertFalse(beat.lease!!.dirty)

                val read = link.read(key).lease!!
                assertEquals(2L, read.gen)
                assertEquals(seatB, read.seat)
                assertEquals("/home/b/shop", read.root)
                assertEquals("0a1b2c3", read.head)
                assertTrue(abs(read.at - Instant.now().epochSecond) < 5, "the lease was written at ${read.at}")
            }
            assertNull(store.keys().firstOrNull { it.startsWith("nop-sync/probe/") })
        }
    }

    @Test
    fun `of two machines claiming at once only one gets the lease`() {
        FakeS3().use { store ->
            SyncLink(remote(store)).use { a ->
                SyncLink(remote(store)).use { b ->
                    a.ping()
                    b.ping()
                    val start = CountDownLatch(1)
                    val results = ConcurrentHashMap<String, SyncReply>()
                    val threads = listOf(seatA to a, seatB to b).map { (seat, link) ->
                        Thread {
                            start.await()
                            results[seat] = link.claim(key, 0, seat, "box-$seat", "/work/shop", "abc123", false)
                        }.also { it.start() }
                    }
                    start.countDown()
                    threads.forEach { it.join(10_000) }
                    assertEquals(2, results.size)
                    assertEquals(1, results.values.count { it.ok })
                    assertEquals(1, results.values.count { it.result == "lost" })
                    val winner = results.entries.single { it.value.ok }.key
                    assertEquals(winner, a.read(key).lease?.seat)
                    assertEquals(1L, b.read(key).lease?.gen)
                }
            }
        }
    }

    @Test
    fun `tabs sent from one machine arrive whole on another, without credentials or live files`(@TempDir tmp: Path) {
        val from = tmp.resolve("from")
        write(from.resolve("open-agents.json"), "{\"tabs\":[\"s1\"]}\n")
        write(from.resolve("logs/s1.jsonl"), "{\"type\":\"session_started\"}\n")
        val big = ByteArray(SyncLink.CHUNK * 2 + 123) { (it * 31 % 251).toByte() }
        Files.createDirectories(from.resolve("native/s1/claude"))
        Files.write(from.resolve("native/s1/claude/c1.jsonl"), big)
        write(from.resolve("native/s1/claude/.credentials.json"), "{\"token\":\"not-a-real-token\"}")
        write(from.resolve("native/s1/codex/auth.json"), "{\"token\":\"not-a-real-token\"}")
        write(from.resolve("native/s1/codex/history.db-wal"), "wal")
        Files.createSymbolicLink(from.resolve("native/s1/link"), from.resolve("logs/s1.jsonl"))

        val elsewhere = write(tmp.resolve("elsewhere/keep.txt"), "not nop's")
        val into = tmp.resolve("into")
        write(into.resolve("left-over"), "old")
        write(into.resolve("logs/old.jsonl"), "old")
        Files.createDirectories(into.resolve("stale/dir"))
        Files.createSymbolicLink(into.resolve("native"), elsewhere.parent)

        FakeS3().use { store ->
            SyncLink(remote(store)).use { link ->
                link.claim(key, 0, seatA, "alpha-box", "/work/shop", "abc123", false)
                assertFalse(link.read(key).pushed)
                link.push(key, seatA, from)
                assertTrue(link.read(key).pushed)

                assertEquals("{\"type\":\"session_started\"}\n", sent(store, seatA, "logs/s1.jsonl"))
                for (left in listOf(
                    "native/s1/claude/.credentials.json", "native/s1/codex/auth.json", "native/s1/codex/history.db-wal", "native/s1/link",
                )) {
                    assertNull(sent(store, seatA, left), left)
                }
                for (stored in store.keys()) {
                    val text = String(store.body(stored)!!, Charsets.ISO_8859_1)
                    assertFalse("session_started" in text || "\"tabs\"" in text || "not-a-real-token" in text, "$stored is not sealed")
                }

                link.pull(key, seatA, into)

                assertEquals(
                    listOf("logs/s1.jsonl", "native/s1/claude/c1.jsonl", "open-agents.json"),
                    files(into),
                )
                assertArrayEquals(big, Files.readAllBytes(into.resolve("native/s1/claude/c1.jsonl")))
                assertEquals(
                    Files.getLastModifiedTime(from.resolve("native/s1/claude/c1.jsonl")).toMillis(),
                    Files.getLastModifiedTime(into.resolve("native/s1/claude/c1.jsonl")).toMillis(),
                )
                assertEquals(Files.readString(from.resolve("logs/s1.jsonl")), Files.readString(into.resolve("logs/s1.jsonl")))
                assertFalse(Files.exists(into.resolve("stale"), LinkOption.NOFOLLOW_LINKS))
                assertTrue(Files.isDirectory(into.resolve("native"), LinkOption.NOFOLLOW_LINKS))
                assertEquals("not nop's", Files.readString(elsewhere))
                assertEquals(
                    setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    Files.getPosixFilePermissions(into.resolve("logs/s1.jsonl")),
                )

                store.requests.clear()
                link.pull(key, seatA, into)
                assertTrue(store.requests.none { "/blobs/" in it }, "a pull with nothing new fetched ${store.requests}")

                Files.write(from.resolve("native/s1/claude/c1.jsonl"), big + "more".toByteArray())
                link.push(key, seatA, from)
                store.requests.clear()
                link.pull(key, seatA, into)
                assertEquals(1, store.requests.count { it.startsWith("GET ") && "/blobs/" in it }, "${store.requests}")
                assertArrayEquals(big + "more".toByteArray(), Files.readAllBytes(into.resolve("native/s1/claude/c1.jsonl")))
            }
        }
    }

    @Test
    fun `a store that lists a key at a time still gets each file once`(@TempDir tmp: Path) {
        val from = tmp.resolve("from")
        for (i in 1..5) write(from.resolve("logs/s$i.jsonl"), "{\"n\":$i}\n")
        FakeS3().use { store ->
            store.pageSize = 1
            SyncLink(remote(store)).use { link ->
                link.push(key, seatA, from)
                val first = blobs(store, seatA)
                assertEquals(5, first.size)

                store.requests.clear()
                link.push(key, seatA, from)
                assertTrue(store.requests.none { it.startsWith("PUT ") && "/blobs/" in it }, "${store.requests}")
                assertEquals(first, blobs(store, seatA))

                Files.writeString(from.resolve("logs/s1.jsonl"), "{\"n\":10}\n")
                Files.delete(from.resolve("logs/s5.jsonl"))
                link.push(key, seatA, from)
                assertEquals(4, named(store, seatA).size)
                assertEquals(named(store, seatA), blobs(store, seatA))
                assertEquals("{\"n\":10}\n", sent(store, seatA, "logs/s1.jsonl"))
                for (i in 2..4) assertEquals("{\"n\":$i}\n", sent(store, seatA, "logs/s$i.jsonl"))
                assertNull(sent(store, seatA, "logs/s5.jsonl"))
            }
        }
    }

    @Test
    fun `a copy changed in the store is refused`(@TempDir tmp: Path) {
        val from = tmp.resolve("from")
        write(from.resolve("logs/s1.jsonl"), "{\"type\":\"session_started\"}\n")
        val into = tmp.resolve("into")
        FakeS3().use { store ->
            SyncLink(remote(store)).use { link ->
                link.push(key, seatA, from)

                val chunk = store.keys().first { "/blobs/" in it }
                val body = store.body(chunk)!!.clone()
                body[body.size - 1] = (body[body.size - 1] + 1).toByte()
                store.replace(chunk, body)
                val changed = assertThrows(SyncFailure::class.java) { link.pull(key, seatA, into) }
                assertEquals(SyncCrypto.NOT_OURS, changed.message)
                assertEquals(emptyList<String>(), files(into))

                store.replace("nop-sync/${machine(seatB)}/manifest", store.body("nop-sync/${machine(seatA)}/manifest")!!)
                assertThrows(SyncFailure::class.java) { link.pull(key, seatB, into) }

                forge(store, seatB, "{\"version\":1,\"files\":[]}", SyncCrypto.parse(SyncCrypto.generate())!!)
                assertThrows(SyncFailure::class.java) { link.pull(key, seatB, into) }

                forge(store, seatB, "{\"version\":2,\"files\":[]}")
                val newer = assertThrows(SyncFailure::class.java) { link.pull(key, seatB, into) }
                assertTrue(newer.message!!.contains("newer nop"), newer.message)
            }
        }
    }

    @Test
    fun `a list naming places outside the folder or files nop never sends is only partly followed`(@TempDir tmp: Path) {
        val from = tmp.resolve("from")
        write(from.resolve("logs/s1.jsonl"), "hello\n")
        val into = tmp.resolve("work/into")
        FakeS3().use { store ->
            SyncLink(remote(store)).use { link ->
                link.push(key, seatA, from)
                val good = manifest(store, seatA)!!["files"]!!.jsonArray.single().jsonObject
                val goodSize = good["size"]!!.jsonPrimitive.content
                val mtime = good["mtime"]!!.jsonPrimitive.content
                val goodChunks = good["chunks"].toString()
                fun entry(path: String, size: String = goodSize, chunks: String = goodChunks) =
                    "{\"path\":${JsonPrimitive(path)},\"size\":$size,\"mtime\":$mtime,\"chunks\":$chunks}"
                val forged = listOf(
                    entry("logs/s1.jsonl"),
                    entry("../escape"),
                    entry("/abs"),
                    entry("logs/./s2.jsonl"),
                    entry("native/s1/claude/.credentials.json"),
                    entry("native/s1/codex/history.db-wal"),
                    entry("logs/long.jsonl", size = (SyncLink.CHUNK + 1).toString()),
                    entry("logs/bad-name.jsonl", chunks = "[\"zz\"]"),
                )
                forge(store, seatA, "{\"version\":1,\"files\":[${forged.joinToString(",")}]}")
                link.pull(key, seatA, into)
            }
        }
        assertEquals(listOf("logs/s1.jsonl"), files(into))
        assertEquals("hello\n", Files.readString(into.resolve("logs/s1.jsonl")))
        assertEquals(listOf("from/logs/s1.jsonl", "work/into/logs/s1.jsonl"), files(tmp))
    }

    @Test
    fun `a machine with another sync key is told so`() {
        FakeS3().use { store ->
            SyncLink(remote(store)).use { link -> assertTrue(link.claim(key, 0, seatA, "alpha-box", "/work/shop", "abc123", false).ok) }
            SyncLink(remote(store, SyncCrypto.generate())).use { other ->
                val ping = assertThrows(SyncFailure::class.java) { other.ping() }
                assertTrue(ping.message!!.contains("the sync key is not the one"), ping.message)
                val claim = assertThrows(SyncFailure::class.java) { other.claim(key, 1, seatB, "bravo-box", "/home/b/shop", "abc123", false) }
                assertTrue(claim.message!!.contains("the sync key is not the one"), claim.message)
                val read = assertThrows(SyncFailure::class.java) { other.read(key) }
                assertTrue(read.message!!.contains("sync key"), read.message)
            }
        }
    }

    @Test
    fun `a store that ignores conditional writes is refused before anything is claimed`() {
        FakeS3().use { store ->
            store.conditional = false
            SyncLink(remote(store)).use { link ->
                val ping = assertThrows(SyncFailure::class.java) { link.ping() }
                assertTrue(ping.message!!.contains("does not honour conditional writes"), ping.message)
                assertThrows(SyncFailure::class.java) { link.claim(key, 0, seatA, "alpha-box", "/work/shop", "abc123", false) }
            }
            assertEquals(listOf("nop-sync/key-check"), store.keys())
        }
    }

    @Test
    fun `a missing bucket and a closed link are failures with words for the user`() {
        FakeS3("other-bucket").use { store ->
            SyncLink(remote(store)).use { link ->
                val ping = assertThrows(SyncFailure::class.java) { link.ping() }
                assertTrue(ping.message!!.contains("there is no bucket called ${FakeS3.BUCKET}"), ping.message)
            }
        }
        FakeS3().use { store ->
            val link = SyncLink(remote(store))
            link.close()
            assertThrows(SyncFailure::class.java) { link.ping() }
        }
    }

    @Test
    fun `a remote is checked before it is used`() {
        val good = SyncRemote(
            endpoint = "https://0123abcd.r2.cloudflarestorage.com", bucket = "shop-sync", accessKey = ACCESS_KEY,
            secretKey = SECRET_KEY, syncKey = syncKey,
        )
        assertNull(SyncLink.problem(good))
        val bad = listOf(
            good.copy(endpoint = ""),
            good.copy(endpoint = "ftp://store.example.com"),
            good.copy(endpoint = "http://store.example.com"),
            good.copy(endpoint = "https://user@store.example.com"),
            good.copy(endpoint = "https://store.example.com?x=1"),
            good.copy(endpoint = "https://store.example.com/shop-sync"),
            good.copy(bucket = "A_B"),
            good.copy(prefix = "a/../b"),
            good.copy(region = "US East"),
            good.copy(accessKey = ""),
            good.copy(secretKey = "has space"),
            good.copy(syncKey = "short"),
            good.copy(rateLimit = -1),
        )
        for (remote in bad) assertNotNull(SyncLink.problem(remote), remote.toString())
        assertNull(SyncLink.problem(good.copy(endpoint = "http://127.0.0.1:9000")))
        assertNull(SyncLink.problem(good.copy(rateLimit = 0)))
        assertNull(SyncLink.problem(good.copy(prefix = "")))
        assertNull(SyncLink.problem(good.copy(prefix = "team/nop-sync")))
        assertThrows(SyncFailure::class.java) { SyncLink(good.copy(bucket = "A_B")) }
        assertFalse(SECRET_KEY in good.toString() || syncKey in good.toString() || ACCESS_KEY in good.toString())
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
        FakeS3().use { store ->
            val remote = remote(store)
            val a = Machine(tmp.resolve("a"))
            val b = Machine(tmp.resolve("b"))
            Settings.saveOpenAgents(a.root, listOf(tab("s1", native = "c1")))
            write(a.log("s1"), "{\"type\":\"session_started\"}\n")
            write(a.transcript("c1"), "{\"type\":\"user\"}\n")

            val stopsA = AtomicInteger()
            val stopsB = AtomicInteger()
            fun sync(m: Machine, seat: String, host: String, stops: AtomicInteger) = ProjectSync(
                root = m.root, link = SyncLink(remote), key = key, seat = seat, host = host, files = m.files,
                target = remote.target, head = { "abc123" }, dirty = { false }, stopSessions = { stops.incrementAndGet() },
                post = { it() }, interval = 250.milliseconds,
            )
            val syncA = sync(a, seatA, "alpha-box", stopsA)
            val syncB = sync(b, seatB, "bravo-box", stopsB)
            try {
                syncA.start()
                waitFor("the first machine to send its tabs") {
                    sent(store, seatA, "native/s1/claude/c1.jsonl") != null
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
    }

    @Test
    fun `a sync that is abandoned sends nothing more`(@TempDir tmp: Path) {
        FakeS3().use { store ->
            val remote = remote(store)
            val a = Machine(tmp.resolve("a"))
            Settings.saveOpenAgents(a.root, listOf(tab("s1", native = "c1")))
            write(a.log("s1"), "{\"type\":\"session_started\"}\n")
            val sync = ProjectSync(
                root = a.root, link = SyncLink(remote), key = key, seat = seatA, host = "alpha-box", files = a.files,
                target = remote.target, head = { "abc123" }, dirty = { false }, stopSessions = {},
                post = { it() }, interval = 250.milliseconds,
            )
            sync.start()
            waitFor("the machine to send its tabs") { sent(store, seatA, "logs/s1.jsonl") != null }
            val before = sent(store, seatA, "logs/s1.jsonl")

            sync.abandon()
            Files.writeString(a.log("s1"), before + "{\"type\":\"user_message\"}\n")
            Thread.sleep(1_000)
            sync.finish(Instant.now().plusSeconds(5))
            assertEquals(before, sent(store, seatA, "logs/s1.jsonl"))
        }
    }

    private companion object {
        const val ACCESS_KEY = "AKTESTKEY01"
        const val SECRET_KEY = "secret-for-tests-only"
    }
}
