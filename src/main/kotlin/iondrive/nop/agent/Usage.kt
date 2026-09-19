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

/**
 * One rate-limit window: how much of it is spent, when it rolls over, and how long it runs for.
 *
 * [length] is the window's whole span — five hours, seven days — which is the piece that turns
 * [resetsAt] into a position: spent against elapsed is the comparison that says whether a quota is
 * being burnt faster than it refills, and neither number alone can make it. It is null only when
 * the provider didn't say.
 */
data class UsageWindow(val percent: Double, val resetsAt: Instant?, val length: Duration? = null) {
    /**
     * "3h 12m" until the window resets, "5d" when that is days out, or null when nothing said when
     * it is.
     *
     * The day form is for the weekly window, which resets up to a week away: "163h 12m" is a number
     * the reader has to divide before it says anything, and to the hour is not a precision anyone
     * plans a week's work to. The session window is always inside its five hours, so it keeps the
     * minutes — those are what "wait for the reset or switch account" turns on.
     */
    fun eta(now: Instant = Instant.now()): String? {
        val at = resetsAt ?: return null
        val seconds = Duration.between(now, at).seconds.coerceAtLeast(0)
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        return when {
            hours >= HOURS_IN_DAYS -> "${Math.round(hours / 24.0)}d"
            hours > 0 -> "${hours}h ${minutes}m"
            else -> "${minutes}m"
        }
    }

    /**
     * How far through the window the clock is, 0..1, or null when its span or its reset is unknown.
     *
     * The window started [length] before it resets — providers report the end, not the beginning —
     * so this is what is left subtracted from the whole. A window whose reset has already passed
     * reads as 1.0 rather than going negative: the next poll will have rolled it over.
     */
    fun elapsed(now: Instant = Instant.now()): Double? {
        val at = resetsAt ?: return null
        val span = length?.seconds?.takeIf { it > 0 } ?: return null
        val remaining = Duration.between(now, at).seconds.coerceIn(0, span)
        return (span - remaining).toDouble() / span
    }

    private companion object {
        /** Past two days, the day count is the answer — the same line [iondrive.nop.Ago] draws. */
        const val HOURS_IN_DAYS = 48
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
    /**
     * Set when these are not a fresh answer: why the provider was not asked again or did not reply,
     * such as the usage API rate-limiting nop. The windows are the last good reading, if there was
     * one, and keep its [asOf]. A reading with no windows and only a note is still signed in. It is
     * not [unavailable], which the accounts dialog shows as signed out.
     */
    val note: String? = null,
) {
    /**
     * Whether this reading is consistent with the vendor refusing to serve the account: true when a
     * window is at or past [SPENT_PERCENT], false when every window it knows about has room, and
     * null when it cannot say.
     *
     * It exists to contradict the terminal. [QuotaWatcher] decides an account has run out by finding
     * a phrase in the CLI's output, and output is not evidence about an account — a session that is
     * reading a diff, grepping a log or writing a test about quota handling prints those phrases as
     * *content*, and killing it for that is a false positive with no undo. This is the independent
     * answer: the provider's own number for how much of the window is gone. Where it says there is
     * room, the phrase on screen was something the agent was showing, not something the vendor said.
     *
     * Null is the honest answer more often than it looks. A Codex reading is scavenged from that
     * account's last session transcript, so it can be days old and says nothing about now; so can a
     * Claude reading nop has not managed to take. An answer nobody should act on is not one to guess
     * at — null leaves the decision exactly where it was before this existed.
     */
    fun looksSpent(now: Instant = Instant.now()): Boolean? {
        val windows = current(now)?.takeIf { it.isNotEmpty() } ?: return null
        // A window whose reset has passed has rolled over since the reading was taken: whatever it
        // said then, it is empty now, and the next poll will say so.
        val worst = windows.filterNot { it.rolledOver(now) }.maxOfOrNull { it.percent } ?: return false
        return worst >= SPENT_PERCENT
    }

    /**
     * When this account can be served again, if the reading says it is spent and says when every
     * spent window resets; null otherwise.
     *
     * The latest of the spent windows' resets, since the account is out until the last of them rolls
     * over: a session window resetting in a minute is no use under a weekly one spent for three days.
     * A spent window with no reset time is null. So is a stale reading, for the same reason as in
     * [looksSpent].
     */
    fun spentUntil(now: Instant = Instant.now()): Instant? {
        val spent = current(now)?.filter { it.percent >= SPENT_PERCENT && !it.rolledOver(now) }
        if (spent.isNullOrEmpty()) return null
        return spent.map { it.resetsAt ?: return null }.max()
    }

    /** The windows, when the reading is about the present; null when it is not worth acting on. */
    private fun current(now: Instant): List<UsageWindow>? {
        if (unavailable != null) return null
        // A reading has to be about the present to contradict something happening in the present.
        val taken = asOf ?: return null
        if (Duration.between(taken, now) > FRESH_ENOUGH) return null
        return listOfNotNull(session, weekly)
    }

    private fun UsageWindow.rolledOver(now: Instant): Boolean = resetsAt?.isAfter(now) == false

    companion object {
        fun unavailable(why: String) = UsageReading(null, null, null, why)

        /**
         * How much of a window has to be gone before the vendor refusing is believable.
         *
         * Not 100. The percentage and the refusal come from different places — the usage endpoint
         * and the model's own gate — and they round and lag differently, so an account genuinely out
         * of quota can read 97%. The number only has to be high enough that an agent quoting a limit
         * message while it still has most of a window left is not mistaken for one that has none.
         */
        const val SPENT_PERCENT: Double = 95.0

        /**
         * How old a reading may be and still be worth contradicting the screen with. Longer than the
         * poll interval, so an account is not left ungoverned by one slow or failed poll, and far
         * shorter than the five-hour window it describes.
         */
        val FRESH_ENOUGH: Duration = Duration.ofMinutes(20)
    }
}

/**
 * Reads each account's remaining quota.
 *
 * The three providers could hardly be less alike here. Claude answers a question: an HTTPS GET with
 * the account's OAuth token, returning the live five-hour and seven-day windows. Codex answers
 * nothing — there is no endpoint — so its usage is scavenged from the `rate_limits` block the CLI
 * writes into its own session transcripts, which means **an OpenAI reading is only as fresh as that
 * account's last Codex session**. Antigravity has no endpoint either, but it does have a `/usage`
 * slash command, so its reading is taken by *running the account* — a CLI start of several seconds,
 * which is why it happens on the poller's thread and nowhere else (see [Antigravity]). All three are
 * surfaced with the age of the reading attached, because a stale 4% and a live 4% are different
 * facts.
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

    /**
     * Rations the asks that cost something: the Claude usage API and the Antigravity CLI start.
     *
     * Every project tab and every window polls for itself, and a project switch starts a new poll
     * at once, so a few quick switches used to send a burst of requests per account. Anthropic's
     * usage endpoint answers a burst with 429 and `Retry-After`, and nop showed that as "no answer
     * from the usage API" until the next poll. The gate stands between all of those callers and
     * the provider. See [UsageGate].
     */
    private val gate = UsageGate()

    /** Blocking; call it off the UI thread. */
    fun read(account: Account): UsageReading {
        // The screenshot's canned numbers, when it has set some — see UsageFixture.
        UsageFixture.file?.let { return UsageFixture.read(it, account) }
        return when (account.provider) {
            Provider.Anthropic -> readClaude(account)
            Provider.OpenAI -> readCodex(account)
            Provider.Antigravity -> gate.held(gateKey(account))
                ?: gate.answered(gateKey(account), Antigravity.readUsage(account))
        }
    }

    private fun gateKey(account: Account) = "${account.provider.name}:${account.home}"

    /** Whether the account's credentials still work — not merely whether the file is there. */
    fun signedIn(account: Account): Boolean = when (account.provider) {
        // An expired access token is the normal state for an account idle a few hours; the CLI
        // refreshes it on use. Signed out is when the refresh itself is refused, which is the only
        // part the user can do anything about.
        Provider.Anthropic -> claudeToken(account) != null
        Provider.OpenAI -> codexAuth(account) != null
        // The refresh token, for the same reason: the CLI spends it for a new access token on use.
        Provider.Antigravity -> Antigravity.signedIn(account)
    }

    /**
     * The models the picker should offer for [account], asked of whoever can answer.
     *
     * Claude's come from the Models API over the account's own token; Antigravity's from `agy
     * models`, which is a CLI start and so costs seconds rather than milliseconds. Codex can be
     * asked neither way and answers empty — "nothing was discovered", which is what leaves the
     * picker on [Provider.fallbackModels] rather than overwriting it with a copy of itself.
     */
    fun discoverModels(account: Account): List<String> {
        UsageFixture.file?.let { return UsageFixture.models(it, account) }
        // Kept for the life of nop once found. The poller in each project tab used to ask again
        // after every project switch: another HTTPS GET for Claude, another CLI start for Antigravity.
        val key = gateKey(account)
        discovered[key]?.let { return it }
        val now = Instant.now()
        val lastTry = modelsTriedAt[key]
        if (lastTry != null && Duration.between(lastTry, now) < UsageGate.HOLD) return emptyList()
        modelsTriedAt[key] = now
        val found = when (account.provider) {
            Provider.Anthropic -> discoverClaudeModels(account)
            Provider.Antigravity -> Antigravity.models(account)
            Provider.OpenAI -> emptyList()
        }
        if (found.isNotEmpty()) discovered[key] = found
        return found
    }

    private val discovered = ConcurrentHashMap<String, List<String>>()
    private val modelsTriedAt = ConcurrentHashMap<String, Instant>()

    // ── Claude ──

    private fun readClaude(account: Account): UsageReading {
        val key = gateKey(account)
        gate.held(key)?.let { return it }
        // Signed out is a local answer, from the credentials file, so it is not held: the first
        // poll after a sign-in should ask.
        val token = claudeToken(account) ?: return UsageReading.unavailable("not signed in")
        fun unanswered(why: String, retryAfter: Duration? = null): UsageReading {
            Log.info("usage for ${account.name}: $why" + (retryAfter?.let { " (Retry-After ${it.seconds}s)" } ?: ""))
            return gate.unanswered(key, why, retryAfter)
        }
        val answer = get(USAGE_URL, token, mapOf("anthropic-beta" to OAUTH_BETA))
            ?: return unanswered("no answer from the usage API")
        when (answer.status) {
            200 -> {}
            429 -> return unanswered("usage API rate-limited", answer.retryAfter)
            // The token was accepted for a refresh but refused here, which only signing in again
            // fixes. Held, so a dead token is not sent every poll.
            401, 403 -> return gate.answered(key, UsageReading.unavailable("usage API refused the sign-in"))
            else -> return unanswered("usage API error ${answer.status}", answer.retryAfter)
        }
        val data = runCatching { json.parseToJsonElement(answer.body).jsonObject }.getOrNull()
            ?: return unanswered("unreadable usage response")
        return gate.answered(
            key,
            UsageReading(
                // The spans are named by the fields themselves and are not in the payload, so they
                // are written down here rather than inferred from a reset that may be minutes away.
                session = claudeWindow(data["five_hour"], Duration.ofHours(5)),
                weekly = claudeWindow(data["seven_day"], Duration.ofDays(7)),
                asOf = Instant.now(),
            ),
        )
    }

    private fun claudeWindow(
        element: kotlinx.serialization.json.JsonElement?,
        length: Duration,
    ): UsageWindow? {
        val obj = element.obj() ?: return null
        val raw = obj["utilization"].double() ?: return null
        val resets = obj["resets_at"].str()?.let(::parseInstant)
        return UsageWindow(normalizePercent(raw), resets, length)
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
            ?.takeIf { it.status == 200 }?.body
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
        fun window(entry: Pair<Long, JsonObject>): UsageWindow? {
            val (minutes, w) = entry
            val used = w["used_percent"].double() ?: return null
            val resets = w["resets_at"].long()?.let(Instant::ofEpochSecond)
            // `used_percent` is a point in time. If the window has rolled over since the snapshot
            // was written, current usage in the new window is zero — reporting the old figure would
            // show a maxed-out account that has in fact been free for hours.
            val rolled = resets != null && !Instant.now().isBefore(resets)
            return UsageWindow(
                percent = if (rolled) 0.0 else used.coerceIn(0.0, 100.0),
                resetsAt = resets,
                // `window_minutes` is the span, and the same field that sorted the two slots.
                length = Duration.ofMinutes(minutes),
            )
        }
        val short = windows.filter { it.first <= CODEX_SESSION_MAX_MINUTES }.minByOrNull { it.first }
        val long = windows.filter { it.first > CODEX_SESSION_MAX_MINUTES }.maxByOrNull { it.first }
        return short?.let(::window) to long?.let(::window)
    }

    /** Longer than this and it is a weekly-style pool, not the rolling session one. */
    private const val CODEX_SESSION_MAX_MINUTES = 24L * 60

    /** The account's Codex auth document, or null when it has never been signed in. */
    private fun codexAuth(account: Account): JsonObject? = runCatching {
        json.parseToJsonElement(Files.readString(account.credentialFile)).jsonObject
            .takeIf { it["tokens"].obj()?.get("access_token") != null }
    }.getOrNull()

    // ── HTTP ──

    /** A reply from the provider, whatever its status. Null from [get] means there was none. */
    private class Answer(val status: Int, val body: String, val retryAfter: Duration?)

    private fun get(url: String, token: String, headers: Map<String, String>): Answer? {
        val builder = HttpRequest.newBuilder(URI.create(url))
            .header("Authorization", "Bearer $token")
            .header("User-Agent", USER_AGENT)
            .timeout(Duration.ofSeconds(10))
            .GET()
        headers.forEach { (k, v) -> builder.header(k, v) }
        return runCatching {
            val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
            val retryAfter = response.headers().firstValue("Retry-After").orElse(null)
                ?.trim()?.toLongOrNull()?.let(Duration::ofSeconds)
            Answer(response.statusCode(), response.body(), retryAfter)
        }.getOrNull()
    }

    private fun send(request: HttpRequest): String? = runCatching {
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() == 200) response.body() else null
    }.getOrNull()

    private fun parseInstant(value: String): Instant? =
        runCatching { Instant.parse(value.replace("+00:00", "Z")) }.getOrNull()
}
