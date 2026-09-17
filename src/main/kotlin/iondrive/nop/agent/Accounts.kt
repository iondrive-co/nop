package iondrive.nop.agent

import iondrive.nop.Log
import iondrive.nop.Settings
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * One configured vendor account: a name, the provider it belongs to, and the directory holding its
 * credentials.
 *
 * [home] is stored rather than derived from [name]. Deriving it would be tidier right up until the
 * first launch, which would then want a login nop could have inherited: the accounts already set
 * up on this machine live under `~/.chad/claude-configs/<name>` and `~/.chad/codex-homes/<name>`,
 * and pointing at them costs nothing and saves signing every one of them in again. It also keeps
 * nop from having an opinion about where someone else's credentials belong.
 *
 * [model] and [reasoning] are null when the CLI's own default should apply — that is what the
 * "default" entry in the pickers means, and it is why neither is an empty string.
 */
@Serializable
data class Account(
    val name: String,
    val provider: Provider,
    val home: String,
    val model: String? = null,
    val reasoning: String? = null,
    /**
     * The account to carry this one's work on when it runs out of usage mid-session, by name, or
     * null to be asked at the time.
     *
     * A name and not an [Account], because this is written to a config file that the accounts
     * themselves live in: a copy embedded here would be the second place a model or a home is
     * recorded, and the two would disagree the first time either was edited. Resolved through
     * [handoverTarget], which is also where a name that no longer matches anything goes quiet.
     */
    val handoverTo: String? = null,
) {
    val homePath: Path get() = Path.of(home)

    /**
     * The file this account's login is kept in. Its presence is a necessary condition for being
     * logged in, never a sufficient one — a Claude token can be there and long dead, which is why
     * [Usage] re-checks rather than trusting the file.
     *
     * Antigravity's is the one nop has to hold the CLI to: `agy` writes it there only while it
     * believes the OS keyring is unusable, which is a belief [Antigravity] keeps current.
     */
    val credentialFile: Path
        get() = when (provider) {
            Provider.Anthropic -> homePath.resolve(".credentials.json")
            Provider.OpenAI -> homePath.resolve(".codex").resolve("auth.json")
            Provider.Antigravity -> Antigravity.tokenFile(homePath)
        }
}

/**
 * Who [from] hands its work to when it hits its wall, or null when nobody has been nominated and
 * the choice belongs to the user.
 *
 * Two answers are deliberately null rather than an error. A nomination naming an account that has
 * since been removed resolves to nothing, because a handover that cannot happen should end up in
 * front of the user rather than in a log; and an account nominating *itself* resolves to nothing
 * too, since starting the same exhausted account again is the one move that certainly does not
 * help. The settings dialog prevents both, so this is the belt behind that brace — an `agent.json`
 * edited by hand, or written by a version that allowed it, must not be able to wedge a session.
 */
fun List<Account>.handoverTarget(from: Account): Account? {
    val nominated = from.handoverTo?.takeIf { it != from.name } ?: return null
    return firstOrNull { it.name == nominated }
}

/**
 * Every account nop knows about.
 *
 * There is deliberately no passphrase here, and the predecessor's is not carried over. chad
 * prompted for one at startup, but tracing it through the source shows it unlocked nothing: the
 * password it verified was handed to `launch_cli_ui(password=…)`, whose own docstring calls it
 * "unused, kept for compatibility"; the one encrypted per-account field was written as an empty
 * string with an empty password for every OAuth account; and the function that decrypted it had no
 * callers.
 *
 * A gate on nop would be weaker still than that. Every credential in play belongs to a vendor CLI
 * that reads it straight off disk, so anyone who can reach this machine can run `claude` in a
 * terminal and be signed in as you without nop's involvement. A prompt in front of nop's own UI
 * would buy the appearance of protection and none of it.
 */
@Serializable
data class AgentConfig(
    val accounts: List<Account> = emptyList(),
)

/**
 * Reads and writes `$XDG_CONFIG_HOME/nop/agent.json`.
 *
 * A separate file from nop's `state`, which is flat `key=value` and would have to grow an escaping
 * scheme to hold a list of records. Tests redirect it by setting [Settings.configRoot], the same
 * hook the rest of nop's persistence uses.
 */
object Accounts {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    val configFile: Path
        get() = Settings.configRoot.resolve("nop").resolve("agent.json")

    /** Where a brand-new account's credential directory is made. */
    fun defaultHome(name: String): Path = dataRoot().resolve("homes").resolve(name)

    /**
     * `~/.local/share/nop/agent` — session logs, handoff files and the homes of accounts nop
     * created itself. Data rather than config, so it follows `XDG_DATA_HOME` and not the config
     * root; tests point `XDG_DATA_HOME` at a temp directory to keep out of the real one.
     */
    fun dataRoot(): Path {
        val xdg = System.getenv("XDG_DATA_HOME")
        val base = if (xdg.isNullOrBlank()) {
            Path.of(System.getProperty("user.home"), ".local", "share")
        } else {
            Path.of(xdg)
        }
        return base.resolve("nop").resolve("agent")
    }

    /**
     * The stored config, or — the first time, when there is no file yet — one seeded from whatever
     * vendor logins are already on this machine ([discover]). Nothing is written by looking: a user
     * who wants none of the discovered accounts should not have to delete a file they never made.
     */
    fun load(): AgentConfig {
        val f = configFile
        if (!Files.isRegularFile(f)) return AgentConfig(accounts = discover())
        val text = runCatching { Files.readString(f) }.getOrNull() ?: return AgentConfig()
        return runCatching { json.decodeFromString<AgentConfig>(text) }
            .onFailure { Log.warn("agent.json is unreadable, ignoring it: $it") }
            .getOrDefault(AgentConfig())
    }

    /**
     * Writes through a temp file and an atomic rename. A half-written `agent.json` would cost the
     * user every account they had configured, and the window for it is a crash or a full disk —
     * both of which happen.
     */
    fun save(config: AgentConfig) {
        val f = configFile
        runCatching {
            Files.createDirectories(f.parent)
            // createTempFile is already owner-only on POSIX, and the move carries that across, so
            // the config is never briefly world-readable under a name anything would look for.
            val tmp = Files.createTempFile(f.parent, "agent", ".json")
            Files.writeString(tmp, json.encodeToString(config))
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            OwnerOnly.tighten(f)
        }.onFailure { Log.warn("could not save agent.json: $it") }
    }

    /**
     * The accounts to start from, the first time nop is asked and has no config of its own.
     *
     * Read from `~/.chad.conf` — the list its predecessor's user actually curated — and not from
     * whatever directories happen to exist. Scanning `~/.chad/claude-configs` and
     * `~/.chad/codex-homes` was the obvious thing and the wrong one: those hold every home ever
     * made, abandoned experiments and one-off test accounts included, so the picker opened on
     * fifteen entries of which four were real. A directory is not an account; a configured account
     * is. Only the name, provider and model settings are read — the encrypted key beside them is
     * never touched, because nop has no use for it and no way to read it.
     *
     * With no chad on the machine, the fallback is the CLI's own default login: the one you get
     * from running `claude` or `codex` in a plain terminal.
     *
     * A one-time seed, not a live view. Once [save] has written a config, that file is the list,
     * and an account the user deleted must stay deleted.
     */
    fun discover(): List<Account> {
        val home = Path.of(System.getProperty("user.home"))
        return fromChadConfig(home).ifEmpty { defaultLogins(home) }
    }

    /** The accounts `~/.chad.conf` declares, for the providers nop can actually run. */
    private fun fromChadConfig(home: Path): List<Account> {
        val file = home.resolve(".chad.conf")
        if (!Files.isRegularFile(file)) return emptyList()
        val accounts = runCatching {
            Json.parseToJsonElement(Files.readString(file)).jsonObject["accounts"].obj()
        }.getOrNull() ?: return emptyList()

        return accounts.mapNotNull { (name, value) ->
            val entry = value.obj() ?: return@mapNotNull null
            // Anything nop cannot run is silently left out rather than listed and then refused.
            // chad also configured qwen, kimi and a local llama-server, none of which nop launches.
            val provider = Provider.byId(entry["provider"].str()) ?: return@mapNotNull null
            Account(
                name = name,
                provider = provider,
                home = chadHome(home, provider, name).toString(),
                model = entry["model"].str()?.takeIf { it != DEFAULT_CHOICE },
                reasoning = entry["reasoning"].str()?.takeIf { it != DEFAULT_CHOICE },
            )
        }.sortedBy { it.name }
    }

    /**
     * Where chad kept each provider's per-account credentials.
     *
     * The antigravity homes are the odd ones: chad could not point the CLI at a credential file, so
     * it kept its own copy as `credential.json` at the root of the home and wrote it into the
     * keyring before each run. Pointing at the same home is still right — [Antigravity] moves that
     * copy to where `agy` now reads it, which is what saves signing the account in again.
     */
    private fun chadHome(home: Path, provider: Provider, name: String): Path = when (provider) {
        Provider.Anthropic -> home.resolve(".chad/claude-configs").resolve(name)
        Provider.OpenAI -> home.resolve(".chad/codex-homes").resolve(name)
        Provider.Antigravity -> home.resolve(".chad/antigravity-homes").resolve(name)
    }

    /**
     * This machine's own vendor logins. Offered only when they exist, so a CLI that has never been
     * signed in doesn't arrive as an account that cannot be launched.
     */
    private fun defaultLogins(home: Path): List<Account> = listOf(
        Account("claude", Provider.Anthropic, home.resolve(".claude").toString()),
        Account("codex", Provider.OpenAI, home.toString()),
    ).filter { Files.isRegularFile(it.credentialFile) }
}