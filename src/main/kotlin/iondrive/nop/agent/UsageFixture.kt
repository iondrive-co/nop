package iondrive.nop.agent

import iondrive.nop.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

/**
 * Canned quota readings that stand in for every provider, for the README screenshot of the usage
 * strip and the accounts dialog.
 *
 * That shot has to show several accounts' worth of numbers without any of them being real, and
 * [Usage]'s providers cannot be faked from outside the way a project or a tab strip can: Claude's
 * reading is an HTTPS call with the account's own token, and Antigravity's is the CLI run as the
 * account. Made-up accounts have neither — and a real account's figures are not something to put in
 * a README. So `scripts/screenshot.sh` sets [ENV] to a file of readings, and while it is set nothing
 * is asked of any provider at all: no request goes out with a made-up token, and no CLI is started
 * against a home that does not exist.
 *
 * The file maps account names to what their reading should be:
 * ```
 * {
 *   "claude-work": {
 *     "session": { "percent": 72, "resetsInMinutes": 108, "windowMinutes": 300 },
 *     "weekly": { "percent": 41, "resetsInMinutes": 4380, "windowMinutes": 10080 },
 *     "models": ["claude-opus-5", "claude-sonnet-5"]
 *   },
 *   "codex-lab": { "unavailable": "not signed in" }
 * }
 * ```
 * Resets are relative, counted from the moment the reading is taken, so a fixture written once never
 * drifts into a week-old snapshot with every window already rolled over.
 */
internal object UsageFixture {
    const val ENV: String = "NOP_USAGE_FIXTURE"

    private val json = Json { ignoreUnknownKeys = true }

    /** The fixture in force, or null — the normal case — when the providers are to be asked. */
    val file: Path? = System.getenv(ENV)?.takeIf { it.isNotBlank() }?.let { Path.of(it) }

    /** [account]'s canned reading. An account the file does not mention reads as unavailable. */
    fun read(file: Path, account: Account, now: Instant = Instant.now()): UsageReading {
        val entry = entry(file, account) ?: return UsageReading.unavailable("no usage recorded yet")
        entry["unavailable"].str()?.let { return UsageReading.unavailable(it) }
        return UsageReading(
            session = window(entry["session"].obj(), now),
            weekly = window(entry["weekly"].obj(), now),
            asOf = now,
        )
    }

    /** [account]'s canned model list, empty when the file gives none. */
    fun models(file: Path, account: Account): List<String> =
        entry(file, account)?.get("models").arr()?.mapNotNull { it.str() }.orEmpty()

    private fun entry(file: Path, account: Account): JsonObject? = runCatching {
        json.parseToJsonElement(Files.readString(file)).obj()?.get(account.name).obj()
    }.onFailure { Log.warn("could not read the usage fixture $file: $it") }.getOrNull()

    private fun window(obj: JsonObject?, now: Instant): UsageWindow? {
        obj ?: return null
        val percent = obj["percent"].double() ?: return null
        return UsageWindow(
            percent = percent.coerceIn(0.0, 100.0),
            resetsAt = obj["resetsInMinutes"].long()?.let { now.plus(Duration.ofMinutes(it)) },
            length = obj["windowMinutes"].long()?.let { Duration.ofMinutes(it) },
        )
    }
}
