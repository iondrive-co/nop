package iondrive.nop.agent

import iondrive.nop.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/** Time scope for the usage breakdown. */
enum class UsageScope(val label: String, val duration: Duration) {
    Session5h("Active session", Duration.ofHours(5)),
    Weekly7d("Current week", Duration.ofDays(7)),
}

/** Percentage share and token count for a specific model tier (e.g. GPT-6 Astra, GPT-6.1 Sol). */
data class ModelShare(
    val model: String,
    val displayName: String,
    val tokens: Long,
    val percent: Double,
)

/** One task or session that consumed quota in this window. */
data class SessionBreakdownItem(
    val sessionId: String,
    val title: String,
    val projectPath: String,
    val tokens: Long,
    val steps: Int,
    val percent: Double,
    val models: List<String>,
    val lastActiveAt: Instant,
)

/** Usage metrics for one period (either the active quota window or historical activity). */
data class PeriodBreakdown(
    val totalTokens: Long,
    val totalSteps: Int,
    val models: List<ModelShare>,
    val sessions: List<SessionBreakdownItem>,
)

/** Complete usage breakdown for one account in the selected time scope. */
data class AccountBreakdown(
    val account: Account,
    val reading: UsageReading?,
    val windowStart: Instant,
    val resetsAt: Instant?,
    val currentPeriod: PeriodBreakdown,
    val historicalPeriod: PeriodBreakdown,
) {
    val totalTokens: Long get() = currentPeriod.totalTokens
    val totalSteps: Int get() = currentPeriod.totalSteps
    val models: List<ModelShare> get() = currentPeriod.models
    val sessions: List<SessionBreakdownItem> get() = currentPeriod.sessions
}

/**
 * Aggregates turn-by-turn metrics across nop session logs and native transcripts to reveal
 * which agent, session and model consumed what portion of an account's quota.
 *
 * Clearly separates activity in the active quota window (since the last reset) from historical
 * activity that occurred before the reset (up to 30 days prior).
 */
object UsageBreakdown {
    private val json = Json { ignoreUnknownKeys = true }

    /** Determines the start of the current quota period for [account] based on vendor reset timing. */
    fun windowStartFor(
        account: Account,
        reading: UsageReading?,
        scope: UsageScope,
        now: Instant = Instant.now(),
    ): Instant {
        val window = when (scope) {
            UsageScope.Session5h -> reading?.session
            UsageScope.Weekly7d -> reading?.weekly
        }
        val defaultSpan = scope.duration
        val resetsAt = window?.resetsAt
        val length = window?.length ?: defaultSpan

        if (resetsAt != null) {
            val calculated = if (now.isAfter(resetsAt)) {
                resetsAt
            } else {
                resetsAt.minus(length)
            }
            return if (calculated.isAfter(now)) now.minus(defaultSpan) else calculated
        }
        return now.minus(defaultSpan)
    }

    /** Formats a window start timestamp into a human-friendly string (e.g. "Fri, Oct 2 at 9:15 AM"). */
    fun formatPeriodStart(start: Instant, now: Instant = Instant.now()): String {
        val zone = ZoneId.systemDefault()
        val startDt = start.atZone(zone)
        val nowDt = now.atZone(zone)
        val timeFormatter = DateTimeFormatter.ofPattern("h:mm a")
        val dateFormatter = DateTimeFormatter.ofPattern("EEE, MMM d 'at' h:mm a")

        val daysDiff = Duration.between(start, now).toDays()
        return if (daysDiff < 1 && startDt.dayOfYear == nowDt.dayOfYear) {
            "today at " + startDt.format(timeFormatter)
        } else if (daysDiff < 2 && startDt.dayOfYear == nowDt.minusDays(1).dayOfYear) {
            "yesterday at " + startDt.format(timeFormatter)
        } else {
            startDt.format(dateFormatter)
        }
    }

    /** Computes the breakdown for all [accounts] within [scope]. */
    fun compute(
        accounts: List<Account>,
        readings: Map<String, UsageReading>,
        scope: UsageScope,
        now: Instant = Instant.now(),
    ): Map<String, AccountBreakdown> {
        val historyDuration = Duration.ofDays(30)
        val historyCutoff = now.minus(historyDuration)
        val historyCutoffMs = historyCutoff.toEpochMilli()
        val historyCutoffSec = historyCutoff.epochSecond

        val windowStarts = accounts.associate { acc ->
            acc.name to windowStartFor(acc, readings[acc.name], scope, now)
        }
        val windowStartsMs = windowStarts.mapValues { it.value.toEpochMilli() }
        val windowStartsSec = windowStarts.mapValues { it.value.epochSecond }

        val currentStatsByAccount = mutableMapOf<String, MutableMap<String, MutableSessionStat>>()
        val historicalStatsByAccount = mutableMapOf<String, MutableMap<String, MutableSessionStat>>()
        val seenNativeSessionIds = mutableSetOf<String>()

        // 1. Scan nop sessions directory (~/.local/share/nop/agent/sessions)
        val nopSessionsDir = EventLog.sessionsDir()
        if (Files.isDirectory(nopSessionsDir)) {
            val sessionFiles = runCatching {
                Files.list(nopSessionsDir).use { stream ->
                    stream.filter {
                        it.fileName.toString().endsWith(".jsonl") &&
                            runCatching { Files.getLastModifiedTime(it).toMillis() }.getOrDefault(0L) >= historyCutoffMs
                    }.toList()
                }
            }.getOrDefault(emptyList())

            for (file in sessionFiles) {
                val sid = file.fileName.toString().removeSuffix(".jsonl")
                parseNopSessionFile(
                    file = file,
                    sessionId = sid,
                    windowStartsMs = windowStartsMs,
                    historyCutoffMs = historyCutoffMs,
                    currentStats = currentStatsByAccount,
                    historicalStats = historicalStatsByAccount,
                    seenNativeIds = seenNativeSessionIds,
                )
            }
        }

        // 2. Scan native transcripts for Claude and Codex accounts to capture external/delegated workers
        for (account in accounts) {
            val startMs = windowStartsMs[account.name] ?: (now.toEpochMilli() - scope.duration.toMillis())
            when (account.provider) {
                Provider.Anthropic -> scanNativeClaudeTranscripts(
                    account = account,
                    windowStartMs = startMs,
                    historyCutoffMs = historyCutoffMs,
                    currentStats = currentStatsByAccount,
                    historicalStats = historicalStatsByAccount,
                    seenNativeIds = seenNativeSessionIds,
                )
                Provider.OpenAI -> scanNativeCodexRollouts(
                    account = account,
                    windowStartMs = startMs,
                    historyCutoffMs = historyCutoffMs,
                    currentStats = currentStatsByAccount,
                    historicalStats = historicalStatsByAccount,
                    seenNativeIds = seenNativeSessionIds,
                )
                Provider.Antigravity -> Unit // Handled via conversation_summaries.db
            }
        }

        // 3. For Antigravity accounts, query conversation_summaries.db
        val antigravitySummaries = mutableMapOf<String, Pair<List<SessionBreakdownItem>, List<SessionBreakdownItem>>>()
        for (account in accounts.filter { it.provider == Provider.Antigravity }) {
            val startSec = windowStartsSec[account.name] ?: (now.epochSecond - scope.duration.seconds)
            antigravitySummaries[account.name] = queryAntigravitySummaries(account, startSec, historyCutoffSec)
        }

        // 4. Assemble AccountBreakdown for each account
        val result = mutableMapOf<String, AccountBreakdown>()
        for (account in accounts) {
            val reading = readings[account.name]
            val winStart = windowStarts[account.name] ?: now.minus(scope.duration)
            val window = when (scope) {
                UsageScope.Session5h -> reading?.session
                UsageScope.Weekly7d -> reading?.weekly
            }

            if (account.provider == Provider.Antigravity) {
                val (currentItems, histItems) = antigravitySummaries[account.name]
                    ?: (emptyList<SessionBreakdownItem>() to emptyList())
                val currentPeriod = buildAntigravityPeriod(account, currentItems)
                val histPeriod = buildAntigravityPeriod(account, histItems)

                result[account.name] = AccountBreakdown(
                    account = account,
                    reading = reading,
                    windowStart = winStart,
                    resetsAt = window?.resetsAt,
                    currentPeriod = currentPeriod,
                    historicalPeriod = histPeriod,
                )
            } else {
                val currentMap = currentStatsByAccount[account.name].orEmpty()
                val histMap = historicalStatsByAccount[account.name].orEmpty()

                result[account.name] = AccountBreakdown(
                    account = account,
                    reading = reading,
                    windowStart = winStart,
                    resetsAt = window?.resetsAt,
                    currentPeriod = buildPeriodBreakdown(currentMap),
                    historicalPeriod = buildPeriodBreakdown(histMap),
                )
            }
        }

        return result
    }

    private class MutableSessionStat(
        val sessionId: String,
        var projectPath: String? = null,
        var title: String? = null,
        var firstPrompt: String? = null,
        var totalTokens: Long = 0L,
        var turnCount: Int = 0,
        var lastActiveAtMs: Long = 0L,
        val modelTokens: MutableMap<String, Long> = mutableMapOf(),
    )

    private fun parseNopSessionFile(
        file: Path,
        sessionId: String,
        windowStartsMs: Map<String, Long>,
        historyCutoffMs: Long,
        currentStats: MutableMap<String, MutableMap<String, MutableSessionStat>>,
        historicalStats: MutableMap<String, MutableMap<String, MutableSessionStat>>,
        seenNativeIds: MutableSet<String>,
    ) {
        var currentAccount: String? = null
        var currentProject: String? = null
        var sessionTitle: String? = null
        var firstPrompt: String? = null

        runCatching {
            Files.newBufferedReader(file).use { reader ->
                for (line in reader.lineSequence()) {
                    if (line.isBlank()) continue

                    if ("\"session_started\"" in line) {
                        runCatching {
                            val obj = json.parseToJsonElement(line).jsonObject
                            if (obj["type"]?.jsonPrimitive?.content == "session_started") {
                                currentProject = obj["projectPath"]?.jsonPrimitive?.content
                            }
                        }
                    } else if ("\"run_started\"" in line) {
                        runCatching {
                            val obj = json.parseToJsonElement(line).jsonObject
                            if (obj["type"]?.jsonPrimitive?.content == "run_started") {
                                currentAccount = obj["account"]?.jsonPrimitive?.content
                                obj["nativeSessionId"]?.jsonPrimitive?.content?.let { seenNativeIds.add(it) }
                            }
                        }
                    } else if ("\"session_titled\"" in line) {
                        runCatching {
                            val obj = json.parseToJsonElement(line).jsonObject
                            if (obj["type"]?.jsonPrimitive?.content == "session_titled") {
                                sessionTitle = obj["title"]?.jsonPrimitive?.content
                            }
                        }
                    } else if ("\"user_message\"" in line && firstPrompt == null) {
                        runCatching {
                            val obj = json.parseToJsonElement(line).jsonObject
                            if (obj["type"]?.jsonPrimitive?.content == "user_message") {
                                val text = obj["text"]?.jsonPrimitive?.content.orEmpty()
                                firstPrompt = text.lineSequence().firstOrNull { it.isNotBlank() }?.take(80)
                            }
                        }
                    } else if ("\"assistant_message\"" in line) {
                        val acc = currentAccount ?: continue
                        runCatching {
                            val obj = json.parseToJsonElement(line).jsonObject
                            if (obj["type"]?.jsonPrimitive?.content == "assistant_message") {
                                val at = obj["at"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                                if (at >= historyCutoffMs) {
                                    val usageObj = obj["usage"]?.jsonObject
                                    val inTok = usageObj?.get("inputTokens")?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                                    val outTok = usageObj?.get("outputTokens")?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                                    val readTok = usageObj?.get("cacheReadTokens")?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                                    val createTok = usageObj?.get("cacheCreationTokens")?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                                    val turnTok = inTok + outTok + readTok + createTok
                                    val model = obj["model"]?.jsonPrimitive?.content ?: "unknown"

                                    val winStart = windowStartsMs[acc] ?: historyCutoffMs
                                    val targetMap = if (at >= winStart) currentStats else historicalStats
                                    val accountMap = targetMap.getOrPut(acc) { mutableMapOf() }
                                    val stat = accountMap.getOrPut(sessionId) {
                                        MutableSessionStat(
                                            sessionId = sessionId,
                                            projectPath = currentProject,
                                            title = sessionTitle ?: firstPrompt,
                                        )
                                    }
                                    if (sessionTitle != null && stat.title == null) stat.title = sessionTitle
                                    if (currentProject != null && stat.projectPath == null) stat.projectPath = currentProject
                                    stat.totalTokens += turnTok
                                    stat.turnCount += 1
                                    stat.modelTokens[model] = (stat.modelTokens[model] ?: 0L) + turnTok
                                    if (at > stat.lastActiveAtMs) stat.lastActiveAtMs = at
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun scanNativeClaudeTranscripts(
        account: Account,
        windowStartMs: Long,
        historyCutoffMs: Long,
        currentStats: MutableMap<String, MutableMap<String, MutableSessionStat>>,
        historicalStats: MutableMap<String, MutableMap<String, MutableSessionStat>>,
        seenNativeIds: MutableSet<String>,
    ) {
        val projectsDir = account.homePath.resolve("projects")
        if (!Files.isDirectory(projectsDir)) return

        val files = runCatching {
            Files.walk(projectsDir, 2).use { stream ->
                stream.filter {
                    it.fileName.toString().endsWith(".jsonl") &&
                        runCatching { Files.getLastModifiedTime(it).toMillis() }.getOrDefault(0L) >= historyCutoffMs
                }.toList()
            }
        }.getOrDefault(emptyList())

        for (file in files) {
            val nativeId = file.fileName.toString().removeSuffix(".jsonl")
            if (nativeId in seenNativeIds) continue

            var projectPath: String? = null
            var firstPrompt: String? = null

            var currentTokens = 0L
            var currentTurns = 0
            var currentLastActive = 0L
            val currentModelMap = mutableMapOf<String, Long>()

            var histTokens = 0L
            var histTurns = 0
            var histLastActive = 0L
            val histModelMap = mutableMapOf<String, Long>()

            runCatching {
                Files.newBufferedReader(file).use { reader ->
                    for (line in reader.lineSequence()) {
                        if ("\"type\":\"assistant\"" in line || "\"type\": \"assistant\"" in line) {
                            val obj = json.parseToJsonElement(line).jsonObject
                            val tsStr = obj["timestamp"]?.jsonPrimitive?.content
                            val atMs = tsStr?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L
                            if (atMs >= historyCutoffMs) {
                                val msg = obj["message"]?.jsonObject
                                val model = msg?.get("model")?.jsonPrimitive?.content ?: "claude"
                                val usage = msg?.get("usage")?.jsonObject
                                val inTok = usage?.get("input_tokens")?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                                val outTok = usage?.get("output_tokens")?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                                val readTok = usage?.get("cache_read_input_tokens")?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                                val createTok = usage?.get("cache_creation_input_tokens")?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                                val tok = inTok + outTok + readTok + createTok

                                if (projectPath == null) projectPath = obj["cwd"]?.jsonPrimitive?.content

                                if (atMs >= windowStartMs) {
                                    currentTokens += tok
                                    currentTurns += 1
                                    currentModelMap[model] = (currentModelMap[model] ?: 0L) + tok
                                    if (atMs > currentLastActive) currentLastActive = atMs
                                } else {
                                    histTokens += tok
                                    histTurns += 1
                                    histModelMap[model] = (histModelMap[model] ?: 0L) + tok
                                    if (atMs > histLastActive) histLastActive = atMs
                                }
                            }
                        } else if (firstPrompt == null && ("\"type\":\"user\"" in line || "\"type\": \"user\"" in line)) {
                            val obj = json.parseToJsonElement(line).jsonObject
                            if (projectPath == null) projectPath = obj["cwd"]?.jsonPrimitive?.content
                            val msg = obj["message"]?.jsonObject
                            val content = msg?.get("content")?.jsonPrimitive?.content.orEmpty()
                            firstPrompt = content.lineSequence().firstOrNull { it.isNotBlank() }?.take(80)
                        }
                    }
                }
            }

            val title = firstPrompt ?: nativeId.take(8)

            if (currentTokens > 0) {
                val accountMap = currentStats.getOrPut(account.name) { mutableMapOf() }
                accountMap[nativeId] = MutableSessionStat(
                    sessionId = nativeId,
                    projectPath = projectPath,
                    title = title,
                    totalTokens = currentTokens,
                    turnCount = currentTurns,
                    lastActiveAtMs = currentLastActive,
                    modelTokens = currentModelMap,
                )
            }

            if (histTokens > 0) {
                val accountMap = historicalStats.getOrPut(account.name) { mutableMapOf() }
                accountMap[nativeId] = MutableSessionStat(
                    sessionId = nativeId,
                    projectPath = projectPath,
                    title = title,
                    totalTokens = histTokens,
                    turnCount = histTurns,
                    lastActiveAtMs = histLastActive,
                    modelTokens = histModelMap,
                )
            }
        }
    }

    private fun scanNativeCodexRollouts(
        account: Account,
        windowStartMs: Long,
        historyCutoffMs: Long,
        currentStats: MutableMap<String, MutableMap<String, MutableSessionStat>>,
        historicalStats: MutableMap<String, MutableMap<String, MutableSessionStat>>,
        seenNativeIds: MutableSet<String>,
    ) {
        val sessionsRoot = account.homePath.resolve(".codex").resolve("sessions")
        if (!Files.isDirectory(sessionsRoot)) return

        val files = runCatching {
            Files.walk(sessionsRoot, 4).use { stream ->
                stream.filter {
                    it.fileName.toString().startsWith("rollout-") &&
                        it.fileName.toString().endsWith(".jsonl") &&
                        runCatching { Files.getLastModifiedTime(it).toMillis() }.getOrDefault(0L) >= historyCutoffMs
                }.toList()
            }
        }.getOrDefault(emptyList())

        for (file in files) {
            var threadId: String? = null
            var projectPath: String? = null
            var currentModel = "codex"

            var currentTokens = 0L
            var currentTurns = 0
            var currentLastActive = 0L
            val currentModelMap = mutableMapOf<String, Long>()

            var histTokens = 0L
            var histTurns = 0
            var histLastActive = 0L
            val histModelMap = mutableMapOf<String, Long>()

            runCatching {
                Files.newBufferedReader(file).use { reader ->
                    for (line in reader.lineSequence()) {
                        if ("\"session_meta\"" in line) {
                            val obj = json.parseToJsonElement(line).jsonObject
                            val payload = obj["payload"]?.jsonObject
                            threadId = payload?.get("id")?.jsonPrimitive?.content
                            projectPath = payload?.get("cwd")?.jsonPrimitive?.content
                        } else if ("\"turn_context\"" in line) {
                            val obj = json.parseToJsonElement(line).jsonObject
                            val payload = obj["payload"]?.jsonObject
                            payload?.get("model")?.jsonPrimitive?.content?.let { currentModel = it }
                        } else if ("\"token_count\"" in line) {
                            val obj = json.parseToJsonElement(line).jsonObject
                            val tsStr = obj["timestamp"]?.jsonPrimitive?.content
                            val atMs = tsStr?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L
                            if (atMs >= historyCutoffMs) {
                                val payload = obj["payload"]?.jsonObject
                                val lastUsage = payload?.get("info")?.jsonObject?.get("last_token_usage")?.jsonObject
                                val inTok = lastUsage?.get("input_tokens")?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                                val outTok = lastUsage?.get("output_tokens")?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                                val cachedTok = lastUsage?.get("cached_input_tokens")?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                                val tok = inTok + outTok + cachedTok

                                if (atMs >= windowStartMs) {
                                    currentTokens += tok
                                    currentTurns += 1
                                    currentModelMap[currentModel] = (currentModelMap[currentModel] ?: 0L) + tok
                                    if (atMs > currentLastActive) currentLastActive = atMs
                                } else {
                                    histTokens += tok
                                    histTurns += 1
                                    histModelMap[currentModel] = (histModelMap[currentModel] ?: 0L) + tok
                                    if (atMs > histLastActive) histLastActive = atMs
                                }
                            }
                        }
                    }
                }
            }

            val id = threadId ?: file.fileName.toString().removePrefix("rollout-").removeSuffix(".jsonl")
            if (id !in seenNativeIds) {
                val title = "Codex task $id"
                if (currentTokens > 0) {
                    val accountMap = currentStats.getOrPut(account.name) { mutableMapOf() }
                    accountMap[id] = MutableSessionStat(
                        sessionId = id,
                        projectPath = projectPath,
                        title = title,
                        totalTokens = currentTokens,
                        turnCount = currentTurns,
                        lastActiveAtMs = currentLastActive,
                        modelTokens = currentModelMap,
                    )
                }
                if (histTokens > 0) {
                    val accountMap = historicalStats.getOrPut(account.name) { mutableMapOf() }
                    accountMap[id] = MutableSessionStat(
                        sessionId = id,
                        projectPath = projectPath,
                        title = title,
                        totalTokens = histTokens,
                        turnCount = histTurns,
                        lastActiveAtMs = histLastActive,
                        modelTokens = histModelMap,
                    )
                }
            }
        }
    }

    private fun queryAntigravitySummaries(
        account: Account,
        windowStartSec: Long,
        historyCutoffSec: Long,
    ): Pair<List<SessionBreakdownItem>, List<SessionBreakdownItem>> {
        val db = account.homePath.resolve(".gemini/antigravity-cli/conversation_summaries.db")
        if (!Files.isRegularFile(db)) return emptyList<SessionBreakdownItem>() to emptyList()

        return runCatching {
            val cmd = listOf(
                "sqlite3",
                db.toString(),
                "SELECT conversation_id, title, step_count, last_modified_time, workspace_uris FROM conversation_summaries WHERE last_modified_time >= datetime('now', '-30 days') ORDER BY last_modified_time DESC LIMIT 100;",
            )
            val process = ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor(3, TimeUnit.SECONDS)

            val current = mutableListOf<SessionBreakdownItem>()
            val historical = mutableListOf<SessionBreakdownItem>()

            for (line in output.lineSequence()) {
                val parts = line.split('|')
                if (parts.size < 5) continue
                val cid = parts[0].trim()
                val title = parts[1].trim()
                val steps = parts[2].trim().toIntOrNull() ?: 0
                val mtimeStr = parts[3].trim()
                val urisStr = parts[4].trim()

                val mtime = runCatching { Instant.parse(mtimeStr.replace(" ", "T")) }.getOrNull() ?: continue
                if (mtime.epochSecond < historyCutoffSec) continue

                var proj = "unknown"
                runCatching {
                    val arr = json.parseToJsonElement(urisStr).jsonArray
                    val lastUri = arr.lastOrNull()?.jsonPrimitive?.content.orEmpty()
                    if (lastUri.startsWith("file://")) {
                        proj = lastUri.removePrefix("file://")
                    }
                }

                val item = SessionBreakdownItem(
                    sessionId = cid,
                    title = title.ifBlank { cid.take(8) },
                    projectPath = proj,
                    tokens = 0L,
                    steps = steps,
                    percent = 0.0,
                    models = listOf(account.model ?: "gemini-3.8-flash"),
                    lastActiveAt = mtime,
                )

                if (mtime.epochSecond >= windowStartSec) {
                    current.add(item)
                } else {
                    historical.add(item)
                }
            }

            current to historical
        }.getOrDefault(emptyList<SessionBreakdownItem>() to emptyList())
    }

    private fun buildAntigravityPeriod(account: Account, items: List<SessionBreakdownItem>): PeriodBreakdown {
        val totalSteps = items.sumOf { it.steps }
        val sessionsWithPercent = items.map { item ->
            val pct = if (totalSteps > 0) (item.steps.toDouble() / totalSteps) * 100.0 else 0.0
            item.copy(percent = pct)
        }.sortedByDescending { it.steps }

        val modelName = account.model ?: "gemini-3.8-flash"
        val models = if (totalSteps > 0) {
            listOf(ModelShare(modelName, formatModelName(modelName), totalSteps.toLong(), 100.0))
        } else {
            emptyList()
        }

        return PeriodBreakdown(
            totalTokens = 0L,
            totalSteps = totalSteps,
            models = models,
            sessions = sessionsWithPercent,
        )
    }

    private fun buildPeriodBreakdown(stats: Map<String, MutableSessionStat>): PeriodBreakdown {
        val totalTokens = stats.values.sumOf { it.totalTokens }
        val totalSteps = stats.values.sumOf { it.turnCount }

        val modelTotals = mutableMapOf<String, Long>()
        for (stat in stats.values) {
            for ((m, tok) in stat.modelTokens) {
                modelTotals[m] = (modelTotals[m] ?: 0L) + tok
            }
        }
        val models = modelTotals.map { (m, tok) ->
            val pct = if (totalTokens > 0) (tok.toDouble() / totalTokens) * 100.0 else 0.0
            ModelShare(m, formatModelName(m), tok, pct)
        }.sortedByDescending { it.tokens }

        val sessions = stats.values.map { stat ->
            val pct = if (totalTokens > 0) (stat.totalTokens.toDouble() / totalTokens) * 100.0 else 0.0
            SessionBreakdownItem(
                sessionId = stat.sessionId,
                title = stat.title ?: stat.firstPrompt ?: stat.sessionId.take(8),
                projectPath = stat.projectPath ?: "unknown",
                tokens = stat.totalTokens,
                steps = stat.turnCount,
                percent = pct,
                models = stat.modelTokens.keys.toList(),
                lastActiveAt = Instant.ofEpochMilli(stat.lastActiveAtMs),
            )
        }.sortedByDescending { it.tokens }

        return PeriodBreakdown(
            totalTokens = totalTokens,
            totalSteps = totalSteps,
            models = models,
            sessions = sessions,
        )
    }

    fun formatModelName(model: String): String = when (model.lowercase()) {
        "gpt-6-astra" -> "GPT-6 Astra"
        "gpt-6.1-sol" -> "GPT-6.1 Sol"
        "gpt-6-sol" -> "GPT-6 Sol"
        "gpt-5.6-sol" -> "GPT-5.6 Sol"
        "claude-opus-5-5" -> "Claude Opus 5.5"
        "claude-fable-5-1" -> "Claude Fable 5.1"
        "claude-sonnet-4-6" -> "Claude Sonnet 4.6"
        "claude-haiku-4-5" -> "Claude Haiku 4.5"
        "gemini-3.8-flash" -> "Gemini 3.8 Flash"
        "gemini-3.8-pro" -> "Gemini 3.8 Pro"
        else -> model
    }

    fun formatTokens(tokens: Long): String = when {
        tokens >= 1_000_000_000L -> "%.1fB".format(tokens / 1_000_000_000.0)
        tokens >= 1_000_000L -> "%.1fM".format(tokens / 1_000_000.0)
        tokens >= 1_000L -> "%.1fK".format(tokens / 1_000.0)
        else -> tokens.toString()
    }
}
