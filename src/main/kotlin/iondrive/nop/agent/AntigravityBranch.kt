package iondrive.nop.agent

import iondrive.nop.agent.transcript.LiveTranscripts
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.sql.DriverManager
import java.util.UUID
import java.time.Instant

/** Antigravity resumes SQLite steps; its readable JSON transcript alone cannot resume a branch. */
internal object AntigravityBranch {
    private val request = Regex("""<USER_REQUEST>\s*(.*?)\s*</USER_REQUEST>""", RegexOption.DOT_MATCHES_ALL)

    fun points(account: Account, conversation: String): List<ConversationBranchPoint> {
        val file = Antigravity.conversationTranscript(account.homePath, conversation)
        check(Files.isRegularFile(file) && Files.isRegularFile(database(account.homePath, conversation))) {
            "This Antigravity conversation's saved history is not available."
        }
        val points = mutableListOf<ConversationBranchPoint>()
        val preview = StringBuilder()
        DriverManager.getConnection("jdbc:sqlite:${database(account.homePath, conversation).toUri()}?mode=ro").use { db ->
            db.createStatement().use { statement ->
                statement.executeQuery("SELECT idx,step_type,status,step_payload,metadata FROM steps ORDER BY idx").use { rows ->
                    while (rows.next()) {
                        val type = rows.getInt(2)
                        if (type != 14 && type != 15) continue
                        val payload = Proto(rows.getBytes(4))
                        val meta = Proto(rows.getBytes(5) ?: byteArrayOf()).message(1)
                        val at = meta?.let { Instant.ofEpochSecond(it.number(1), it.number(2)).toEpochMilli() }
                        val body = if (type == 14) userText(payload) else assistantText(payload)
                        when (type) {
                            14 -> {
                                val text = request.find(body)?.groupValues?.get(1)?.trim() ?: body
                                if (text.isBlank()) continue
                                if (points.isEmpty()) points += ConversationBranchPoint(account, conversation, file, null, "Start of conversation", "", text, at = at)
                                else points[points.lastIndex] = points.last().copy(following = text)
                                preview.append("You:\n$text\n\n")
                            }
                            15 -> {
                                if (body.isNotBlank()) preview.append("Agent:\n$body\n\n")
                                val step = rows.getInt(1)
                                if (body.isNotBlank() && rows.getInt(3) == 3 && payload.message(20)?.has(7) != true) {
                                    points += ConversationBranchPoint(account, conversation, file, step.toString(),
                                        preview.toString().trim().takeLast(2200), body, at = at)
                                }
                            }
                        }
                        if (preview.length > 6000) preview.delete(0, preview.length - 4000)
                    }
                }
            }
        }
        return points
    }

    internal fun database(home: Path, conversation: String): Path =
        Antigravity.cliDir(home).resolve("conversations").resolve("$conversation.db")

    /** Copies a consistent database snapshot without writing to the original or its WAL. */
    fun create(point: ConversationBranchPoint): String {
        val cutoff = point.boundary?.toIntOrNull() ?: error("Invalid Antigravity branch boundary.")
        val source = database(point.account.homePath, point.conversation)
        val id = UUID.randomUUID().toString()
        val target = database(point.account.homePath, id)
        val temporary = Files.createTempFile(source.parent, ".branch-", ".db")
        OwnerOnly.tighten(temporary)
        LiveTranscripts.claim(id, "branch-$id")
        try {
            DriverManager.getConnection("jdbc:sqlite:${source.toUri()}?mode=ro").use { original ->
                original.createStatement().use { statement ->
                    statement.execute("PRAGMA busy_timeout=5000")
                    statement.execute("VACUUM INTO '${temporary.toString().replace("'", "''")}'")
                }
            }
            DriverManager.getConnection("jdbc:sqlite:$temporary").use { copy ->
                val tables = copy.createStatement().use { statement ->
                    statement.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'").use { rows ->
                        buildSet { while (rows.next()) add(rows.getString(1)) }
                    }
                }
                check(tables == setOf("trajectory_meta", "steps", "gen_metadata", "executor_metadata", "parent_references", "trajectory_metadata_blob", "battle_mode_infos")) {
                    "This Antigravity history format is not supported."
                }
                copy.prepareStatement("SELECT step_type,status,step_payload FROM steps WHERE idx=?").use { statement ->
                    statement.setInt(1, cutoff)
                    statement.executeQuery().use { rows ->
                        check(rows.next() && rows.getInt(1) == 15 && rows.getInt(2) == 3) {
                            "The selected Antigravity reply is not complete."
                        }
                        val payload = Proto(rows.getBytes(3))
                        check(payload.message(20)?.has(7) != true) { "The selected Antigravity reply has pending tool calls." }
                        check(assistantText(payload) == point.preceding) {
                            "The selected Antigravity reply changed. Open Branch Here again."
                        }
                    }
                }
                copy.autoCommit = false
                copy.prepareStatement("DELETE FROM steps WHERE idx>?").use { it.setInt(1, cutoff); it.executeUpdate() }
                // These are generation caches, execution bookkeeping and inherited references, not
                // conversation steps. A branch must rehydrate them from the retained steps only.
                copy.createStatement().use { statement ->
                    for (table in listOf("gen_metadata", "executor_metadata", "parent_references", "battle_mode_infos")) statement.executeUpdate("DELETE FROM $table")
                }
                copy.prepareStatement("UPDATE trajectory_meta SET trajectory_id=?,cascade_id=?").use {
                    it.setString(1, id); it.setString(2, id); it.executeUpdate()
                }
                val metadata = copy.createStatement().use { statement ->
                    statement.executeQuery("SELECT id,data FROM trajectory_metadata_blob").use { rows ->
                        buildList { while (rows.next()) add(rows.getString(1) to rows.getBytes(2)) }
                    }
                }
                copy.prepareStatement("UPDATE trajectory_metadata_blob SET data=? WHERE id=?").use { statement ->
                    for ((key, bytes) in metadata) {
                        statement.setBytes(1, branchMetadata(bytes ?: byteArrayOf(), id)); statement.setString(2, key); statement.executeUpdate()
                    }
                }
                copy.commit()
                // VACUUM removes discarded rows from free pages as well as the live tables.
                copy.autoCommit = true
                copy.createStatement().use { it.execute("VACUUM") }
            }
            writeTranscript(point, id, cutoff)
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE)
            return id
        } finally { Files.deleteIfExists(temporary) }
    }

    private fun userText(step: Proto): String = step.message(19)?.let { input ->
        input.messages(3).map { it.text(1) }.filter { it.isNotBlank() }.joinToString("\n").ifBlank { input.text(1) }
    }.orEmpty()

    private fun assistantText(step: Proto): String = step.message(20)?.let { it.text(8).ifBlank { it.text(1) } }.orEmpty()

    /** Reads the stable protobuf wire format while leaving native step payloads unchanged. */
    private class Proto(private val bytes: ByteArray) {
        private data class Field(val number: Int, val wire: Int, val value: Long, val start: Int, val end: Int)
        private val fields = buildList {
            var at = 0
            fun varint(): Long {
                var value = 0L
                for (shift in 0..63 step 7) {
                    check(at < bytes.size) { "Incomplete Antigravity step." }
                    val byte = bytes[at++].toInt() and 255
                    value = value or ((byte and 127).toLong() shl shift)
                    if (byte < 128) return value
                }
                error("Invalid Antigravity step.")
            }
            while (at < bytes.size) {
                val tag = varint().toInt()
                val wire = tag and 7
                val value = if (wire == 0 || wire == 2) varint() else 0L
                val start = at
                when (wire) {
                    0 -> Unit
                    1 -> at += 8
                    2 -> { check(value in 0..(bytes.size - at).toLong()); at += value.toInt() }
                    5 -> at += 4
                    else -> error("Unsupported Antigravity step field.")
                }
                check(at <= bytes.size && tag ushr 3 > 0) { "Invalid Antigravity step field." }
                add(Field(tag ushr 3, wire, value, start, at))
            }
        }
        fun has(number: Int): Boolean = fields.any { it.number == number }
        fun number(number: Int): Long = fields.lastOrNull { it.number == number && it.wire == 0 }?.value ?: 0L
        fun text(number: Int): String = fields.lastOrNull { it.number == number && it.wire == 2 }
            ?.let { String(bytes, it.start, it.end - it.start, Charsets.UTF_8) }.orEmpty()
        fun messages(number: Int): List<Proto> = fields.filter { it.number == number && it.wire == 2 }.map { Proto(bytes.copyOfRange(it.start, it.end)) }
        fun message(number: Int): Proto? = messages(number).lastOrNull()
    }

    private fun writeTranscript(point: ConversationBranchPoint, id: String, cutoff: Int) {
        val logs = Antigravity.cliDir(point.account.homePath).resolve("brain").resolve(id).resolve(".system_generated/logs")
        OwnerOnly.directory(logs)
        Files.newBufferedWriter(OwnerOnly.file(logs.resolve("transcript_full.jsonl"))).use { output ->
            Files.newBufferedReader(point.transcript).useLines { lines ->
                for (line in lines) {
                    val record = runCatching { Json.parseToJsonElement(line).obj() }.getOrNull() ?: continue
                    val index = record["step_index"].int() ?: continue
                    if (index <= cutoff) output.appendLine(line)
                }
            }
        }
    }

    /** Immutable workspace/configuration metadata, with an independent root conversation id. */
    internal fun branchMetadata(bytes: ByteArray, id: String): ByteArray {
        var at = 0
        fun varint(): Long {
            var value = 0L
            for (shift in 0..63 step 7) {
                check(at < bytes.size) { "Incomplete Antigravity metadata." }
                val byte = bytes[at++].toInt() and 255
                value = value or ((byte and 127).toLong() shl shift)
                if (byte < 128) return value
            }
            error("Invalid Antigravity metadata.")
        }
        val output = ByteArrayOutputStream()
        while (at < bytes.size) {
            val start = at
            val tag = varint().toInt()
            check(tag ushr 3 > 0) { "Invalid Antigravity metadata field." }
            when (tag and 7) {
                0 -> varint()
                1 -> at += 8
                2 -> { val size = varint(); check(size in 0..(bytes.size - at).toLong()); at += size.toInt() }
                5 -> at += 4
                else -> error("Unsupported Antigravity metadata field.")
            }
            check(at <= bytes.size) { "Incomplete Antigravity metadata field." }
            if (tag ushr 3 in setOf(1, 2, 3, 7, 10, 11, 12, 15, 18, 19, 24)) output.write(bytes, start, at - start)
        }
        output.write(6 shl 3 or 2)
        val root = id.toByteArray(Charsets.UTF_8)
        output.write(root.size)
        output.write(root)
        return output.toByteArray()
    }
}
