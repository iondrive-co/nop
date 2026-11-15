package iondrive.nop.agent

import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.UUID
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.abs

/** An immutable copy of the terminal at the instant its context menu opens. */
data class ConversationClick(
    val lines: List<String>,
    val row: Int,
    val capturedAt: Long = System.currentTimeMillis(),
    /** The reading view knows the original event, even when its words occur several times. */
    val messageAt: Long? = null,
)

/** A native history boundary; the preview and the fork always use this same identifier. */
data class ConversationBranchPoint(
    val account: Account,
    val conversation: String,
    val transcript: Path,
    val boundary: String?,
    val preview: String,
    val preceding: String,
    val following: String = "",
    val at: Long? = null,
)

/** Finds completed turns without reconstructing model context from nop's abbreviated event log. */
object ConversationBranch {
    private val json = Json { ignoreUnknownKeys = true }

    fun points(source: AgentSession, accounts: List<Account>): List<ConversationBranchPoint> {
        val runs = source.log.events().filterIsInstance<AgentEvent.RunStarted>()
            .filter { it.transcriptPath != null && it.nativeSessionId != null }
            .distinctBy { it.provider to it.nativeSessionId }
        val candidates = runs.flatMap { run ->
            val provider = Provider.byId(run.provider)
            val configured = accounts.firstOrNull { it.name == run.account && it.provider == provider }
            val account = configured?.let { if (run.home != null) it.copy(home = run.home) else it }
                ?: run.home?.let { home -> provider?.let { Account(run.account, it, home, run.model, run.reasoning) } }
            if (account == null) emptyList()
            else runCatching { points(account, source.projectDir.toPath(), run.nativeSessionId!!) }.getOrDefault(emptyList())
        }
        check(candidates.isNotEmpty()) { "No saved conversation is available to branch here." }
        return candidates
    }

    fun points(account: Account, project: Path, conversation: String): List<ConversationBranchPoint> {
        if (account.provider == Provider.Antigravity) return AntigravityBranch.points(account, conversation)
        val file = Transcripts.find(account.provider, account.homePath, project.toFile(), conversation)
            ?: error("This agent's saved conversation is not available for branching.")
        return when (account.provider) {
            Provider.Anthropic -> claudePoints(account, conversation, file)
            Provider.OpenAI -> codexPoints(account, conversation, file)
            Provider.Antigravity -> error("No saved Antigravity conversation is available.")
        }
    }

    private fun <T> records(file: Path, consume: (Sequence<JsonObject>) -> T): T = Files.newBufferedReader(file).useLines { lines ->
        consume(lines.mapNotNull { runCatching { json.parseToJsonElement(it).obj() }.getOrNull() })
    }

    private fun text(message: JsonObject?): String = message?.get("content").str()
        ?: message?.get("content").arr()?.mapNotNull { item ->
            item.obj()?.takeIf { it["type"].str() in setOf("text", "input_text", "output_text") }?.get("text").str()
        }?.joinToString("\n\n").orEmpty()

    private fun claudePoints(account: Account, conversation: String, file: Path): List<ConversationBranchPoint> {
        data class Entry(val id: String, val parent: String?, val role: String, val body: String, val stop: String?, val at: Long?)
        // Tool results can be megabytes; boundary indexing keeps only the text displayed to the user.
        val entries = records(file) { all ->
            all.filterNot { it["isSidechain"].bool() }.mapNotNull { record ->
                val id = record["uuid"].str() ?: return@mapNotNull null
                val role = record["type"].str()?.takeIf { it in setOf("user", "assistant", "system") } ?: return@mapNotNull null
                val message = record["message"].obj()
                Entry(id, record["parentUuid"].str() ?: record["logicalParentUuid"].str(), role,
                    if (role == "system") "" else text(message), message?.get("stop_reason").str(), timestamp(record["timestamp"].str()))
            }.toList()
        }
        // Only the active parent chain belongs to the conversation after an in-CLI rewind.
        val byId = entries.associateBy { it.id }
        val chain = mutableSetOf<String>()
        var entry = entries.lastOrNull { it.role == "user" || it.role == "assistant" }
        while (entry != null && chain.add(entry.id)) {
            entry = byId[entry.parent]
        }
        val result = mutableListOf<ConversationBranchPoint>()
        val preview = StringBuilder()
        var lastText = ""
        var completed: ConversationBranchPoint? = null
        for (record in entries.filter { it.id in chain }) {
            val body = record.body
            if (record.role == "user" && body.isNotBlank()) {
                completed?.let { result += it }; completed = null
                lastText = ""
                if (result.isEmpty()) result += ConversationBranchPoint(account, conversation, file, null, "Start of conversation", "", body, at = record.at)
                else result[result.lastIndex] = result.last().copy(following = body)
                preview.append("You:\n$body\n\n")
            }
            if (record.role == "assistant") {
                if (body.isNotBlank()) { preview.append("Agent:\n$body\n\n"); lastText = body }
                if (record.stop in setOf("end_turn", "stop_sequence") && lastText.isNotBlank()) {
                    completed = ConversationBranchPoint(account, conversation, file, record.id,
                        preview.toString().trim().takeLast(2200), lastText, at = record.at)
                }
            }
            if (preview.length > 6000) preview.delete(0, preview.length - 4000)
        }
        completed?.let { result += it }
        return result
    }

    private fun codexPoints(account: Account, conversation: String, file: Path): List<ConversationBranchPoint> {
        val result = mutableListOf<ConversationBranchPoint>()
        val preview = StringBuilder()
        var turn: String? = null
        var lastText = ""
        records(file) { records -> for (record in records) {
            val payload = record["payload"].obj() ?: continue
            when (record["type"].str()) {
                "turn_context" -> turn = payload["turn_id"].str() ?: turn
                "response_item" -> if (payload["type"].str() == "message") {
                    val body = text(payload)
                    when (payload["role"].str()) {
                        "user" -> if (body.isNotBlank() && !contextOnly.matches(body.trim()) && !body.startsWith("# AGENTS.md instructions for ")) {
                            if (result.isEmpty()) result += ConversationBranchPoint(account, conversation, file, null, "Start of conversation", "", body, at = timestamp(record["timestamp"].str()))
                            else result[result.lastIndex] = result.last().copy(following = body)
                            preview.append("You:\n$body\n\n")
                        }
                        "assistant" -> if (body.isNotBlank()) { preview.append("Agent:\n$body\n\n"); lastText = body }
                    }
                }
                "event_msg" -> when (payload["type"].str()) {
                    "task_started" -> { turn = payload["turn_id"].str() ?: turn; lastText = "" }
                    "task_complete" -> {
                        val id = payload["turn_id"].str() ?: turn
                        if (id != null && (payload["error"] == null || payload["error"] == JsonNull) && lastText.isNotBlank()) result += ConversationBranchPoint(account, conversation, file, id,
                            preview.toString().trim().takeLast(2200), lastText, at = timestamp(record["timestamp"].str()))
                    }
                    "thread_rolled_back" -> {
                        val count = payload["num_turns"].int() ?: 0
                        repeat(count.coerceAtMost((result.size - 1).coerceAtLeast(0))) { result.removeLast() }
                        preview.clear()
                        preview.append(result.lastOrNull()?.preview.orEmpty()).append("\n\n")
                    }
                }
            }
            if (preview.length > 6000) preview.delete(0, preview.length - 4000)
        } }
        return result
    }

    internal fun compact(text: String): String = text.filter(Char::isLetterOrDigit)
    internal fun timestamp(value: String?): Long? = value?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }
    private val contextOnly = Regex("""^<(environment_context|user_instructions|local-command-caveat|local-command-stdout|command-name)>[\s\S]*</\1>$""")

    /** Match both sides of a boundary, allowing CLI wrapping and Markdown decoration. */
    fun nearest(points: List<ConversationBranchPoint>, click: ConversationClick): ConversationBranchPoint {
        val rowEnds = IntArray(click.lines.size)
        val screen = buildString {
            click.lines.forEachIndexed { index, line -> append(compact(line)); rowEnds[index] = length }
        }
        data class Match(val point: ConversationBranchPoint, val offset: Int)
        data class Anchor(val text: String, val end: Boolean)
        val eligible = points.filter { it.at == null || it.at <= click.capturedAt }
        val anchors = eligible.associateWith { point ->
            listOf(Anchor(compact(point.preceding).takeLast(120), true), Anchor(compact(point.following).take(120), false))
                .filter { it.text.isNotEmpty() }
        }
        val occurrences = mutableMapOf<Anchor, IntArray>()
        fun positions(anchor: Anchor): IntArray = occurrences.getOrPut(anchor) {
            val found = mutableListOf<Int>()
            if (anchor.text.length >= 8) {
                var offset = screen.indexOf(anchor.text)
                while (offset >= 0) {
                    found += offset + if (anchor.end) anchor.text.length else 0
                    offset = screen.indexOf(anchor.text, offset + 1)
                }
            } else {
                click.lines.forEachIndexed { index, line ->
                    if (compact(line) == anchor.text) found += if (anchor.end) rowEnds[index] else if (index == 0) 0 else rowEnds[index - 1]
                }
            }
            found.toIntArray()
        }
        fun lowerBound(values: IntArray, offset: Int): Int {
            var low = 0
            var high = values.size
            while (low < high) {
                val middle = (low + high) / 2
                if (values[middle] < offset) low = middle + 1 else high = middle
            }
            return low
        }
        fun rowAt(offset: Int): Int = lowerBound(rowEnds, offset).coerceAtMost(rowEnds.lastIndex)
        // The terminal contains an ordered suffix of native history. Assign repeated passages
        // from the end, so the second visible occurrence belongs to the second native turn.
        val aligned = mutableListOf<Match>()
        var limit = screen.length + 1
        for (point in eligible.asReversed()) {
            val offset = anchors.getValue(point).mapNotNull { anchor ->
                val positions = positions(anchor)
                positions.getOrNull(lowerBound(positions, limit) - 1)
            }.maxOrNull() ?: continue
            aligned += Match(point, offset)
            limit = offset
        }
        // A unique boundary may be printed more than once by a CLI redraw.
        val owners = anchors.values.flatten().groupingBy { it }.eachCount()
        val unambiguous = eligible.flatMap { point -> anchors.getValue(point).filter { owners[it] == 1 }
            .flatMap { anchor -> positions(anchor).map { Match(point, it) } } }
        val at = rowEnds.getOrNull(click.row) ?: screen.length
        val timed = if (click.messageAt != null) eligible.filter { it.at != null }.flatMap { point ->
            anchors.getValue(point).flatMap { anchor ->
                val positions = positions(anchor)
                val next = lowerBound(positions, at)
                listOfNotNull(positions.getOrNull(next - 1), positions.getOrNull(next)).map { Match(point, it) }
            }
        } else emptyList()
        val usable = if (timed.isNotEmpty()) timed else (aligned + unambiguous).distinct()
        val nearest = usable.minWithOrNull(compareBy<Match> { abs(rowAt(it.offset) - click.row) }
            .thenBy { match -> click.messageAt?.let { at -> match.point.at?.let { abs(it - at) } ?: Long.MAX_VALUE } ?: 0L })
            ?: error("Could not locate a completed conversation boundary near this text.")
        return nearest.point
    }

    /** Creates independent native history before the source run is shelved. */
    fun create(point: ConversationBranchPoint, binary: Path? = null): String? {
        if (point.boundary == null) return null
        return when (point.account.provider) {
            Provider.Anthropic -> copyClaude(point)
            Provider.OpenAI -> CodexBranch.fork(point, binary ?: CliTools.locate(Provider.OpenAI)
                ?: error("Codex is not installed."))
            Provider.Antigravity -> AntigravityBranch.create(point)
        }
    }

    private fun copyClaude(point: ConversationBranchPoint): String {
        val id = UUID.randomUUID().toString()
        // The live source tailer must not mistake the newly published file for its own /clear.
        iondrive.nop.agent.transcript.LiveTranscripts.claim(id, "branch-$id")
        val target = point.transcript.resolveSibling("$id.jsonl")
        val temporary = Files.createTempFile(point.transcript.parent, ".branch-", ".jsonl")
        OwnerOnly.tighten(temporary)
        try {
            var found = false
            Files.newBufferedWriter(temporary).use { output ->
                Files.newBufferedReader(point.transcript).useLines { lines ->
                    for (line in lines) {
                        val record = json.parseToJsonElement(line).obj() ?: continue
                        if (record["isSidechain"].bool()) continue
                        val copy = JsonObject(record + mapOf("sessionId" to JsonPrimitive(id)) +
                            if (record.containsKey("session_id")) mapOf("session_id" to JsonPrimitive(id)) else emptyMap())
                        output.appendLine(copy.toString())
                        if (record["uuid"].str() == point.boundary) { found = true; break }
                    }
                }
            }
            check(found) { "The selected conversation boundary is no longer available." }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE)
        } finally { Files.deleteIfExists(temporary) }
        return id
    }
}

/** A short-lived native backend forks saved history without making a model request. */
internal object CodexBranch {
    fun fork(point: ConversationBranchPoint, binary: Path): String {
        val process = ProcessBuilder(binary.toString(), "app-server")
            .redirectError(ProcessBuilder.Redirect.DISCARD).apply {
                val clean = iondrive.nop.terminal.InheritedEnvironment.of(environment())
                environment().clear(); environment().putAll(clean)
                environment()["HOME"] = point.account.home
                environment()["CODEX_HOME"] = point.account.homePath.resolve(".codex").toString()
            }.start()
        val task = FutureTask {
            val input = process.inputStream.bufferedReader()
            val output = process.outputStream.bufferedWriter()
            var serial = 0
            fun request(method: String, params: JsonObject): JsonObject {
                val id = ++serial
                output.appendLine(buildJsonObject { put("id", id); put("method", method); put("params", params) }.toString()); output.flush()
                while (true) {
                    val reply = Json.parseToJsonElement(input.readLine() ?: error("Codex closed the branch request.")).obj() ?: continue
                    if (reply["id"].str() != id.toString() || reply["method"] != null) continue
                    reply["error"].obj()?.let { error(it["message"].str() ?: "Codex refused to branch this conversation.") }
                    return reply["result"].obj() ?: error("Codex returned no branch.")
                }
            }
            request("initialize", buildJsonObject {
                put("clientInfo", buildJsonObject { put("name", "nop"); put("version", "1.0") })
                put("capabilities", buildJsonObject { put("experimentalApi", true) })
            })
            output.appendLine("{\"method\":\"initialized\"}"); output.flush()
            val fork = request("thread/fork", buildJsonObject {
                put("threadId", point.conversation); put("lastTurnId", point.boundary)
            })["thread"].obj() ?: error("Codex returned no branch.")
            val id = fork["id"].str() ?: error("Codex returned no branch identifier.")
            check(id != point.conversation) { "Codex did not create an independent branch." }
            val turns = fork["turns"].arr()
            if (turns?.lastOrNull()?.obj()?.get("id").str() != point.boundary) {
                runCatching { request("thread/archive", buildJsonObject { put("threadId", id) }) }
                error("This Codex version could not verify the selected branch boundary. Update Codex and try again.")
            }
            id
        }
        val worker = thread(isDaemon = true, name = "conversation-fork") { task.run() }
        try { return task.get(Duration.ofSeconds(60).toMillis(), TimeUnit.MILLISECONDS) }
        finally {
            process.destroy()
            if (!process.waitFor(500, TimeUnit.MILLISECONDS)) process.destroyForcibly()
            task.cancel(true); worker.join(500)
        }
    }
}
