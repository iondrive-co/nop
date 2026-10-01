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
 * usable token. A home that has only ever run the CLI headless (`-p` skips onboarding entirely) has
 * a perfectly good `.credentials.json` and no such flag, so launching it interactively would ask
 * the user to sign in to an account that is already signed in — which looks exactly like a launcher
 * that lost your login.
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
            // Codex defaults modern models to "priority" (Fast mode) when service_tier is unset in
            // config.toml. Settle config.toml so it defaults to standard processing unless opted in.
            Provider.OpenAI -> {
                if (!Files.isRegularFile(account.credentialFile)) return
                settleCodex(account.homePath.resolve(".codex").resolve("config.toml"))
            }
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

    private val CODEX_SERVICE_TIER_REGEX = Regex("""(?m)^\s*service_tier\s*=""")
    private val CODEX_FAST_OPT_OUT_REGEX = Regex("""(?m)^\s*fast_default_opt_out\s*=""")
    private val TOML_TABLE_HEADER_REGEX = Regex("""^\s*\[""")
    private val TOML_NOTICE_TABLE_REGEX = Regex("""^\s*\[notice\]\s*$""")

    /**
     * Ensures Codex defaults to standard speed rather than /fast mode, and suppresses the fast-mode
     * onboarding prompt across CLI updates.
     *
     * Codex's model descriptors specify `default_service_tier = "priority"` (Fast mode) for modern
     * models. When `service_tier` is unset in config.toml, sessions launch in /fast mode and burn
     * quota faster. Setting `service_tier = "default"` when missing keeps launches on the standard
     * tier, while `/fast` in the TUI still lets the user toggle it and persist their choice.
     * Setting `fast_default_opt_out = true` under `[notice]` prevents Codex from prompting to opt
     * into fast defaults when updated.
     */
    internal fun settleCodex(file: Path) {
        val existing = if (Files.isRegularFile(file)) {
            runCatching { Files.readString(file) }.getOrNull()
                ?: return Log.warn("left $file alone: it could not be read")
        } else {
            ""
        }

        val hasServiceTier = CODEX_SERVICE_TIER_REGEX.containsMatchIn(existing)
        val hasFastOptOut = CODEX_FAST_OPT_OUT_REGEX.containsMatchIn(existing)
        if (hasServiceTier && hasFastOptOut) return

        runCatching {
            val lines = if (existing.isEmpty()) mutableListOf() else existing.lines().toMutableList()

            if (!hasServiceTier) {
                val firstTable = lines.indexOfFirst { TOML_TABLE_HEADER_REGEX.containsMatchIn(it) }
                if (firstTable >= 0) {
                    lines.add(firstTable, "service_tier = \"default\"")
                } else {
                    lines.add("service_tier = \"default\"")
                }
            }

            if (!hasFastOptOut) {
                val noticeIdx = lines.indexOfFirst { TOML_NOTICE_TABLE_REGEX.matches(it) }
                if (noticeIdx >= 0) {
                    lines.add(noticeIdx + 1, "fast_default_opt_out = true")
                } else {
                    if (lines.isNotEmpty() && lines.last().isNotBlank()) {
                        lines.add("")
                    }
                    lines.add("[notice]")
                    lines.add("fast_default_opt_out = true")
                }
            }

            OwnerOnly.directory(file.parent)
            val tmp = Files.createTempFile(file.parent, ".config", ".toml")
            Files.writeString(tmp, lines.joinToString("\n", postfix = "\n"))
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            OwnerOnly.tighten(file)
        }.onFailure { Log.warn("could not update $file: $it") }
    }
}
