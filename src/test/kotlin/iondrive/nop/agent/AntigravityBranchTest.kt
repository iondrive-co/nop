package iondrive.nop.agent

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class AntigravityBranchTest {
    @TempDir lateinit var root: Path
    private val account get() = Account("agent-larch", Provider.Antigravity, root.resolve("home").toString())
    private val future = "Greenhouse material belongs only to the shelved continuation."

    private fun varint(number: Long): ByteArray {
        var remaining = number
        val output = java.io.ByteArrayOutputStream()
        while (remaining > 127) { output.write((remaining.toInt() and 127) or 128); remaining = remaining ushr 7 }
        output.write(remaining.toInt())
        return output.toByteArray()
    }
    private fun scalar(field: Int, value: Long) = varint((field shl 3).toLong()) + varint(value)
    private fun data(field: Int, value: String) = data(field, value.toByteArray())
    private fun data(field: Int, value: ByteArray) = varint((field shl 3 or 2).toLong()) + varint(value.size.toLong()) + value

    private fun fixture(): Pair<String, Path> {
        val id = UUID.randomUUID().toString()
        val file = AntigravityBranch.database(account.homePath, id)
        Files.createDirectories(file.parent)
        DriverManager.getConnection("jdbc:sqlite:$file").use { db ->
            db.createStatement().use { statement ->
                for (ddl in listOf(
                    "CREATE TABLE trajectory_meta (trajectory_id TEXT PRIMARY KEY,cascade_id TEXT,trajectory_type INTEGER,source INTEGER)",
                    "CREATE TABLE steps (idx INTEGER PRIMARY KEY,step_type INTEGER,status INTEGER,has_subtrajectory NUMERIC DEFAULT 0,metadata BLOB,error_details BLOB,permissions BLOB,task_details BLOB,render_info BLOB,step_payload BLOB,step_format INTEGER DEFAULT 0)",
                    "CREATE TABLE gen_metadata (idx INTEGER PRIMARY KEY,data BLOB,size INTEGER DEFAULT 0)",
                    "CREATE TABLE executor_metadata (idx INTEGER PRIMARY KEY,data BLOB)",
                    "CREATE TABLE parent_references (idx INTEGER PRIMARY KEY,data BLOB)",
                    "CREATE TABLE trajectory_metadata_blob (id TEXT PRIMARY KEY,data BLOB)",
                    "CREATE TABLE battle_mode_infos (idx INTEGER PRIMARY KEY,data BLOB)",
                )) statement.execute(ddl)
            }
            db.prepareStatement("INSERT INTO trajectory_meta VALUES (?,?,1,1)").use {
                it.setString(1, id); it.setString(2, id); it.executeUpdate()
            }
            db.prepareStatement("INSERT INTO trajectory_metadata_blob VALUES ('main',?)").use {
                it.setBytes(1, data(7, root.toUri().toString()) + data(6, id) + data(99, future)); it.executeUpdate()
            }
            for (table in listOf("gen_metadata", "executor_metadata", "parent_references", "battle_mode_infos")) {
                db.prepareStatement("INSERT INTO $table (idx,data) VALUES (0,?)").use { it.setBytes(1, future.toByteArray()); it.executeUpdate() }
            }
            val messages = listOf("Describe the orchard.", "The orchard has an eastern path.", "Describe the greenhouse.", future)
            val transcript = Antigravity.conversationTranscript(account.homePath, id)
            Files.createDirectories(transcript.parent)
            Files.newBufferedWriter(transcript).use { log ->
                for ((index, body) in messages.withIndex()) {
                    val user = index % 2 == 0
                    val type = if (user) 14 else 15
                    val created = 1_744_000_000L + index
                    val stamp = data(1, scalar(1, created))
                    val payload = scalar(1, type.toLong()) + scalar(4, 3) + data(5, stamp) +
                        data(if (user) 19 else 20, if (user) data(3, data(1, body)) else data(1, body))
                    db.prepareStatement("INSERT INTO steps (idx,step_type,status,metadata,step_payload) VALUES (?,?,3,?,?)").use {
                        it.setInt(1, index); it.setInt(2, type); it.setBytes(3, stamp); it.setBytes(4, payload); it.executeUpdate()
                    }
                    log.appendLine(buildJsonObject {
                        put("type", if (user) "USER_INPUT" else "PLANNER_RESPONSE"); put("step_index", index)
                        put("status", "DONE"); put("created_at", Instant.ofEpochSecond(created).toString())
                        put("content", if (user) "<USER_REQUEST>$body</USER_REQUEST>" else body)
                    }.toString())
                }
            }
        }
        return id to file
    }

    @Test fun `Antigravity forks its native database and transcript through the selected step only`() {
        val (id, original) = fixture()
        val originalBytes = Files.readAllBytes(original)
        val sourceLog = Antigravity.conversationTranscript(account.homePath, id)
        val originalLog = Files.readString(sourceLog)
        val points = ConversationBranch.points(account, root, id)
        assertEquals(listOf(null, "1", "3"), points.map { it.boundary })
        val point = points[1]
        assertFalse(point.preview.contains("greenhouse"))
        val branch = ConversationBranch.create(point)!!
        val file = AntigravityBranch.database(account.homePath, branch)
        DriverManager.getConnection("jdbc:sqlite:$file").use { db ->
            db.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM steps").use { assertTrue(it.next()); assertEquals(2, it.getInt(1)) }
                statement.executeQuery("SELECT trajectory_id,cascade_id FROM trajectory_meta").use {
                    assertTrue(it.next()); assertEquals(branch, it.getString(1)); assertEquals(branch, it.getString(2))
                }
                for (table in listOf("gen_metadata", "executor_metadata", "parent_references", "battle_mode_infos")) {
                    statement.executeQuery("SELECT COUNT(*) FROM $table").use { assertTrue(it.next()); assertEquals(0, it.getInt(1)) }
                }
            }
        }
        assertFalse(String(Files.readAllBytes(file), Charsets.ISO_8859_1).contains(future), "discarded data must not survive in SQLite free pages")
        val log = Files.readString(Antigravity.conversationTranscript(account.homePath, branch))
        assertTrue(log.contains("eastern path")); assertFalse(log.contains("greenhouse"))
        assertArrayEquals(originalBytes, Files.readAllBytes(original))
        assertEquals(originalLog, Files.readString(sourceLog))
    }

    @Test fun `a live WAL is included without changing the source database or WAL`() {
        val (id, file) = fixture()
        DriverManager.getConnection("jdbc:sqlite:$file").use { db ->
            db.createStatement().use {
                it.execute("PRAGMA journal_mode=WAL"); it.execute("PRAGMA wal_autocheckpoint=0")
                it.executeUpdate("UPDATE steps SET render_info=x'1234' WHERE idx=1")
            }
            val wal = file.resolveSibling(file.fileName.toString() + "-wal")
            val before = Files.readAllBytes(file); val beforeWal = Files.readAllBytes(wal)
            val point = ConversationBranch.points(account, root, id)[1]
            val branch = ConversationBranch.create(point)!!
            DriverManager.getConnection("jdbc:sqlite:${AntigravityBranch.database(account.homePath, branch)}").use { copy ->
                copy.createStatement().executeQuery("SELECT hex(render_info) FROM steps WHERE idx=1").use {
                    assertTrue(it.next()); assertEquals("1234", it.getString(1))
                }
            }
            assertArrayEquals(before, Files.readAllBytes(file)); assertArrayEquals(beforeWal, Files.readAllBytes(wal))
        }
    }

    @Test fun `an incomplete native reply cannot be published as a branch`() {
        val (id, file) = fixture()
        val point = ConversationBranch.points(account, root, id)[1]
        DriverManager.getConnection("jdbc:sqlite:$file").use { it.createStatement().executeUpdate("UPDATE steps SET status=1 WHERE idx=1") }
        assertThrows(IllegalStateException::class.java) { ConversationBranch.create(point) }
        assertEquals(1L, Files.list(file.parent).use { it.count() })
    }

    @Test fun `a planner response with pending tools is not a completed conversation boundary`() {
        val (id, file) = fixture()
        val point = ConversationBranch.points(account, root, id)[1]
        val payload = scalar(1, 15) + scalar(4, 3) + data(20,
            data(1, point.preceding) + data(7, byteArrayOf()))
        DriverManager.getConnection("jdbc:sqlite:$file").use { db ->
            db.prepareStatement("UPDATE steps SET step_payload=? WHERE idx=1").use { it.setBytes(1, payload); it.executeUpdate() }
        }
        assertEquals(listOf(null, "3"), ConversationBranch.points(account, root, id).map { it.boundary })
        assertThrows(IllegalStateException::class.java) { ConversationBranch.create(point) }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "NOP_BRANCH_SMOKE_ANTIGRAVITY", matches = ".+")
    fun `real Antigravity loads the branch with earlier user and assistant messages only`() {
        val (id, _) = fixture()
        val branch = ConversationBranch.create(ConversationBranch.points(account, root, id)[1])!!
        Files.writeString(Antigravity.tokenFile(account.homePath), """{"auth_method":"consumer","id_token_email":"dev@example.test","token":{"access_token":"offline-placeholder","refresh_token":"offline-refresh","token_type":"Bearer","expiry":"2099-01-01T00:00:00Z"}}""")
        Antigravity.prepareHome(account)
        val command = listOf("unshare", "-c", "-n", System.getenv("NOP_BRANCH_SMOKE_ANTIGRAVITY"), "acp", "--disable-telemetry")
        val process = ProcessBuilder(command).directory(root.toFile()).redirectError(root.resolve("acp.err").toFile()).apply {
            environment().clear(); environment().putAll(mapOf("HOME" to account.home, "PATH" to "/usr/bin:/bin", "TERM" to "dumb"))
        }.start()
        val task = FutureTask {
            val output = process.outputStream.bufferedWriter()
            val input = process.inputStream.bufferedReader()
            fun send(number: Int, method: String, params: JsonObject) {
                output.appendLine(buildJsonObject { put("jsonrpc", "2.0"); put("id", number); put("method", method); put("params", params) }.toString()); output.flush()
            }
            send(1, "initialize", buildJsonObject { put("protocolVersion", 1); put("clientCapabilities", buildJsonObject {}) })
            val received = StringBuilder()
            while (true) {
                val line = input.readLine() ?: error(Files.readString(root.resolve("acp.err")))
                val reply = Json.parseToJsonElement(line).jsonObject
                received.appendLine(line)
                check(reply["error"] == null) { line }
                if (reply["id"].str() == "1") send(2, "session/load", buildJsonObject {
                    put("sessionId", branch); put("cwd", root.toString()); put("mcpServers", buildJsonArray {})
                })
                if (reply["id"].str() == "2") break
            }
            received.toString()
        }
        thread(isDaemon = true) { task.run() }
        try {
            val replay = task.get(25, TimeUnit.SECONDS)
            assertTrue(replay.contains("Describe the orchard."), replay)
            assertTrue(replay.contains("The orchard has an eastern path."), replay)
            assertFalse(replay.contains("greenhouse"), replay)
            assertFalse(replay.contains(future), replay)
        } finally { process.destroyForcibly(); task.cancel(true) }
    }
}
