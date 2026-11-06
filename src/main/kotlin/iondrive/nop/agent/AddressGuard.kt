package iondrive.nop.agent

import iondrive.nop.Log
import iondrive.nop.Settings
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.eclipse.jgit.lib.Config
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.Executors

/**
 * Keeps the user's own email addresses out of what agents send to other services.
 *
 * Agents put the address they were told the user has into User-Agent headers by habit, because
 * SEC EDGAR and a few other sites ask automated clients for a contact — and then into every
 * script they write after, for sites that never asked. No CLI stops that, so nop puts one hook in
 * front of every tool call, in all three: `nop-address-guard`, a short Python script that denies a
 * call carrying one of the user's addresses out — in a command that talks to the network, a URL, a
 * header in code being written, or a script a command runs — and says why, so the agent can redo
 * the call without it. Calls that only mention an address (a grep, a commit, prose in a doc) go
 * through.
 *
 * The hook goes into each account's home, not onto nop's command line, because nop's tabs are not
 * the only runs those homes have: a project's own tooling starts headless `claude -p` and `codex
 * exec` workers on them, and those leak the same way.
 *  - Claude Code: `PreToolUse` in the home's `settings.json`.
 *  - Codex: `PreToolUse` in `.codex/hooks.json`, and its trust in `.codex/config.toml` — Codex
 *    skips any hook not trusted by hash, so nop records the hash Codex would ([codexTrustHash]).
 *  - `agy`: a named hook in `.gemini/config/hooks.json`, its global customisation root. agy has no
 *    way for a hook to abstain — an empty answer denies the call — so the guard answers `allow` to
 *    what it passes, which under the `--dangerously-skip-permissions` nop runs agy with is what
 *    would have happened anyway.
 *
 * Which addresses: the ones nop's accounts are signed in as, the user's own CLIs' logins, and the
 * user's git identities (but no `noreply` address, which exists to be public), written to
 * [addressesFile]. The user adds more, lets an address go to named hosts, or turns the guard off,
 * in [userFile]:
 *
 *     {"addresses": ["me@example.org"],
 *      "allow": [{"address": "me+sec@example.org", "hosts": ["sec.gov"]}],
 *      "enabled": true}
 *
 * An address is matched by the mailbox it reaches, so `me+anything@` and, for Gmail, a dotted
 * spelling are the same address. Every denial is a line in [logFile].
 *
 * The script fails open: a fault of its own lets the call through, and without a `python3` nop
 * takes its hooks back out rather than leave every tool call failing on a missing interpreter.
 */
internal object AddressGuard {

    const val SCRIPT_NAME = "nop-address-guard"
    private const val RESOURCE = "/iondrive/nop/agent/address-guard.py"
    private const val TIMEOUT_S = 10
    private const val MATCHER = "*"
    private const val PRE_TOOL_USE = "PreToolUse"

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json { prettyPrint = true; prettyPrintIndent = "  " }
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "address-guard").apply { isDaemon = true } }

    fun scriptPath(root: Path = Accounts.dataRoot()): Path = root.resolve("bin").resolve(SCRIPT_NAME)
    fun addressesFile(root: Path = Accounts.dataRoot()): Path = root.resolve("address-guard").resolve("addresses.json")
    fun logFile(root: Path = Accounts.dataRoot()): Path = root.resolve("address-guard").resolve("guard.log")
    fun userFile(configRoot: Path = Settings.configRoot): Path = configRoot.resolve("nop").resolve("address-guard.json")

    /** Hooks every account in now, and every account added later. Called once, by the nop that owns the windows. */
    fun start() {
        worker.execute { install(Accounts.load()) }
        Accounts.addChangeListener { next -> worker.execute { install(next) } }
    }

    /** Before a run: the hook in [account]'s home, and the addresses it reads up to date. */
    fun prepare(account: Account) {
        runCatching { install(Accounts.load(), only = account) }
            .onFailure { Log.warn("address guard: could not prepare ${account.name}: $it") }
    }

    @Synchronized
    internal fun install(
        config: AgentConfig,
        only: Account? = null,
        root: Path = Accounts.dataRoot(),
        userHome: Path = Path.of(System.getProperty("user.home")),
        configRoot: Path = Settings.configRoot,
        python: String? = python(),
    ) {
        val accounts = only?.let(::listOf) ?: config.accounts
        val command = python?.let {
            writeScript(root, configRoot, it)
            writeIfChanged(addressesFile(root), addressesJson(addresses(config.accounts, userHome)), "rw-------")
            command(it, scriptPath(root))
        }
        if (command == null) Log.warn("address guard: no python3, so no account gets the hook")
        for (account in accounts) {
            runCatching { hook(account, command) }
                .onFailure { Log.warn("address guard: could not hook ${account.name}: $it") }
        }
    }

    /** Puts [command] in front of [account]'s tool calls, or takes nop's hook out when it is null. */
    internal fun hook(account: Account, command: String?) {
        val home = account.homePath
        if (!Files.isDirectory(home)) return
        when (account.provider) {
            Provider.Anthropic -> editJson(home.resolve("settings.json")) { withClaudeHook(it, command) }
            Provider.OpenAI -> hookCodex(home.resolve(".codex"), command)
            Provider.Antigravity -> editJson(home.resolve(".gemini").resolve("config").resolve("hooks.json")) {
                withAgyHook(it, command)
            }
        }
    }

    /** The shell line each CLI runs: an absolute interpreter, isolated, so no PATH or site-packages decides what runs. */
    internal fun command(python: String, script: Path): String = "${shellQuote(python)} -I -S ${shellQuote(script.toString())}"

    private fun handler(command: String, cli: String): JsonObject = buildJsonObject {
        put("type", "command")
        put("command", "$command $cli")
        put("timeout", TIMEOUT_S)
    }

    private fun isOurs(handler: JsonElement): Boolean = handler.obj()?.get("command").str()?.contains(SCRIPT_NAME) == true

    /**
     * [groups] (a `PreToolUse` list of matcher groups) without any handler of nop's, and with nop's
     * one group at the end when [handler] is not null. A group left with no handler is dropped.
     */
    private fun withOurGroup(groups: JsonArray?, handler: JsonObject?): JsonArray = buildJsonArray {
        for (group in groups.orEmpty()) {
            val hooks = group.obj()?.get("hooks").arr()
            if (hooks == null || hooks.none(::isOurs)) {
                add(group)
                continue
            }
            val kept = hooks.filterNot(::isOurs)
            if (kept.isNotEmpty()) add(JsonObject(group.obj()!! + ("hooks" to JsonArray(kept))))
        }
        if (handler != null) {
            add(buildJsonObject {
                put("matcher", MATCHER)
                put("hooks", buildJsonArray { add(handler) })
            })
        }
    }

    /** [events] (an object of hook lists by event name) with nop's PreToolUse group in place. */
    private fun withPreToolUse(events: JsonObject?, handler: JsonObject?): JsonObject? {
        val groups = withOurGroup(events?.get(PRE_TOOL_USE).arr(), handler)
        val rest = events.orEmpty() - PRE_TOOL_USE
        return when {
            groups.isNotEmpty() -> JsonObject(events.orEmpty().toMutableMap().apply { put(PRE_TOOL_USE, groups) })
            rest.isNotEmpty() -> JsonObject(rest)
            else -> null
        }
    }

    internal fun withClaudeHook(settings: JsonObject, command: String?): JsonObject {
        val hooks = withPreToolUse(settings["hooks"].obj(), command?.let { handler(it, "claude") })
        return JsonObject(settings.toMutableMap().apply { if (hooks != null) put("hooks", hooks) else remove("hooks") })
    }

    /** `agy` names each hook at the top level of its file, so nop's is one key of its own. */
    internal fun withAgyHook(file: JsonObject, command: String?): JsonObject {
        val ours = command?.let {
            buildJsonObject {
                put(PRE_TOOL_USE, buildJsonArray {
                    add(buildJsonObject {
                        put("matcher", MATCHER)
                        put("hooks", buildJsonArray { add(handler(it, "agy")) })
                    })
                })
            }
        }
        return JsonObject(file.toMutableMap().apply { if (ours != null) put(SCRIPT_NAME, ours) else remove(SCRIPT_NAME) })
    }

    internal fun hookCodex(codexDir: Path, command: String?) {
        val hooksFile = codexDir.resolve("hooks.json")
        val handler = command?.let { handler(it, "codex") }
        val written = editJson(hooksFile) { file ->
            val events = withPreToolUse(file["hooks"].obj(), handler)
            JsonObject(file.toMutableMap().apply { if (events != null) put("hooks", events) else remove("hooks") })
        } ?: return
        val groups = written["hooks"].obj()?.get(PRE_TOOL_USE).arr().orEmpty()
        val index = groups.indexOfFirst { g -> g.obj()?.get("hooks").arr().orEmpty().any(::isOurs) }
        val path = hooksFile.toAbsolutePath().normalize().toString()
        val trust = handler?.takeIf { index >= 0 }?.let { "$path:pre_tool_use:$index:0" to codexTrustHash(it, MATCHER) }
        val ourHash = handler?.let { codexTrustHash(it, MATCHER) }
        editCodexTrust(codexDir.resolve("config.toml"), path, trust, ourHash)
    }

    /**
     * The hash Codex trusts a hook by: SHA-256 of the hook's normalized identity — its event, its
     * matcher, and its one handler with the defaults filled in — as JSON with every object's keys
     * sorted and no whitespace (codex-rs `hook_hash` and `version_for_toml`).
     */
    internal fun codexTrustHash(handler: JsonObject, matcher: String?): String {
        val normalized = buildJsonObject {
            put("async", handler["async"].bool())
            handler["commandWindows"]?.let { put("commandWindows", it) }
            put("command", handler["command"].str())
            handler["statusMessage"]?.let { put("statusMessage", it) }
            put("timeout", handler["timeout"].long() ?: 600)
            put("type", "command")
        }
        val identity = buildJsonObject {
            put("event_name", "pre_tool_use")
            put("hooks", buildJsonArray { add(normalized) })
            if (matcher != null) put("matcher", matcher)
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical(identity).toByteArray(Charsets.UTF_8))
        return "sha256:" + digest.joinToString("") { "%02x".format(it) }
    }

    /** JSON as serde_json writes it: keys sorted, no whitespace, `\u00xx` in lower case. */
    private fun canonical(e: JsonElement): String = when (e) {
        is JsonObject -> e.entries.sortedBy { it.key }.joinToString(",", "{", "}") { (k, v) -> quote(k) + ":" + canonical(v) }
        is JsonArray -> e.joinToString(",", "[", "]") { canonical(it) }
        is JsonPrimitive -> if (e.isString) quote(e.content) else e.content
    }

    private fun quote(s: String): String = buildString {
        append('"')
        for (c in s) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }

    /**
     * Records [trust] (the key and hash of nop's hook in [hooksPath]) in Codex's `config.toml`, and
     * drops any other entry for that file that holds [ourHash] — left behind when nop's group moved
     * to another place in the list. Every other line of the file is left exactly as it was.
     */
    internal fun editCodexTrust(file: Path, hooksPath: String, trust: Pair<String, String>?, ourHash: String?) {
        val existing = if (Files.isRegularFile(file)) Files.readString(file) else ""
        val lines = existing.lines().toMutableList()
        if (lines.lastOrNull() == "") lines.removeAt(lines.lastIndex)
        var changed = false

        // Each [hooks.state."<key>"] table: its lines up to the next table, and its trusted hash.
        data class Table(val key: String, val start: Int, val end: Int, val hash: String?, val hashAt: Int)
        fun tables(): List<Table> = buildList {
            var i = 0
            while (i < lines.size) {
                val key = TRUST_HEADER.matchEntire(lines[i])?.groupValues?.get(1)
                if (key == null) {
                    i++
                    continue
                }
                var end = i + 1
                while (end < lines.size && !TABLE_HEADER.containsMatchIn(lines[end])) end++
                val at = (i + 1 until end).firstOrNull { TRUSTED_HASH.matches(lines[it]) } ?: -1
                val hash = if (at >= 0) TRUSTED_HASH.matchEntire(lines[at])!!.groupValues[1] else null
                add(Table(unquoteToml(key), i, end, hash, at))
                i = end
            }
        }

        if (ourHash != null) {
            val stale = tables().filter { it.hash == ourHash && it.key.startsWith("$hooksPath:") && it.key != trust?.first }
            for (t in stale.sortedByDescending { it.start }) {
                repeat(t.end - t.start) { lines.removeAt(t.start) }
                changed = true
            }
        }

        if (trust != null) {
            val (key, hash) = trust
            val entry = "trusted_hash = \"$hash\""
            val table = tables().firstOrNull { it.key == key }
            when {
                table == null -> {
                    if (lines.isNotEmpty() && lines.last().isNotBlank()) lines.add("")
                    lines.add("[hooks.state.\"${quoteToml(key)}\"]")
                    lines.add(entry)
                    changed = true
                }
                table.hash == hash -> Unit
                table.hashAt >= 0 -> lines[table.hashAt] = entry.also { changed = true }
                else -> lines.add(table.start + 1, entry).also { changed = true }
            }
        }

        if (!changed) return
        while (lines.lastOrNull()?.isBlank() == true) lines.removeAt(lines.lastIndex)
        replace(file, lines.joinToString("\n", postfix = "\n"), keepMode = true)
        Log.info("address guard: recorded Codex's trust in $file")
    }

    private val TRUST_HEADER = Regex("""^\s*\[hooks\.state\."((?:[^"\\]|\\.)*)"]\s*$""")
    private val TABLE_HEADER = Regex("""^\s*\[""")
    private val TRUSTED_HASH = Regex("""^\s*trusted_hash\s*=\s*"([^"]*)"\s*$""")

    private fun quoteToml(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
    private fun unquoteToml(s: String) = s.replace(Regex("""\\(.)""")) { it.groupValues[1] }

    /**
     * Rewrites the JSON object in [file] through [change], creating the file when it is missing, and
     * returns what the file now holds. A file that is not a JSON object is left alone (and null
     * returned): writing over something unparseable would turn a missing hook into lost settings.
     */
    private fun editJson(file: Path, change: (JsonObject) -> JsonObject): JsonObject? {
        val existing = if (Files.isRegularFile(file)) {
            runCatching { Json.parseToJsonElement(Files.readString(file)).obj() }.getOrNull()
                ?: return null.also { Log.warn("address guard: left $file alone: it is not readable as JSON") }
        } else {
            JsonObject(emptyMap())
        }
        val next = change(existing)
        if (next == existing) return existing
        if (next.isEmpty() && !Files.exists(file)) return next
        replace(file, json.encodeToString(JsonObject.serializer(), next) + "\n", keepMode = true)
        Log.info("address guard: updated $file")
        return next
    }

    /** [file] replaced by [text] in one rename, keeping the old file's permissions when asked. */
    private fun replace(file: Path, text: String, keepMode: Boolean) {
        OwnerOnly.directory(file.parent)
        val mode = if (keepMode && Files.exists(file)) runCatching { Files.getPosixFilePermissions(file) }.getOrNull() else null
        val tmp = Files.createTempFile(file.parent, ".${file.fileName}", ".tmp", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        try {
            Files.writeString(tmp, text)
            mode?.let { Files.setPosixFilePermissions(tmp, it) }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    private fun writeIfChanged(file: Path, text: String, mode: String) {
        if (Files.isRegularFile(file) && runCatching { Files.readString(file) }.getOrNull() == text) return
        OwnerOnly.directory(file.parent)
        val tmp = Files.createTempFile(file.parent, ".${file.fileName}", ".tmp", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(mode)))
        try {
            Files.writeString(tmp, text)
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    /** The guard, with the files it reads named in it. Rewritten when this build's differs. */
    internal fun writeScript(root: Path, configRoot: Path, python: String) {
        val source = checkNotNull(AddressGuard::class.java.getResourceAsStream(RESOURCE)) { "missing $RESOURCE" }
            .use { String(it.readAllBytes(), Charsets.UTF_8) }
        val text = "#!$python -I -S\n" + source
            .replace("\"@@ADDRESSES@@\"", JsonPrimitive(addressesFile(root).toString()).toString())
            .replace("\"@@USER_CONFIG@@\"", JsonPrimitive(userFile(configRoot).toString()).toString())
            .replace("\"@@LOG@@\"", JsonPrimitive(logFile(root).toString()).toString())
        writeIfChanged(scriptPath(root), text, "rwx------")
    }

    private fun addressesJson(addresses: List<String>): String =
        json.encodeToString(JsonObject.serializer(), buildJsonObject { put("addresses", buildJsonArray { addresses.forEach { add(JsonPrimitive(it)) } }) }) + "\n"

    /** The user's addresses nop can find: its accounts' logins, the user's own CLIs', and git's. */
    internal fun addresses(accounts: List<Account>, userHome: Path): List<String> {
        val found = buildList {
            for (account in accounts) {
                when (account.provider) {
                    Provider.Anthropic -> add(claudeEmail(account.homePath.resolve(".claude.json")))
                    Provider.OpenAI -> add(codexEmail(account.homePath.resolve(".codex").resolve("auth.json")))
                    Provider.Antigravity -> add(runCatching { Antigravity.email(account) }.getOrNull())
                }
            }
            add(claudeEmail(userHome.resolve(".claude.json")))
            add(codexEmail(userHome.resolve(".codex").resolve("auth.json")))
            addAll(gitEmails(userHome))
        }
        return found.filterNotNull()
            .map { it.trim().lowercase() }
            .filter { '@' in it && "noreply" !in it }
            .distinct()
            .sorted()
    }

    private fun readObject(file: Path): JsonObject? =
        runCatching { Json.parseToJsonElement(Files.readString(file)).obj() }.getOrNull()

    internal fun claudeEmail(file: Path): String? = readObject(file)?.get("oauthAccount").obj()?.get("emailAddress").str()

    internal fun codexEmail(file: Path): String? {
        val token = readObject(file)?.get("tokens").obj()?.get("id_token").str() ?: return null
        val payload = token.split(".").getOrNull(1) ?: return null
        return runCatching {
            val decoded = Base64.getUrlDecoder().decode(payload.padEnd(payload.length + (4 - payload.length % 4) % 4, '='))
            Json.parseToJsonElement(String(decoded, Charsets.UTF_8)).obj()?.get("email").str()
        }.getOrNull()
    }

    /** `user.email` in the user's git config and in every file it includes, whatever the condition. */
    internal fun gitEmails(userHome: Path): List<String> {
        val main = userHome.resolve(".gitconfig")
        val config = gitConfig(main) ?: return emptyList()
        val included = buildList {
            addAll(config.getStringList("include", null, "path"))
            for (condition in config.getSubsections("includeIf")) addAll(config.getStringList("includeIf", condition, "path"))
        }.mapNotNull { path ->
            val expanded = if (path.startsWith("~/")) userHome.resolve(path.removePrefix("~/")) else Path.of(path)
            gitConfig(if (expanded.isAbsolute) expanded else main.parent.resolve(expanded))
        }
        return (listOf(config) + included).flatMap { it.getStringList("user", null, "email").toList() }
    }

    private fun gitConfig(file: Path): Config? {
        if (!Files.isRegularFile(file)) return null
        return runCatching { Config().apply { fromText(Files.readString(file)) } }.getOrNull()
    }

    /** An absolute `python3`, or null. nop started from the desktop has only the system PATH. */
    internal fun python(): String? {
        val dirs = listOf("/usr/bin", "/usr/local/bin", "/bin") + System.getenv("PATH").orEmpty().split(File.pathSeparator)
        return dirs.filter { it.isNotBlank() }.map { Path.of(it, "python3") }.firstOrNull { Files.isExecutable(it) }?.toString()
    }

    private fun shellQuote(s: String): String =
        if (s.all { it.isLetterOrDigit() || it in "/._-+=:@%," }) s else "'" + s.replace("'", "'\\''") + "'"
}
