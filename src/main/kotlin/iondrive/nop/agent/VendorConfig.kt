package iondrive.nop.agent

import iondrive.nop.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Whatever nop has to put in a vendor's own config before its TUI will run.
 *
 * Claude Code decides whether to show its first-run flow — theme picker, then sign in — from
 * `hasCompletedOnboarding` in `$CLAUDE_CONFIG_DIR/.claude.json`, and not from whether it has a
 * usable token. An account inherited from a tool that only ever drove the CLI headless (`-p` skips
 * onboarding entirely) has a perfectly good `.credentials.json` and no such flag, so launching it
 * interactively asked the user to sign in to an account that was already signed in — which is
 * exactly what it looks like: a launcher that lost your login.
 *
 * So nop sets the flag, and only when there are credentials beside it to make it true. An account
 * that has genuinely never signed in still gets the real flow, because for that account the flow is
 * the right answer.
 *
 * It sets one other thing while it is there: that Claude Code's diff sidebar starts closed. See
 * [DIFF_SIDEBAR_KEY].
 */
internal object VendorConfig {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private const val ONBOARDING_KEY = "hasCompletedOnboarding"

    /**
     * Whether Claude Code opens its changed-files pane beside the transcript.
     *
     * Left unset, the CLI opens that pane by itself as soon as the terminal is wide enough and the
     * directory is a repository — which an agent pane in nop always is, so every session came up
     * with a second diff squeezed in beside the conversation, next to nop's own Diff tab that exists
     * to show the same thing without taking the TUI's width. `false` keeps it shut.
     *
     * Written only when the key is missing. `/diff` still opens the pane and writes the answer here
     * itself, so a user who asks for it in some account keeps it there: this is a default, not a
     * rule nop re-imposes on every launch.
     */
    private const val DIFF_SIDEBAR_KEY = "diffSidebarOpen"

    /** Does whatever [account]'s provider needs before an interactive run. Safe to call every time. */
    fun prepareForInteractive(account: Account) {
        when (account.provider) {
            Provider.Anthropic -> {
                if (!Files.isRegularFile(account.credentialFile)) return
                settle(account.homePath.resolve(".claude.json"))
            }
            // The same first-run problem and more besides — which token store the CLI will use is
            // also decided at startup, and that decision is what isolates the account at all. Then
            // the shared memory's instructions, which this CLI takes only as a rule in its home.
            Provider.Antigravity -> {
                Antigravity.prepareHome(account)
                SharedMemory.installRule(account.homePath)
            }
            // Codex asks for nothing: it reads its own auth.json and starts.
            Provider.OpenAI -> Unit
        }
    }

    /**
     * Adds Claude Code's onboarding flag to [file], and the diff sidebar's default if it has none,
     * leaving every other key exactly as it was.
     *
     * This is someone else's config: the rewrite re-reads the whole document, adds only what is
     * missing, and lands through a temp file and an atomic rename, so a crash mid-write cannot cost
     * the user their CLI settings. A file that already says both is not touched at all. A file that
     * can't be read is left alone — writing a fresh one over something unparseable would turn a
     * puzzling prompt into lost configuration.
     */
    private fun settle(file: Path) {
        val existing = if (Files.isRegularFile(file)) {
            runCatching { json.parseToJsonElement(Files.readString(file)).obj() }.getOrNull()
                ?: return Log.warn("left $file alone: it is not readable as JSON")
        } else {
            JsonObject(emptyMap())
        }
        val missing = buildMap {
            if (existing[ONBOARDING_KEY]?.bool() != true) put(ONBOARDING_KEY, JsonPrimitive(true))
            if (DIFF_SIDEBAR_KEY !in existing) put(DIFF_SIDEBAR_KEY, JsonPrimitive(false))
        }
        if (missing.isEmpty()) return

        runCatching {
            val next = buildJsonObject {
                existing.forEach { (k, v) -> put(k, v) }
                missing.forEach { (k, v) -> put(k, v) }
            }
            OwnerOnly.directory(file.parent)
            val tmp = Files.createTempFile(file.parent, ".claude", ".json")
            Files.writeString(tmp, json.encodeToString(JsonObject.serializer(), next))
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.onFailure { Log.warn("could not update $file: $it") }
    }
}
