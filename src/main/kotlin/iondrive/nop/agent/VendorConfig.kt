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
 * The one thing nop has to put in a vendor's own config before its TUI will run.
 *
 * Claude Code decides whether to show its first-run flow — theme picker, then sign in — from
 * `hasCompletedOnboarding` in `$CLAUDE_CONFIG_DIR/.claude.json`, and not from whether it has a
 * usable token. An account inherited from a tool that only ever drove the CLI headless (`-p` skips
 * onboarding entirely) has a perfectly good `.credentials.json` and no such flag, so launching it
 * interactively asked the user to sign in to an account that was already signed in — which is
 * exactly what it looks like: a launcher that lost your login.
 *
 * So nop sets the flag, and only the flag, and only when there are credentials beside it to make it
 * true. An account that has genuinely never signed in still gets the real flow, because for that
 * account the flow is the right answer.
 */
internal object VendorConfig {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private const val ONBOARDING_KEY = "hasCompletedOnboarding"

    /** Does whatever [account]'s provider needs before an interactive run. Safe to call every time. */
    fun prepareForInteractive(account: Account) {
        if (account.provider != Provider.Anthropic) return
        if (!Files.isRegularFile(account.credentialFile)) return
        markOnboarded(account.homePath.resolve(".claude.json"))
    }

    /**
     * Adds the flag to [file], leaving every other key exactly as it was.
     *
     * This is someone else's config: the rewrite re-reads the whole document, adds one key, and
     * lands through a temp file and an atomic rename, so a crash mid-write cannot cost the user
     * their CLI settings. A file that can't be read is left alone — writing a fresh one over
     * something unparseable would turn a puzzling prompt into lost configuration.
     */
    private fun markOnboarded(file: Path) {
        val existing = if (Files.isRegularFile(file)) {
            runCatching { json.parseToJsonElement(Files.readString(file)).obj() }.getOrNull()
                ?: return Log.warn("left $file alone: it is not readable as JSON")
        } else {
            JsonObject(emptyMap())
        }
        if (existing[ONBOARDING_KEY]?.bool() == true) return

        runCatching {
            val next = buildJsonObject {
                existing.forEach { (k, v) -> put(k, v) }
                put(ONBOARDING_KEY, JsonPrimitive(true))
            }
            OwnerOnly.directory(file.parent)
            val tmp = Files.createTempFile(file.parent, ".claude", ".json")
            Files.writeString(tmp, json.encodeToString(JsonObject.serializer(), next))
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.onFailure { Log.warn("could not mark $file as onboarded: $it") }
    }
}
