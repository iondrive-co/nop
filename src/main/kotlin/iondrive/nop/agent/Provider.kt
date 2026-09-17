package iondrive.nop.agent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A vendor coding agent nop knows how to launch.
 *
 * All three keep their credentials in a *file* inside a directory nop points an environment
 * variable at, which is what makes running several accounts side by side possible at all.
 *
 * Antigravity was the exception, and the plan's D3 deferred it for that reason: its login lived in
 * one shared OS-keyring slot every account overwrote, so isolating it meant owning that slot over
 * DBus. `agy` 1.2.4 no longer works that way — it keeps the token in a file under its own home and
 * falls back to that file whenever the keyring is not usable, which is the state nop pins it into.
 * See [Antigravity], which owns the difference; everything else here treats it as a third provider
 * isolated by `HOME`, exactly like Codex.
 *
 * The serialized name is the string chad used, so an account written by hand against the old
 * config reads back as the same provider.
 */
@Serializable
enum class Provider(
    /** How the provider is named in config and in the event log. */
    val id: String,
    /** What the user sees. */
    val label: String,
    /** The binary to run — resolved against PATH and the usual install dirs by [CliTools]. */
    val binary: String,
) {
    @SerialName("anthropic")
    Anthropic("anthropic", "Claude Code", "claude"),

    @SerialName("openai")
    OpenAI("openai", "Codex", "codex"),

    @SerialName("antigravity")
    Antigravity("antigravity", "Antigravity", "agy"),
    ;

    /**
     * Reasoning levels this provider's CLI accepts. Claude's map onto extended-thinking token
     * budgets ([CLAUDE_THINKING_BUDGETS]); Codex's are the values its `model_reasoning_effort`
     * config key takes; Antigravity's are what `agy --effort` accepts. All are offered alongside
     * [DEFAULT_CHOICE], which omits the setting.
     *
     * Antigravity's is the one that is not independent of the model: its model ids name an effort
     * themselves, so this only reaches the CLI for an account left on the default model. See
     * [Spawn], which is where the two are reconciled.
     */
    val reasoningLevels: List<String>
        get() = when (this) {
            Anthropic -> CLAUDE_THINKING_BUDGETS.keys.toList()
            OpenAI -> listOf("minimal", "low", "medium", "high")
            Antigravity -> listOf("low", "medium", "high")
        }

    /**
     * Models to offer when the live list can't be fetched. Claude's real list comes from the
     * Anthropic Models API using the account's own token, and Antigravity's from `agy models` run
     * as the account (see `Usage.discoverModels`), so neither is hardcoded here to go stale; Codex
     * has neither an endpoint nor a subcommand, so its short list is.
     */
    val fallbackModels: List<String>
        get() = when (this) {
            Anthropic -> emptyList()
            OpenAI -> listOf("gpt-5.5", "gpt-5.5-codex", "gpt-5.3-codex")
            Antigravity -> emptyList()
        }

    companion object {
        fun byId(id: String?): Provider? = entries.firstOrNull { it.id == id }
    }
}

/**
 * The placeholder both model and reasoning pickers start on: it means "don't pass the flag", so
 * the CLI's own default applies. Stored as null, shown as this.
 */
const val DEFAULT_CHOICE: String = "default"

/**
 * Claude Code's extended-thinking budgets keyed by reasoning level, ordered least to most. Set
 * through the `MAX_THINKING_TOKENS` environment variable the CLI reads. 31999 is the CLI's own
 * cap, so the top tiers converge on it.
 */
val CLAUDE_THINKING_BUDGETS: Map<String, Int> = linkedMapOf(
    "low" to 4000,
    "medium" to 10000,
    "high" to 21000,
    "xHigh" to 28000,
    "Max" to 31999,
    "Ultracode" to 31999,
)
