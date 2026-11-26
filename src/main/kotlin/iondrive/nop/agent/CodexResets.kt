package iondrive.nop.agent

import iondrive.nop.Log
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Path
import java.time.DateTimeException
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Codex's usage-limit reset credits, which only the CLI's app-server reads and spends: the usage
 * endpoint nop polls knows nothing of them.
 */
internal object CodexResets {
    const val READ = "account/rateLimits/read"
    const val CONSUME = "account/rateLimitResetCredit/consume"

    private val READ_TIMEOUT: Duration = Duration.ofSeconds(20)

    /** Long enough for the backend to answer; a reset is never retried, so this only bounds the wait. */
    private val CONSUME_TIMEOUT: Duration = Duration.ofSeconds(30)

    private const val GENERIC_KIND = "usage reset"
    private const val EFFECT = "Resets your current usage limits"
    private const val DONE = "Usage reset"

    const val FAILED = "Couldn't reset usage · nothing was used · try again in a moment"
    const val NO_CLI = "Couldn't find the Codex CLI · nothing was used"
    const val FIXTURE = "Resets aren't used while nop shows sample usage"

    /** The resets [account] holds, or null when the CLI could not be asked or did not say. Blocking. */
    fun read(
        account: Account,
        binary: Path? = CliTools.locate(Provider.OpenAI),
        timeout: Duration = READ_TIMEOUT,
    ): LimitResets? {
        if (UsageFixture.file != null || binary == null) return null
        return runCatching {
            CodexAppServer.query(account, binary, timeout, "codex-resets") { rpc ->
                rpc.initialize()
                resets(rpc.request(READ, buildJsonObject {}))
            }
        }.onFailure { Log.warn("could not read Codex resets for ${account.name}: $it") }.getOrNull()
    }

    /** The resets in an `account/rateLimits/read` answer; null when it has no reset summary. */
    internal fun resets(result: JsonObject, now: Instant = Instant.now()): LimitResets? {
        val summary = result["rateLimitResetCredits"].obj() ?: return null
        val available = summary["availableCount"].int()?.coerceAtLeast(0) ?: 0
        // Without details only the count is known, and the backend picks which credit to spend.
        val credits = summary["credits"].arr()?.mapNotNull { credit(it.obj(), now) }
            ?: return if (available > 0) LimitResets(listOf(offer("", GENERIC_KIND, available, null, null)))
            else LimitResets(none = "No resets available")
        val usable = credits.filter { it.status == "available" }
        val groups = usable.groupBy { it.kind }.values
        val offers = groups.map { group ->
            // The soonest to expire is the one to spend first.
            val first = group.minWith(compareBy(nullsLast()) { it.expiresAt })
            // The list may stop short of the count; when every credit is alike the count is theirs.
            val left = if (groups.size == 1) maxOf(group.size, available) else group.size
            offer(first.id, first.kind, left, first.expiresAt, first.description)
        }.sortedWith(compareBy(nullsLast()) { it.until })
        val blocked = credits.filter { it.status == "redeeming" }.groupBy { it.kind }.keys
            .map { "${it.replaceFirstChar(Char::uppercaseChar)} · being used now" }
        if (offers.isNotEmpty() || blocked.isNotEmpty()) return LimitResets(offers, blocked)
        return LimitResets(none = "No resets available")
    }

    private class Credit(val id: String, val kind: String, val status: String, val expiresAt: Instant?, val description: String?)

    private fun credit(obj: JsonObject?, now: Instant): Credit? {
        obj ?: return null
        val id = obj["id"].str()?.takeIf { it.isNotBlank() } ?: return null
        val expiresAt = try {
            obj["expiresAt"].long()?.let(Instant::ofEpochSecond)
        } catch (_: DateTimeException) {
            return null
        }
        if (expiresAt != null && !expiresAt.isAfter(now)) return null
        return Credit(
            id = id,
            kind = kind(obj["title"].str()),
            status = obj["status"].str() ?: "unknown",
            expiresAt = expiresAt,
            description = obj["description"].str()?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    /** "Full reset" reads as "full reset" mid-sentence; "GPT reset" stays as it is. */
    internal fun kind(title: String?): String {
        val t = title?.trim()?.takeIf { it.isNotEmpty() } ?: return GENERIC_KIND
        return if (t.length > 1 && t[1].isLowerCase()) t.replaceFirstChar(Char::lowercaseChar) else t
    }

    private fun offer(id: String, kind: String, left: Int, until: Instant?, description: String?) =
        ResetOffer(id = id, kind = kind, left = left, until = until, effect = description ?: EFFECT, done = DONE)

    /**
     * Spends one of [offer]'s credits for [account], once: a reset is never retried, because a retry
     * that crosses an answer lost on the way back would spend a second credit. Blocking.
     */
    fun consume(
        account: Account,
        offer: ResetOffer,
        binary: Path? = CliTools.locate(Provider.OpenAI),
        timeout: Duration = CONSUME_TIMEOUT,
    ): ResetOutcome {
        if (UsageFixture.file != null) return ResetOutcome(false, FIXTURE)
        if (binary == null) return ResetOutcome(false, NO_CLI)
        val sent = AtomicBoolean(false)
        val outcome = try {
            CodexAppServer.query(account, binary, timeout, "codex-reset") { rpc ->
                rpc.initialize()
                sent.set(true)
                outcome(rpc.request(CONSUME, buildJsonObject {
                    put("idempotencyKey", UUID.randomUUID().toString())
                    if (offer.id.isNotEmpty()) put("creditId", offer.id)
                }), offer)
            }
        } catch (e: Exception) {
            Log.warn("Codex reset for ${account.name} failed: ${e.javaClass.simpleName}")
            ResetOutcome(false, if (sent.get()) UNCONFIRMED else FAILED)
        }
        Log.info("Codex reset for ${account.name}: ${outcome.text}")
        return outcome
    }

    internal fun outcome(result: JsonObject, offer: ResetOffer): ResetOutcome = when (result["outcome"].str()) {
        "reset" -> ResetOutcome(true, "$DONE · ${offer.count(maxOf(0, offer.left - 1))} left")
        "nothingToReset" -> ResetOutcome(false, "Your usage doesn't need a reset right now · nothing was used")
        "noCredit" -> ResetOutcome(false, "No usage limit resets are available · nothing was used")
        "alreadyRedeemed" -> ResetOutcome(false, "That reset is no longer available · checking your current resets")
        else -> ResetOutcome(false, UNCONFIRMED)
    }
}
