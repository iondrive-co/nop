package iondrive.nop.agent

import iondrive.nop.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Claude's usage-limit resets: the grants the usage API lists under `cedar_ember` when asked for
 * them, and the claim that spends one.
 *
 * The server only lists grants to a client that says it is the CLI, so [headers] go with the usage
 * read as well as the claim. Grouping, wording and the checks before a claim follow the CLI's own
 * `/usage` screen, so an account reads the same in both.
 */
internal object ClaudeResets {
    const val PROGRAM = "cedar_ember"
    private const val FALLBACK_CLI_VERSION = "2.1.332"
    private const val OAUTH_BETA = "oauth-2025-04-20"
    private const val API_BASE = "https://api.anthropic.com"

    private val GRANT_ID = Regex("^[a-z0-9_-]{1,40}$")
    private val REQUEST_ID = Regex("^[A-Za-z0-9_-]{1,64}$")
    private val ORG_ID = Regex("^[A-Za-z0-9-]{1,64}$")
    private val VERSION = Regex("^\\d+\\.\\d+\\.\\d+$")

    /** The limits the server may name in a grant; anything else is dropped, as the CLI drops it. */
    private val LIMITS = listOf(
        "five_hour", "seven_day", "seven_day_overage_included", "seven_day_opus", "seven_day_sonnet",
        "seven_day_cowork", "seven_day_omelette", "seven_day_oauth_apps",
    )
    private val LIMIT_NAMES = mapOf(
        "five_hour" to "5-hour limit",
        "seven_day" to "weekly limit",
        "seven_day_opus" to "Opus limit",
        "seven_day_sonnet" to "Sonnet limit",
        "seven_day_overage_included" to "Fable limit",
    )
    private val KIND_ORDER = listOf("session", "weekly", "fable", "full")

    private val json = Json { ignoreUnknownKeys = true }
    private val http: HttpClient by lazy {
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    }

    // ── Who nop says it is ──

    private val cliVersion: String by lazy { cliVersion(CliTools.locate(Provider.Anthropic)) }

    /** The installed CLI's version, from the versioned file its launcher points at. */
    internal fun cliVersion(binary: Path?): String = runCatching {
        binary?.toRealPath()?.fileName?.toString()?.takeIf { VERSION.matches(it) }
    }.getOrNull() ?: FALLBACK_CLI_VERSION

    fun userAgent(version: String = cliVersion): String = "claude-cli/$version (external, cli)"

    /** What both the usage read and the claim send besides the token. */
    fun headers(version: String = cliVersion): Map<String, String> = mapOf(
        "anthropic-beta" to OAUTH_BETA,
        "x-app" to "cli",
        "User-Agent" to userAgent(version),
    )

    // ── The grants ──

    private data class Grant(
        val id: String,
        val resetsLeft: Int,
        val endsAt: Instant?,
        val clears: List<String>,
        val paused: Boolean,
        val usableNow: Boolean,
        val useRequiresLimit: Boolean,
        val percentUsed: Map<String, Int>,
        val blocking: List<String>,
        val type: String?,
        val givesBackWeekly: Boolean,
        val unavailableReason: String?,
    ) {
        val kind: String get() = when {
            givesBackWeekly -> "fable"
            type != null -> type
            clears.isNotEmpty() && clears.all { it == "five_hour" } -> "session"
            clears.isNotEmpty() && clears.none { it == "five_hour" } -> "weekly"
            else -> "full"
        }
        val held: Boolean get() = !paused && (resetsLeft > 0 || usableNow)

        /** Whether using it gives back room in [limit]. */
        fun refills(limit: String): Boolean = limit in clears || (limit == "seven_day" && givesBackWeekly)
    }

    private data class Status(
        val eligible: Boolean,
        val ineligibleReason: String,
        val atLimit: Boolean,
        val exhausted: List<String>,
        val grants: List<Grant>,
        val weeklyResetsAt: Instant?,
        val cooldownUntil: Instant?,
    )

    private fun limits(element: JsonElement?): List<String> =
        element.arr().orEmpty().mapNotNull { it.str() }.filter { it in LIMITS }.distinct()

    private fun instant(element: JsonElement?): Instant? = element.str()?.let {
        runCatching { Instant.parse(it.replace("+00:00", "Z")) }.getOrNull()
    }

    private fun count(element: JsonElement?): Int? = element.str()?.toDoubleOrNull()
        ?.takeIf { it >= 0 && it == Math.floor(it) && it <= Int.MAX_VALUE }?.toInt()

    private fun grant(element: JsonElement?): Grant? {
        val o = element.obj() ?: return null
        val id = o["id"].str()?.takeIf { GRANT_ID.matches(it) } ?: return null
        val left = count(o["resets_left"]) ?: return null
        fun flag(name: String, default: Boolean) = o[name].str()?.toBooleanStrictOrNull() ?: default
        return Grant(
            id = id,
            resetsLeft = left,
            endsAt = instant(o["ends_at"]),
            clears = limits(o["clears"]),
            paused = flag("paused", false),
            usableNow = flag("usable_now", false),
            useRequiresLimit = flag("use_requires_limit", true),
            percentUsed = o["percent_used"].obj().orEmpty()
                .filterKeys { it in LIMITS }
                .mapNotNull { (k, v) -> count(v)?.takeIf { it <= 100 }?.let { k to it } }
                .toMap(),
            blocking = limits(o["blocking"]),
            type = o["type"].str()?.takeIf { it in setOf("session", "weekly", "full") },
            givesBackWeekly = flag("gives_back_weekly", false),
            unavailableReason = o["unavailable_reason"].str()?.takeIf { it in UNAVAILABLE },
        )
    }

    private val UNAVAILABLE = setOf(
        "cooldown", "blocked_five_hour", "blocked_seven_day", "blocked_opus", "blocked_sonnet",
        "blocked_other", "cannot_clear", "needs_limit", "unavailable",
    )

    private fun status(element: JsonElement?): Status? {
        val o = element.obj() ?: return null
        return Status(
            eligible = o["eligible"].bool(),
            ineligibleReason = o["ineligible_reason"].str() ?: "unknown",
            atLimit = o["at_limit"].bool(),
            exhausted = limits(o["exhausted"]),
            grants = o["grants"].arr().orEmpty().mapNotNull(::grant),
            weeklyResetsAt = instant(o["weekly_resets_at"]),
            cooldownUntil = instant(o["cooldown_until"]),
        )
    }

    // ── What to offer ──

    /** The resets in the usage API's `cedar_ember` block; null when the block is missing. */
    fun resets(element: JsonElement?, now: Instant = Instant.now()): LimitResets? {
        val status = status(element) ?: return null
        if (!status.eligible || status.grants.isEmpty()) {
            if (!status.eligible) Log.info("Claude resets not offered: ${status.ineligibleReason}")
            return LimitResets(none = "No resets for this account")
        }
        val offers = mutableListOf<ResetOffer>()
        val blocked = mutableListOf<String>()
        val groups = status.grants.filter { it.held && it.endsAt?.isAfter(now) != false }.groupBy { it.kind }
            .toList().sortedBy { KIND_ORDER.indexOf(it.first) }
        for ((kind, grants) in groups) {
            val left = grants.sumOf { it.resetsLeft.toLong() }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val target = grants.filter { it.usableNow }.minWithOrNull(compareBy(nullsLast()) { it.endsAt })
            if (target != null) {
                offers += offer(kind, target, maxOf(1, left), status)
            } else if (left > 0) {
                blocked += "${kindName(kind).replaceFirstChar { it.uppercase() }} · ${reasonText(reason(grants.first(), status))}"
            }
        }
        val none = when {
            offers.isNotEmpty() || blocked.isNotEmpty() -> null
            status.grants.none { it.resetsLeft > 0 } -> "No resets left"
            status.grants.all { it.paused } -> "Resets are paused"
            else -> "No resets can be used right now"
        }
        return LimitResets(offers, blocked, none)
    }

    private fun kindName(kind: String): String = when (kind) {
        "session" -> "5-hour reset"
        "fable" -> "Fable reset"
        "weekly" -> "weekly reset"
        else -> "full reset"
    }

    private fun offer(kind: String, grant: Grant, left: Int, status: Status): ResetOffer {
        val name = kindName(kind)
        var effect = "Refills your ${refillsText(grant)} now"
        if (kind == "fable") {
            effect += " · returns what Fable used to your weekly limit"
            if ("seven_day" in status.exhausted) effect += " · frees up only what Fable used this week"
        }
        return ResetOffer(
            id = grant.id,
            kind = name,
            left = left,
            until = grant.endsAt,
            effect = effect,
            weekResets = status.weeklyResetsAt,
            early = early(grant, status, name),
            done = when (kind) {
                "session" -> "5-hour limit reset"
                "fable" -> "Fable reset used · what Fable used is back in your weekly limit"
                "weekly" -> "Weekly limits reset"
                else -> "Limits reset"
            },
        )
    }

    /** "5-hour limit", "weekly and Opus limits": what a grant gives back room in. */
    private fun refillsText(grant: Grant): String {
        val names = linkedSetOf<String>()
        if (grant.givesBackWeekly) names += "Fable"
        for (limit in grant.clears) when (limit) {
            "seven_day_overage_included" -> names += if (grant.givesBackWeekly) "Fable" else "weekly"
            "five_hour", "seven_day", "seven_day_opus", "seven_day_sonnet" ->
                names += LIMIT_NAMES.getValue(limit).removeSuffix(" limit")
        }
        val list = names.toList()
        return when (list.size) {
            0 -> "limits"
            1 -> "${list[0]} limit"
            else -> list.dropLast(1).joinToString(", ") + " and ${list.last()} limits"
        }
    }

    /** The question to ask before spending a grant that does not need a limit reached, while none it refills is. */
    private fun early(grant: Grant, status: Status, name: String): String? {
        if (grant.useRequiresLimit || status.exhausted.any(grant::refills)) return null
        val (limit, used) = grant.clears.mapNotNull { c -> grant.percentUsed[c]?.let { c to it } }
            .maxByOrNull { it.second } ?: (null to null)
        val limitName = limit?.let(LIMIT_NAMES::get)
        return if (used == null || limitName == null || used >= 100) {
            "You haven't reached a limit yet — use your $name anyway?"
        } else {
            "You still have ${100 - used}% of your $limitName left — use your $name anyway?"
        }
    }

    private fun reason(grant: Grant, status: Status): String {
        grant.unavailableReason?.let { return it }
        if (status.cooldownUntil != null) return "cooldown"
        grant.blocking.firstNotNullOfOrNull {
            when (it) {
                "five_hour" -> "blocked_five_hour"
                "seven_day" -> "blocked_seven_day"
                "seven_day_opus" -> "blocked_opus"
                "seven_day_sonnet" -> "blocked_sonnet"
                else -> null
            }
        }?.let { return it }
        if (grant.blocking.isNotEmpty()) return "blocked_other"
        if (status.atLimit && status.exhausted.none(grant::refills)) return "cannot_clear"
        return if (grant.useRequiresLimit) "needs_limit" else "unavailable"
    }

    private fun reasonText(reason: String): String = when (reason) {
        "cooldown" -> "try again in a minute"
        "blocked_five_hour" -> "can't be used while you're at your 5-hour limit"
        "blocked_seven_day" -> "can't be used while you're at your weekly limit"
        "blocked_opus" -> "can't be used while you're at your Opus limit"
        "blocked_sonnet" -> "can't be used while you're at your Sonnet limit"
        "blocked_other" -> "can't be used while you're at another limit it doesn't refill"
        "cannot_clear" -> "doesn't refill the limit you've reached"
        "needs_limit" -> "usable once you reach a limit it refills"
        else -> "can't be used right now"
    }

    // ── The claim ──

    /** The claim's body, or null when either id is not one the server could have issued. */
    internal fun claimBody(grantId: String, requestId: String): String? {
        if (!GRANT_ID.matches(grantId) || !REQUEST_ID.matches(requestId)) return null
        return buildJsonObject {
            put("program", PROGRAM)
            put("grant_id", grantId)
            put("request_id", requestId)
        }.toString()
    }

    /** The organization the account's login belongs to, which the claim is addressed to. */
    internal fun organization(account: Account): String? = runCatching {
        json.parseToJsonElement(Files.readString(account.homePath.resolve(".claude.json")))
            .obj()?.get("oauthAccount").obj()?.get("organizationUuid").str()
    }.getOrNull()?.takeIf { ORG_ID.matches(it) }

    private const val FAILED = "Couldn't reset your limits · nothing was used · try again in a moment"
    private const val SIGN_IN = "Couldn't reset your limits with this login · sign in again, then try again"

    /**
     * Spends one of [offer]'s grants for [account]. Never retried: a claim the server took but did
     * not answer has been spent, so an unanswered one says so rather than trying again.
     */
    fun claim(account: Account, offer: ResetOffer, apiBase: String = API_BASE): ResetOutcome {
        if (UsageFixture.file != null) return ResetOutcome(false, "Resets are not used while usage is a fixture")
        val org = organization(account) ?: return ResetOutcome(false, SIGN_IN)
        val body = claimBody(offer.id, UUID.randomUUID().toString()) ?: return ResetOutcome(false, FAILED)
        val token = Usage.claudeToken(account) ?: return ResetOutcome(false, SIGN_IN)
        val builder = HttpRequest.newBuilder(URI.create("$apiBase/api/organizations/$org/reset_rate_limits"))
            .setHeader("Authorization", "Bearer $token")
            .setHeader("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(25))
            .POST(HttpRequest.BodyPublishers.ofString(body))
        headers().forEach { (k, v) -> builder.setHeader(k, v) }
        val response = runCatching { http.send(builder.build(), HttpResponse.BodyHandlers.ofString()) }
            .onFailure { Log.warn("Claude reset for ${account.name} unanswered: $it") }
            .getOrNull() ?: return ResetOutcome(false, UNCONFIRMED)
        return outcome(response.statusCode(), response.body(), offer)
            .also { Log.info("Claude reset for ${account.name}: HTTP ${response.statusCode()} · ${it.text}") }
    }

    internal fun outcome(status: Int, body: String, offer: ResetOffer): ResetOutcome {
        if (status == 401 || status == 403) return ResetOutcome(false, SIGN_IN)
        if (status in 400..499) return ResetOutcome(false, FAILED)
        if (status != 200) return ResetOutcome(false, UNCONFIRMED)
        val reply = runCatching { json.parseToJsonElement(body).obj() }.getOrNull()
        return when (reply?.get("result").str()) {
            "reset" -> {
                val left = count(reply?.get("resets_left")) ?: maxOf(0, offer.left - 1)
                ResetOutcome(true, "${offer.done} · ${offer.count(left)} left")
            }
            "not_limited" -> ResetOutcome(false, "You weren't at a limit · nothing was used")
            "cooldown" -> ResetOutcome(false, "Your limits are already being reset · one moment")
            "already_used" -> ResetOutcome(
                false,
                "This reset isn't available any more · your earlier try may have gone through · checking your limits now",
            )
            "ineligible" -> ResetOutcome(false, "Couldn't reset your limits with this login")
            else -> ResetOutcome(false, UNCONFIRMED)
        }
    }
}
