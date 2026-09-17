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
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * The three things `agy` does differently from the other two CLIs.
 *
 * **Where the login lives.** This is the whole reason antigravity was deferred. The CLI used to keep
 * its token in one OS-keyring slot — service `gemini`, user `antigravity` — that every account
 * overwrote, so a second account signing in replaced the first and no amount of `HOME` redirection
 * separated them. `agy` 1.2.4 stores it in a *file* instead, `$HOME/.gemini/antigravity-cli/
 * antigravity-oauth-token`, whenever it decides the keyring is not usable; it records that decision
 * in [KEYRING_MARKER], a file beside it holding the time it was taken. nop writes that marker
 * itself before every run, which turns a fallback the CLI happened to be in into the one it is held
 * in — and makes an antigravity account isolated by exactly what a Codex account is isolated by.
 *
 * **What it reports about quota.** There is no endpoint. `/usage` is a slash command the CLI
 * expands in print mode, so a reading costs a CLI start of several seconds and is taken by running
 * the account rather than by asking about it. It answers in *remaining* percent across two model
 * families, where nop shows spent for the one family the account actually runs.
 *
 * **What it writes about a session.** Nothing nop can read as a transcript: conversations are
 * SQLite databases of protobuf blobs. See [iondrive.nop.agent.transcript.AntigravityTailer] for
 * what is left, which is the prompt history and the conversation id.
 */
internal object Antigravity {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    /** The CLI's own directory inside an account's home. Everything below is relative to it. */
    fun cliDir(home: Path): Path = home.resolve(".gemini").resolve("antigravity-cli")

    /** Where `agy` keeps the login, and so what nop treats as the account's credential file. */
    fun tokenFile(home: Path): Path = cliDir(home).resolve("antigravity-oauth-token")

    /** The CLI's prompt history — the only line-oriented record of a session it writes. */
    fun historyFile(home: Path): Path = cliDir(home).resolve("history.jsonl")

    /** Workspace path to the conversation last opened there, written by the CLI as it opens one. */
    fun lastConversationsFile(home: Path): Path = cliDir(home).resolve("cache").resolve("last_conversations.json")

    /**
     * Readies [account]'s home for an interactive run or a login. Safe to call every time, and
     * best-effort throughout: none of it is worth refusing to launch over.
     */
    fun prepareHome(account: Account) {
        val home = account.homePath
        runCatching { OwnerOnly.directory(cliDir(home).resolve("cache")) }
        adoptChadCredential(account)
        pinFileTokenStore(home)
        markOnboarded(home)
    }

    /**
     * Whether this account holds a login nop can run it with.
     *
     * The refresh token and not the access token: an access token expires in an hour and is the
     * normal state of an account idle since lunchtime, while the refresh token is what the CLI
     * spends to get another one. Without it the account is signed out in the only sense the user
     * can do something about.
     */
    fun signedIn(account: Account): Boolean {
        val token = readToken(account.credentialFile) ?: return false
        return !token["token"].obj()?.get("refresh_token").str().isNullOrBlank()
    }

    /**
     * The Google account this login belongs to, for saying *which* account a row is. Read out of
     * the id token's own payload, which is a plain base64url JSON segment — nothing is verified
     * here, because nothing is trusted here: it is a label.
     */
    fun email(account: Account): String? {
        val idToken = readToken(account.credentialFile)?.get("id_token").str() ?: return null
        val payload = idToken.split(".").getOrNull(1) ?: return null
        return runCatching {
            val decoded = java.util.Base64.getUrlDecoder()
                .decode(payload.padEnd(payload.length + (4 - payload.length % 4) % 4, '='))
            Json.parseToJsonElement(String(decoded, Charsets.UTF_8)).obj()?.get("email").str()
        }.getOrNull()
    }

    /**
     * What this account has left, by asking the CLI as the account.
     *
     * Every other reading nop takes is a file read or an HTTPS GET. This one starts a process, so
     * it is bounded by [USAGE_TIMEOUT] and called only from the background poller — and the answer
     * carries [Instant.now] as its age, because unlike a Codex reading it really was taken now.
     */
    fun readUsage(account: Account): UsageReading {
        // Before the check, not after: an account inherited from chad has its login in chad's file
        // and none in the CLI's until this has run, and "not signed in" is exactly the wrong answer
        // for an account nop is one copy away from being able to run.
        prepareHome(account)
        if (!signedIn(account)) return UsageReading.unavailable("not signed in")
        val output = run(account, listOf("-p", "/usage", "--output-format", "text"), USAGE_TIMEOUT)
            ?: return UsageReading.unavailable("could not ask the CLI for usage")
        val windows = parseUsage(output, account.model)
        if (windows.isEmpty()) return UsageReading.unavailable("unreadable usage output")
        return UsageReading(
            session = windows["session"],
            weekly = windows["weekly"],
            asOf = Instant.now(),
        )
    }

    /** The models this account may actually use, straight from the CLI. Empty when it can't answer. */
    fun models(account: Account): List<String> {
        prepareHome(account)
        if (!signedIn(account)) return emptyList()
        val output = run(account, listOf("models"), MODELS_TIMEOUT) ?: return emptyList()
        return output.lineSequence()
            // `id<TAB>Human readable name`, after a "Fetching available models..." line that has
            // no tab in it at all.
            .mapNotNull { line -> line.substringBefore('\t').trim().takeIf { '\t' in line && it.isNotEmpty() } }
            .toList()
    }

    /**
     * Turns `/usage` output into nop's two windows.
     *
     * The CLI answers one tab-separated row per limit, for both model families:
     * ```
     * Gemini Models	Five Hour Limit Remaining	97%	2026-09-17T10:07:01Z
     * Claude and GPT models	Weekly Limit Remaining	100%	2026-09-24T08:10:33Z
     * ```
     * Only the family [model] runs against is kept — showing a Gemini account's Claude allowance
     * would be a number that never moves — and the percentage is inverted, because the CLI reports
     * what is *left* where every other reading in nop reports what is spent.
     */
    internal fun parseUsage(output: String, model: String?): Map<String, UsageWindow> {
        val wanted = familyFor(model)
        val windows = mutableMapOf<String, UsageWindow>()
        for (line in output.lineSequence()) {
            val fields = line.split('\t').map { it.trim() }
            if (fields.size < 4) continue
            if (fields[0].lowercase() != wanted) continue
            val remaining = fields[2].removeSuffix("%").toDoubleOrNull()?.takeIf { fields[2].endsWith("%") }
                ?: continue
            val limit = fields[1].lowercase()
            val window = when {
                "five hour" in limit -> "session"
                "weekly" in limit -> "weekly"
                else -> continue
            }
            windows[window] = UsageWindow(
                percent = (100.0 - remaining).coerceIn(0.0, 100.0),
                resetsAt = runCatching { Instant.parse(fields[3]) }.getOrNull(),
                length = if (window == "session") Duration.ofHours(5) else Duration.ofDays(7),
            )
        }
        return windows
    }

    /**
     * Which of the two limits applies to the model an account runs. Antigravity serves Gemini,
     * Claude and GPT models against separate allowances, so the account's own model decides which
     * number describes it. An account on the CLI's default is a Gemini account.
     */
    internal fun familyFor(model: String?): String {
        val normalized = model.orEmpty().trim().lowercase()
        return if (normalized.startsWith("claude-") || normalized.startsWith("gpt-")) {
            OTHER_FAMILY
        } else {
            GEMINI_FAMILY
        }
    }

    /**
     * The login chad captured for this account, moved to where `agy` now reads it.
     *
     * chad kept each account's credential in a file of its own and wrote it into the keyring just
     * before that account ran — the same JSON, in a different place. Copying it across is what
     * makes an account inherited from chad work without signing it in again, which is the whole
     * point of nop declaring each account's home explicitly (see [Account.home]).
     *
     * Only ever *into* an empty slot: a token file already there is the one the CLI has been
     * refreshing, and chad's copy is by now months stale.
     */
    private fun adoptChadCredential(account: Account) {
        val target = account.credentialFile
        if (Files.exists(target)) return
        val legacy = account.homePath.resolve("credential.json")
        if (!Files.isRegularFile(legacy)) return
        runCatching {
            OwnerOnly.directory(target.parent)
            Files.copy(legacy, target, StandardCopyOption.COPY_ATTRIBUTES)
            OwnerOnly.tighten(target)
            Log.info("adopted chad's antigravity login for ${account.name}")
        }.onFailure { Log.warn("could not adopt chad's antigravity login for ${account.name}: $it") }
    }

    /**
     * Holds the CLI in the token store nop can isolate.
     *
     * `agy` chooses between the keyring and the file on each run, records "the keyring was not
     * usable" in a marker file stamped with the time, and prefers the file while that stamp is
     * recent. Left alone that is luck: it works on a machine whose keyring is locked and silently
     * stops the day one is unlocked, at which point every account shares one slot again and nop is
     * back to the bug this provider was deferred over.
     *
     * So nop writes the marker itself, with the current time, immediately before the CLI starts.
     * It is the account's own home, the CLI's own file and its own format — nop is voting in an
     * election the CLI already runs, not reaching into anything.
     */
    private fun pinFileTokenStore(home: Path) {
        val marker = cliDir(home).resolve("cache").resolve(KEYRING_MARKER)
        runCatching {
            OwnerOnly.directory(marker.parent)
            // To the second, which is the shape the CLI writes and so the shape it parses.
            Files.writeString(marker, DateTimeFormatter.ISO_INSTANT.format(Instant.now().truncatedTo(ChronoUnit.SECONDS)))
            OwnerOnly.tighten(marker)
        }.onFailure { Log.warn("could not pin agy to its file token store in $home: $it") }
    }

    /**
     * Tells the CLI its first-run flow is done, for the same reason [VendorConfig] tells Claude
     * Code: a fresh home reads `onboardingComplete: false` however good the token beside it is, and
     * an account nop has just handed a working login should not open on a welcome screen.
     *
     * Written only when there is a credential to make it true, and only over nop's own keys — an
     * account that has genuinely never signed in still gets the real flow, because for that account
     * the flow is the right answer.
     */
    private fun markOnboarded(home: Path) {
        if (!Files.isRegularFile(tokenFile(home))) return
        val file = cliDir(home).resolve("cache").resolve("onboarding.json")
        val existing = if (Files.isRegularFile(file)) {
            runCatching { json.parseToJsonElement(Files.readString(file)).obj() }.getOrNull()
                ?: return Log.warn("left $file alone: it is not readable as JSON")
        } else {
            JsonObject(emptyMap())
        }
        if (existing["onboardingComplete"].bool() && existing["consumerOnboardingComplete"].bool()) return

        runCatching {
            val next = buildJsonObject {
                existing.forEach { (k, v) -> put(k, v) }
                put("onboardingComplete", JsonPrimitive(true))
                put("consumerOnboardingComplete", JsonPrimitive(true))
            }
            OwnerOnly.directory(file.parent)
            val tmp = Files.createTempFile(file.parent, "onboarding", ".json")
            Files.writeString(tmp, json.encodeToString(JsonObject.serializer(), next))
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            OwnerOnly.tighten(file)
        }.onFailure { Log.warn("could not mark $file as onboarded: $it") }
    }

    private fun readToken(file: Path): JsonObject? = runCatching {
        Json.parseToJsonElement(Files.readString(file)).obj()
    }.getOrNull()

    /**
     * Runs the CLI as [account] and hands back its stdout, or null if it could not be run, took
     * longer than [timeout], or failed.
     *
     * The process is destroyed on a timeout rather than left behind: this is called from a poller
     * that comes round every few minutes, and a stuck CLI holding a login open would otherwise
     * accumulate one process per poll for as long as nop is running.
     */
    private fun run(account: Account, args: List<String>, timeout: Duration): String? {
        val binary = CliTools.locate(Provider.Antigravity)?.toString() ?: return null
        return runCatching {
            val process = ProcessBuilder(listOf(binary) + args)
                .directory(account.homePath.toFile())
                // Discarded rather than merged: the CLI puts progress chatter on stderr, and a
                // pipe nobody drains fills and stops the process it belongs to.
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .apply { environment()["HOME"] = account.home }
                .start()
            process.outputStream.close()

            // Drained on a thread of its own so the timeout below is the real bound. Reading to EOF
            // on this thread would wait for the process whatever the timeout said, which is how a
            // poller ends up with one wedged CLI per poll.
            //
            // The result is handed over whole, through a reference, rather than appended to a
            // buffer this thread also reads: a join that times out would otherwise leave two
            // threads inside one StringBuilder.
            val output = AtomicReference<String>()
            val drain = thread(isDaemon = true, name = "agy-read") {
                runCatching { process.inputStream.bufferedReader().use { output.set(it.readText()) } }
            }
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                Log.warn("agy ${args.first()} for ${account.name} timed out")
                return null
            }
            drain.join(DRAIN_GRACE_MS)
            if (process.exitValue() != 0) return null
            output.get()
        }.onFailure { Log.warn("could not run agy ${args.first()} for ${account.name}: $it") }.getOrNull()
    }

    /** The file `agy` records "the keyring was not usable" in, and the time it decided that. */
    private const val KEYRING_MARKER = "antigravity-keyring-unavailable"

    private const val GEMINI_FAMILY = "gemini models"
    private const val OTHER_FAMILY = "claude and gpt models"

    /** A `/usage` run is a CLI start and a round trip; six seconds is normal on this machine. */
    private val USAGE_TIMEOUT: Duration = Duration.ofSeconds(60)
    private val MODELS_TIMEOUT: Duration = Duration.ofSeconds(60)

    /** How long the reader gets to finish after the process has exited. It is reading a pipe. */
    private const val DRAIN_GRACE_MS = 2_000L
}
