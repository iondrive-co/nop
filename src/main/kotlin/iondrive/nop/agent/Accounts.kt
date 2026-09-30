package iondrive.nop.agent

import iondrive.nop.Log
import iondrive.nop.Settings
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.CopyOnWriteArrayList

/**
 * One configured vendor account: a name, the provider it belongs to, and the directory holding its
 * credentials.
 *
 * [home] is stored rather than derived from [name], because a home is not only a login: Claude Code
 * files every conversation an account has had under it, and moving one is a decision, not a
 * rename. A new account's home is made under nop's own data directory ([Accounts.defaultHome]).
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
 * The nomination is read from the account as it stands in [this] list (the active configuration)
 * rather than from [from] directly, so a change made in the settings dialog mid-session applies to
 * sessions that were already running when the change was made.
 *
 * Two answers are deliberately null rather than an error. A nomination naming an account that has
 * since been removed resolves to nothing, because a handover that cannot happen should end up in
 * front of the user rather than in a log; and an account nominating *itself* resolves to nothing
 * too, since starting the same exhausted account again is the one move that certainly does not
 * help. The settings dialog prevents both, so this is the belt behind that brace — an `agent.json`
 * edited by hand, or written by a version that allowed it, must not be able to wedge a session.
 */
fun List<Account>.handoverTarget(from: Account): Account? {
    val currentFrom = firstOrNull { it.name == from.name } ?: from
    val nominated = currentFrom.handoverTo?.takeIf { it != currentFrom.name } ?: return null
    return firstOrNull { it.name == nominated }
}

/**
 * Every account nop knows about.
 *
 * There is deliberately no passphrase here: a gate on nop would protect nothing. Every credential
 * in play belongs to a vendor CLI that reads it straight off disk, so anyone who can reach the
 * machine can run `claude` in a
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

    private val listeners = CopyOnWriteArrayList<(AgentConfig) -> Unit>()

    /**
     * Registers a callback notified whenever [save] successfully writes a new configuration.
     * Returns an unsubscribe lambda.
     */
    fun addChangeListener(listener: (AgentConfig) -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }

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
     * vendor logins are already on the machine ([discover]). Nothing is written by looking: a user
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
        }.onSuccess {
            listeners.forEach { it(config) }
        }.onFailure { Log.warn("could not save agent.json: $it") }
    }

    /**
     * The accounts to start from, the first time nop is asked and has no config of its own: the
     * CLIs' own default logins, the ones you get from running `claude` or `codex` in a plain
     * terminal.
     *
     * A one-time seed, not a live view. Once [save] has written a config, that file is the list,
     * and an account the user deleted must stay deleted.
     */
    fun discover(): List<Account> = defaultLogins(Path.of(System.getProperty("user.home")))

    /**
     * The CLIs' own default logins. Offered only when they exist, so a CLI that has never been
     * signed in doesn't arrive as an account that cannot be launched.
     */
    private fun defaultLogins(home: Path): List<Account> = listOf(
        Account("claude", Provider.Anthropic, home.resolve(".claude").toString()),
        Account("codex", Provider.OpenAI, home.toString()),
    ).filter { Files.isRegularFile(it.credentialFile) }
}