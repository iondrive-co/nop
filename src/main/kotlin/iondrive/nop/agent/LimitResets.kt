package iondrive.nop.agent

import java.time.Instant

/**
 * The usage-limit resets one account holds, as its provider offers them: what can be used now, what
 * is held but cannot be used yet and why, or why there is nothing at all.
 */
data class LimitResets(
    val offers: List<ResetOffer> = emptyList(),
    /** Resets held that cannot be used now, each with why: "Fable reset · usable once you reach a limit it refills". */
    val blocked: List<String> = emptyList(),
    /** Why the account has no resets to show, when [offers] and [blocked] are both empty: "No resets left". */
    val none: String? = null,
)

/** One kind of reset the account can use now. Using it spends one of [left]. */
data class ResetOffer(
    /** What the provider redeems: a Claude grant id, or a Codex credit id, empty for Codex's next credit. */
    val id: String,
    /** What it is called in a sentence: "5-hour reset", "Fable reset", "full reset". */
    val kind: String,
    val left: Int,
    /** When it expires; null when it does not, or the provider does not say. */
    val until: Instant?,
    /** What using it does: "Refills your 5-hour limit now". */
    val effect: String,
    /** The account's weekly reset, which using it does not move; null when unknown or not applicable. */
    val weekResets: Instant? = null,
    /** Set when using it now may waste it, as the question to ask before using it anyway. */
    val early: String? = null,
    /** What a successful use says: "5-hour limit reset". */
    val done: String,
) {
    /** "2 Fable resets", "1 full reset", "no 5-hour resets". */
    fun count(n: Int = left): String {
        if (n == 1) return "1 $kind"
        val name = kind.substringBefore(" (")
        val quantity = if (n == 0) "no" else n.toString()
        return "$quantity ${name}s${kind.removePrefix(name)}"
    }
}

/** What happened when a reset was used. [ok] is true only when the provider confirmed it. */
data class ResetOutcome(val ok: Boolean, val text: String)

internal const val UNCONFIRMED = "Couldn't confirm the reset · check your current usage and resets before trying again"
