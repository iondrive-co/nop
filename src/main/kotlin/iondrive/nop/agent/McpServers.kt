package iondrive.nop.agent

import iondrive.nop.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * The MCP servers the user's own agents have, handed to the agents nop runs.
 *
 * The isolation that keeps accounts apart is also what hid these. A `claude` started from a shell
 * reads its servers from `~/.claude.json`; the same CLI started by nop has `CLAUDE_CONFIG_DIR`
 * pointed at the account's home and reads that home's `.claude.json` instead, which has none. The
 * other two CLIs are run with `HOME` moved, so `agy` misses `~/.gemini/config/mcp_config.json` the
 * same way.
 *
 * Copying the servers into each account's config would be the second place they are kept, and it
 * would go stale the first time the user added one. So they are read from where the user keeps
 * them, on every run, and given to each CLI the way it takes per-run servers:
 *
 * - Claude: `--mcp-config` on a file nop writes, owner-only, because a server's `env` can hold a
 *   token and argv is readable by every account on the machine.
 * - Codex: `-c mcp_servers.<name>={…}` per server.
 * - Antigravity: no per-run way in at all, so into the account home's own `mcp_config.json`,
 *   next to whatever the account has there, removing again the ones nop put there that the user
 *   has since dropped.
 *
 * The source is Claude's: the user-scope servers, then the local-scope ones for this project, less
 * any the user has disabled there. A project's checked-in `.mcp.json` is not read here — Claude
 * finds that itself in the project directory. `agy` also gets the user's own `agy` servers, which
 * its moved `HOME` hid in the same way.
 */
object McpServers {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    /** What a run needs: the servers, and for Claude the file they were written to. */
    data class Launch(val servers: Map<String, JsonObject>, val claudeConfig: Path? = null) {
        companion object {
            val NONE = Launch(emptyMap())
        }
    }

    /**
     * The servers a `claude` started in [projectDir] from the user's shell would have, in Claude's
     * own `.mcp.json` shape. [configDir] is `CLAUDE_CONFIG_DIR` from nop's own environment, read as
     * [NativeSessions] reads it: a user who exports it runs their CLI from there.
     */
    internal fun forProject(
        projectDir: File,
        userHome: Path = Path.of(System.getProperty("user.home")),
        configDir: String? = System.getenv("CLAUDE_CONFIG_DIR"),
    ): Map<String, JsonObject> {
        val file = configDir?.takeIf { it.isNotBlank() }?.let { Path.of(it).resolve(".claude.json") }
            ?: userHome.resolve(".claude.json")
        val root = readObject(file) ?: return emptyMap()
        val project = root["projects"].obj()?.get(projectDir.absolutePath).obj()
        val disabled = project?.get("disabledMcpServers").arr().orEmpty().mapNotNull { it.str() }.toSet()
        return buildMap {
            servers(root["mcpServers"]).forEach { (k, v) -> put(k, v) }
            servers(project?.get("mcpServers")).forEach { (k, v) -> put(k, v) }
            disabled.forEach { remove(it) }
        }
    }

    /**
     * Gets [projectDir]'s servers ready for [account]'s CLI: writes Claude's config file, or merges
     * them into an `agy` home. Never throws — a run without its MCP servers is better than no run.
     */
    internal fun prepare(account: Account, projectDir: File): Launch {
        val servers = runCatching { forProject(projectDir) }
            .onFailure { Log.warn("could not read the user's MCP servers: $it") }
            .getOrDefault(emptyMap())
        return when (account.provider) {
            Provider.Anthropic -> Launch(servers, if (servers.isEmpty()) null else writeClaudeConfig(projectDir, servers))
            Provider.OpenAI -> Launch(servers)
            Provider.Antigravity -> {
                installForAntigravity(account.homePath, servers)
                Launch.NONE
            }
        }
    }

    /** Claude's `--mcp-config` file for [projectDir], under nop's data root and owner-only. */
    internal fun writeClaudeConfig(
        projectDir: File,
        servers: Map<String, JsonObject>,
        dir: Path = Accounts.dataRoot().resolve("mcp"),
    ): Path? = runCatching {
        val doc = buildJsonObject { put("mcpServers", JsonObject(servers)) }
        val file = dir.resolve("claude-${digest(projectDir.absolutePath)}.json")
        writeAtomically(file, json.encodeToString(JsonObject.serializer(), doc))
        file
    }.onFailure { Log.warn("could not write the MCP config for ${projectDir.name}: $it") }.getOrNull()

    /**
     * `-c` overrides that give a `codex` run [servers]. A server it cannot express — SSE, which the
     * CLI does not speak, or a name that isn't a bare TOML key — is left out rather than guessed at.
     */
    internal fun codexOverrides(servers: Map<String, JsonObject>): List<String> = buildList {
        for ((name, server) in servers) {
            val fields = if (BARE_KEY.matches(name)) toCodex(name, server) else {
                Log.info("mcp server '$name' left out of codex: not a bare TOML key")
                null
            }
            if (fields != null) {
                add("-c")
                add("mcp_servers.$name={${fields.joinToString(", ")}}")
            }
        }
    }

    /** Claude's server as the fields of a Codex `mcp_servers` inline table. */
    private fun toCodex(name: String, server: JsonObject): List<String>? = when (transport(server)) {
        "stdio" -> server["command"].str()?.let { command ->
            buildList {
                add("command=${Spawn.tomlString(command)}")
                strings(server["args"])?.let { add("args=${tomlArray(it)}") }
                stringMap(server["env"])?.let { add("env=${tomlTable(it)}") }
                server["cwd"].str()?.let { add("cwd=${Spawn.tomlString(it)}") }
            }
        }
        "http" -> server["url"].str()?.let { url ->
            buildList {
                add("url=${Spawn.tomlString(url)}")
                stringMap(server["headers"])?.let { add("http_headers=${tomlTable(it)}") }
            }
        }
        else -> {
            Log.info("mcp server '$name' left out of codex: ${transport(server)} is not supported")
            null
        }
    }

    /**
     * Merges [servers] into `agy`'s config in [home], plus the user's own `agy` servers.
     *
     * The names nop wrote last time are kept beside the config, so a server the user removes from
     * `~/.claude.json` is removed here too, while one added to this home by hand (`agy mcp add`) is
     * never touched, not even when a server of the same name appears in the user's config. A home
     * that is the user's own is left alone: that CLI already reads the user's config, and nop has
     * no business adding to it.
     */
    internal fun installForAntigravity(
        home: Path,
        servers: Map<String, JsonObject>,
        userHome: Path = Path.of(System.getProperty("user.home")),
    ) {
        val file = agyConfig(home)
        val userFile = agyConfig(userHome)
        if (file.toAbsolutePath().normalize() == userFile.toAbsolutePath().normalize()) return

        val wanted = buildMap {
            readObject(userFile)?.get("mcpServers").obj()?.forEach { (k, v) -> v.obj()?.let { put(k, it) } }
            servers.forEach { (name, server) -> toAgy(name, server)?.let { put(name, it) } }
        }
        val existing = if (Files.isRegularFile(file)) {
            readObject(file) ?: return Log.warn("left $file alone: it is not readable as JSON")
        } else {
            JsonObject(emptyMap())
        }
        val managedFile = file.resolveSibling(MANAGED_FILE)
        val managedBefore = readArray(managedFile)
        val theirs = existing["mcpServers"].obj().orEmpty().filterKeys { it !in managedBefore }
        val ours = wanted.filterKeys { it !in theirs }
        val next = buildJsonObject {
            existing.forEach { (k, v) -> if (k != "mcpServers") put(k, v) }
            put("mcpServers", JsonObject(theirs + ours))
        }
        if (next == existing && ours.keys == managedBefore) return

        runCatching {
            writeAtomically(file, json.encodeToString(JsonObject.serializer(), next))
            writeAtomically(managedFile, json.encodeToString(JsonArray.serializer(), JsonArray(ours.keys.map(::JsonPrimitive))))
        }.onFailure { Log.warn("could not give $home the user's MCP servers: $it") }
    }

    private const val MANAGED_FILE = "nop-mcp-servers.json"

    private val BARE_KEY = Regex("[A-Za-z0-9_-]+")

    private fun agyConfig(home: Path): Path =
        home.resolve(".gemini").resolve("config").resolve("mcp_config.json")

    /** Claude's server in `agy`'s shape: `serverUrl` for HTTP, and no SSE, which it does not take. */
    private fun toAgy(name: String, server: JsonObject): JsonObject? = when (transport(server)) {
        "stdio" -> buildJsonObject {
            put("command", server["command"].str() ?: return null)
            server["args"].arr()?.let { put("args", it) }
            server["env"].obj()?.let { put("env", it) }
        }
        "http" -> buildJsonObject {
            put("serverUrl", server["url"].str() ?: return null)
            server["headers"].obj()?.let { put("headers", it) }
        }
        else -> {
            Log.info("mcp server '$name' left out of agy: ${transport(server)} is not supported")
            null
        }
    }

    /** Claude omits `type` for a stdio server, and accepts `streamable-http` for `http`. */
    private fun transport(server: JsonObject): String = when (val type = server["type"].str()) {
        null -> if (server["command"] != null) "stdio" else "http"
        "streamable-http" -> "http"
        else -> type
    }

    private fun servers(element: kotlinx.serialization.json.JsonElement?): Map<String, JsonObject> =
        element.obj().orEmpty().mapNotNull { (k, v) -> v.obj()?.let { k to it } }.toMap()

    private fun strings(element: kotlinx.serialization.json.JsonElement?): List<String>? =
        element.arr()?.mapNotNull { it.str() }?.takeIf { it.isNotEmpty() }

    private fun stringMap(element: kotlinx.serialization.json.JsonElement?): Map<String, String>? =
        element.obj()?.mapNotNull { (k, v) -> v.str()?.let { k to it } }?.toMap()?.takeIf { it.isNotEmpty() }

    private fun tomlArray(items: List<String>) = items.joinToString(", ", "[", "]") { Spawn.tomlString(it) }

    private fun tomlTable(entries: Map<String, String>) =
        entries.entries.joinToString(", ", "{", "}") { (k, v) -> "${Spawn.tomlString(k)}=${Spawn.tomlString(v)}" }

    private fun readObject(file: Path): JsonObject? = runCatching {
        if (!Files.isRegularFile(file)) return null
        json.parseToJsonElement(Files.readString(file)).obj()
    }.getOrNull()

    private fun readArray(file: Path): Set<String> = runCatching {
        if (!Files.isRegularFile(file)) return emptySet()
        json.parseToJsonElement(Files.readString(file)).arr().orEmpty().mapNotNull { it.str() }.toSet()
    }.getOrDefault(emptySet())

    private fun writeAtomically(file: Path, text: String) {
        OwnerOnly.directory(file.parent)
        val tmp = Files.createTempFile(file.parent, ".nop-mcp", ".json")
        Files.writeString(tmp, text)
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun digest(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).take(8).joinToString("") { "%02x".format(it) }
}
