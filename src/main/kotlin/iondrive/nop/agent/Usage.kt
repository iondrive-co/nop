package iondrive.nop.agent

import iondrive.nop.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** One rate-limit window: how much of it is spent, and when it rolls over. */
data class UsageWindow(val percent: Double, val resetsAt: Instant?) {
    /** "3h 12m" until the window resets, or null when nothing said when that is. */
    fun eta(now: Instant = Instant.now()): String? {
        val at = resetsAt ?: return null
        val seconds = Duration.between(now, at).seconds.coerceAtLeast(0)
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
    }
}

/**
 * What one account's quota looks like right now.
 *
 * [asOf] is when the reading was *taken*, which for Claude is now and for Codex is whenever that
 * account last ran a session. The distinction matters enough to show: a Codex reading is a snapshot
 * left behind by the CLI, not an answer to a question nop asked.
 */
data class UsageReading(
    val session: UsageWindow?,
    val weekly: UsageWindow?,
    val asOf: Instant?,
    /** Set when the reading could not be taken at all — logged out, offline, never run. */
    val unavailable: String? = null,
) {
    companion object {
        fun unavailable(why: String) = UsageReading(null, null, null, why)
    }
}

/**
 * Reads each account's remaining quota.
 *
 * The two providers could hardly be less alike here. Claude answers a question: an HTTPS GET with
 * the account's OAuth token, returning the live five-hour and seven-day windows. Codex answers
 * nothing — there is no endpoint — so its usage is scavenged from the `rate_limits` block the CLI
 * writes into its own session transcripts, which means **a Codex reading is only as fresh as that
 * account's last Codex session**. Both are surfaced with the age of the reading attached, because
 * a stale 4% and a live 4% are different facts.
 *
 * The Claude path also refreshes expired OAuth tokens, which is the one piece of this feature that
 * can damage something outside nop: a malformed `.credentials.json` breaks the account for the real
 * CLI too. So the write goes through a temp file and an atomic rename, the refresh token is spent
 * under a per-account lock (Anthropic's are single-use — two threads refreshing at once log a
 * working account out), and a refusal is remembered for a few minutes rather than retried on every
 * poll.
 */
object Usage {
    private val json = Json { ignoreUnknownKeys = true }

    private val http: HttpClient by lazy {
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    }

    // Anthropic's OAuth client id for Claude Code, and the endpoint that spends a refresh token.
    private const val OAUTH_CLIENT_ID = "9d1c250a-e61b-44d9-88ed-5944d1962f5e"
    private const val OAUTH_TOKEN_URL = "https://platform.claude.com/v1/oauth/token"
    private const val USAGE_URL = "https://api.anthropic.com/api/oauth/usage"
    private const val MODELS_URL = "https://api.anthropic.com/v1/models?limit=100"
    private const val OAUTH_BETA = "oauth-2025-04-20"
    private const val USER_AGENT = "claude-code/2.1.270"

    /**
     * A refused refresh means the account is signed out until the user signs in again, so retrying
     * it on every poll only adds latency to a state only they can fix.
     */
    private const val REFUSAL_TTL_MS = 5 * 60 * 1000L

    private val refusedAt = ConcurrentHashMap<String, Long>()
    private val refreshLocks = ConcurrentHashMap<String, Any>()

    /** Forgets remembered refusals — after a fresh login, and in tests. */
    fun clearAuthCache() {
        refusedAt.clear()
    }

    /** Blocking; call it off the UI thread. */
    fun read(account: Account): UsageReading = when (account.provider) {
        Provider.Anthropic -> readClaude(account)
        Provider.OpenAI -> readCodex(account)
    }

    /** Whether the account's credentials still work — not merely whether the file is there. */
    fun signedIn(account: Account): Boolean = when (account.provider) {
        // An expired access token is the normal state for an account idle a few hours; the CLI
        // refreshes it on use. Signed out is when the refresh itself is refused, which is the only
        // part the user can do anything about.
        Provider.Anthropic -> claudeToken(account) != null
        Provider.OpenAI -> codexAuth(account) != null
    }

    // ── Claude ──

    private fun readClaude(account: Account): UsageReading {
        val token = claudeToken(account) ?: return UsageReading.unavailable("not signed in")
        val body = get(USAGE_URL, token, mapOf("anthropic-beta" to OAUTH_BETA))
            ?: return UsageReading.unavailable("no answer from the usage API")
        val data = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return UsageReading.unavailable("unreadable usage response")
        return UsageReading(
            session = claudeWindow(data["five_hour"]),
            weekly = claudeWindow(data["seven_day"]),
            asOf = Instant.now(),
        )
    }

    private fun claudeWindow(element: kotlinx.serialization.json.JsonElement?): UsageWindow? {
        val obj = element.obj() ?: return null
        val raw = obj["utilization"].double() ?: return null
        val resets = obj["resets_at"].str()?.let(::parseInstant)
        return UsageWindow(normalizePercent(raw), resets)
    }

    /**
     * The API returns either a fraction (0..1) or an outright percentage, so anything below 1.0 is
     * scaled. 1.0 itself is ambiguous and treated as 1%: real full usage produces a quota error
     * rather than a tidy number.
     */
    internal fun normalizePercent(value: Double): Double {
        if (value.isNaN() || value.isInfinite()) return 0.0
        val scaled = if (value >= 0.0 && value < 1.0) value * 100.0 else value
        return scaled.coerceIn(0.0, 100.0)
    }

    /**
     * Current Claude model ids for this account, straight from the Models API, so the picker offers
     * what the account may actually use instead of a list baked into nop to go stale.
     */
    fun discoverClaudeModels(account: Account): List<String> {
        if (account.provider != Provider.Anthropic) return account.provider.fallbackModels
        val token = claudeToken(account) ?: return emptyList()
        val body = get(MODELS_URL, token, mapOf("anthropic-version" to "2023-06-01", "anthropic-beta" to OAUTH_BETA))
            ?: return emptyList()
        return runCatching {
            json.parseToJsonElement(body).jsonObject["data"]!!.jsonArray
                .mapNotNull { it.jsonObject["id"].str() }
        }.getOrDefault(emptyList())
    }

    /**
     * A usable access token for [account], refreshing an expired one and writing the new one back.
     * Null when the account has no credentials, or its refresh was refused.
     */
    internal fun claudeToken(account: Account): String? {
        val file = account.credentialFile
        val oauth = readOauth(file) ?: return null
        val token = oauth["accessToken"].str()?.takeIf { it.isNotBlank() } ?: return null
        val expiresAt = oauth["expiresAt"].long() ?: 0L
        if (expiresAt > System.currentTimeMillis()) return token

        synchronized(refreshLocks.computeIfAbsent(file.toString()) { Any() }) {
            // Another thread may have refreshed while we waited for the lock. Anthropic's refresh
            // tokens are single-use, so spending one twice signs a working account out.
            val current = readOauth(file)
            val fresh = current?.get("accessToken").str()
            val currentExpiry = current?.get("expiresAt").long() ?: 0L
            if (fresh != null && currentExpiry > System.currentTimeMillis()) return fresh

            val refusal = refusedAt[file.toString()]
            if (refusal != null && System.currentTimeMillis() - refusal < REFUSAL_TTL_MS) return null

            val refreshed = refreshClaudeToken(file, current ?: oauth)
            if (refreshed != null) {
                refusedAt.remove(file.toString())
                return refreshed
            }
            refusedAt[file.toString()] = System.currentTimeMillis()
            return null
        }
    }

    private fun readOauth(file: Path): JsonObject? = runCatching {
        json.parseToJsonElement(Files.readString(file)).jsonObject["claudeAiOauth"]?.jsonObject
    }.getOrNull()

    /**
     * Spends the refresh token and writes the new one back into the credentials file.
     *
     * The write is the dangerous part — this file belongs to the real CLI as much as to nop — so it
     * re-reads the whole document, edits only the three fields it owns, and lands through a temp
     * file and an atomic rename. A crash mid-write then loses nothing.
     */
    private fun refreshClaudeToken(file: Path, oauth: JsonObject): String? {
        val refreshToken = oauth["refreshToken"].str()?.takeIf { it.isNotBlank() }
            ?: return null
        val form = listOf(
            "grant_type" to "refresh_token",
            "refresh_token" to refreshToken,
            "client_id" to OAUTH_CLIENT_ID,
        ).joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, StandardCharsets.UTF_8)}" }

        val request = HttpRequest.newBuilder(URI.create(OAUTH_TOKEN_URL))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .timeout(Duration.ofSeconds(10))
            .POST(HttpRequest.BodyPublishers.ofString(form))
            .build()
        val body = send(request) ?: return null

        val token = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        return mergeRefreshedToken(file, token)
    }

    /**
     * Folds a token-endpoint response into the credentials file and returns the new access token.
     *
     * Split out from the HTTP call because this is the half that can do damage: `.credentials.json`
     * belongs to the real CLI too, so the rewrite has to preserve every key it doesn't own — other
     * top-level sections, and the scopes and account fields inside `claudeAiOauth` — and land whole
     * or not at all. Returns null when the response carried no usable token.
     */
    internal fun mergeRefreshedToken(file: Path, token: JsonObject): String? {
        val access = token["access_token"].str()?.takeIf { it.isNotBlank() }
            ?: return null

        val written = runCatching {
            val whole = json.parseToJsonElement(Files.readString(file)).jsonObject
            val existing = whole["claudeAiOauth"]?.jsonObject ?: JsonObject(emptyMap())
            val updated = buildJsonObject {
                existing.forEach { (k, v) -> put(k, v) }
                put("accessToken", access)
                token["refresh_token"].str()?.let { put("refreshToken", it) }
                token["expires_in"].long()?.let {
                    put("expiresAt", System.currentTimeMillis() + it * 1000)
                }
            }
            val next = buildJsonObject {
                whole.forEach { (k, v) -> if (k != "claudeAiOauth") put(k, v) }
                put("claudeAiOauth", updated)
            }
            writeAtomically(file, json.encodeToString(JsonObject.serializer(), next))
        }
        if (written.isFailure) {
            // The token is spent either way. Returning it lets this poll succeed; the next one
            // re-reads the unchanged file, finds it expired, and tries again.
            Log.warn("could not write the refreshed Claude token to $file: ${written.exceptionOrNull()}")
        }
        return access
    }

    private fun writeAtomically(file: Path, content: String) {
        val tmp = Files.createTempFile(file.parent, ".credentials", ".tmp")
        Files.writeString(tmp, content)
        runCatching { Files.setPosixFilePermissions(tmp, Files.getPosixFilePermissions(file)) }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    // ── Codex ──

    /**
     * The freshest populated `rate_limits` snapshot in this account's session transcripts.
     *
     * Not an API call: Codex publishes no usage endpoint, so the only record of what the account
     * has spent is what the CLI itself wrote down mid-session. The upside is that a *running*
     * session updates it live, since the tailer is already reading that very file.
     */
    private fun readCodex(account: Account): UsageReading {
        // Sign-in first, and before looking at a single transcript. A home that was abandoned still
        // has its old rollouts in it, and reading those reported a tidy 0% for an account that
        // cannot run at all — which is the one reading worse than none, because it says the week is
        // untouched when the truth is that nop has nothing to ask.
        if (codexAuth(account) == null) return UsageReading.unavailable("not signed in")

        val sessions = account.homePath.resolve(".codex").resolve("sessions")
        if (!Files.isDirectory(sessions)) return UsageReading.unavailable("no sessions yet")

        val files = runCatching {
            Files.walk(sessions).use { stream ->
                stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".jsonl") }
                    .toList()
            }
        }.getOrDefault(emptyList())
            .sortedByDescending { runCatching { Files.getLastModifiedTime(it).toMillis() }.getOrDefault(0L) }

        for (file in files) {
            val limits = latestRateLimits(file) ?: continue
            val (session, weekly) = codexWindows(limits)
            val asOf = runCatching { Instant.ofEpochMilli(Files.getLastModifiedTime(file).toMillis()) }.getOrNull()
            return UsageReading(session, weekly, asOf)
        }
        return UsageReading.unavailable("no usage recorded yet")
    }

    /**
     * The last `token_count` snapshot in [file] that carries real window data.
     *
     * A trivial turn can report a snapshot whose windows are all null — a different limit bucket
     * that says nothing about the five-hour or weekly pools — and taking that would clobber a good
     * reading with a blank one.
     */
    internal fun latestRateLimits(file: Path): JsonObject? {
        var found: JsonObject? = null
        runCatching {
            Files.newBufferedReader(file).use { reader ->
                reader.lineSequence().forEach { line ->
                    if ("rate_limits" !in line) return@forEach
                    val record = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull()
                        ?: return@forEach
                    if (record["type"].str() != "event_msg") return@forEach
                    val payload = record["payload"].obj() ?: return@forEach
                    if (payload["type"].str() != "token_count") return@forEach
                    val limits = payload["rate_limits"].obj() ?: return@forEach
                    if (limits["primary"] is JsonObject || limits["secondary"] is JsonObject) found = limits
                }
            }
        }
        return found
    }

    /**
     * Sorts a snapshot's two slots into (session, weekly).
     *
     * The slot names are not roles. A team-plan account reports its seven-day window in `primary`
     * with `secondary` empty, so reading `primary` as the session window puts a weekly figure — and
     * a reset a week away — in the session row. `window_minutes` is what actually says which is
     * which, and a window without one cannot be placed at all.
     */
    internal fun codexWindows(limits: JsonObject): Pair<UsageWindow?, UsageWindow?> {
        val windows = listOf("primary", "secondary").mapNotNull { slot ->
            val w = limits[slot].obj() ?: return@mapNotNull null
            val minutes = w["window_minutes"].long() ?: return@mapNotNull null
            minutes to w
        }
        fun window(w: JsonObject): UsageWindow? {
            val used = w["used_percent"].double() ?: return null
            val resets = w["resets_at"].long()?.let(Instant::ofEpochSecond)
            // `used_percent` is a point in time. If the window has rolled over since the snapshot
            // was written, current usage in the new window is zero — reporting the old figure would
            // show a maxed-out account that has in fact been free for hours.
            val rolled = resets != null && !Instant.now().isBefore(resets)
            return UsageWindow(if (rolled) 0.0 else used.coerceIn(0.0, 100.0), resets)
        }
        val short = windows.filter { it.first <= CODEX_SESSION_MAX_MINUTES }.minByOrNull { it.first }
        val long = windows.filter { it.first > CODEX_SESSION_MAX_MINUTES }.maxByOrNull { it.first }
        return short?.second?.let(::window) to long?.second?.let(::window)
    }

    /** Longer than this and it is a weekly-style pool, not the rolling session one. */
    private const val CODEX_SESSION_MAX_MINUTES = 24L * 60

    /** The account's Codex auth document, or null when it has never been signed in. */
    private fun codexAuth(account: Account): JsonObject? = runCatching {
        json.parseToJsonElement(Files.readString(account.credentialFile)).jsonObject
            .takeIf { it["tokens"].obj()?.get("access_token") != null }
    }.getOrNull()

    // ── HTTP ──

    private fun get(url: String, token: String, headers: Map<String, String>): String? {
        val builder = HttpRequest.newBuilder(URI.create(url))
            .header("Authorization", "Bearer $token")
            .header("User-Agent", USER_AGENT)
            .timeout(Duration.ofSeconds(10))
            .GET()
        headers.forEach { (k, v) -> builder.header(k, v) }
        return send(builder.build())
    }

    private fun send(request: HttpRequest): String? = runCatching {
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() == 200) response.body() else null
    }.getOrNull()

    private fun parseInstant(value: String): Instant? =
        runCatching { Instant.parse(value.replace("+00:00", "Z")) }.getOrNull()
}
