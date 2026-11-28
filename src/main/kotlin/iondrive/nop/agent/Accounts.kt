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
    /**
     * The model [handoverTo] runs when it takes this one's work on, or null for whatever that
     * account is set to run. Kept here rather than on the other account because it is a choice about
     * this handover: the account taking over may be set to a cheaper model for work started on it.
     */
    val handoverModel: String? = null,
    /**
     * Whether to keep this account's work where it is when it runs out: leave the CLI at its wall,
     * and once the window has reset, type a message into it telling it to carry on. Takes the place
     * of [handoverTo], which [handoverTarget] ignores while this is set — the conversation stays
     * whole, at the cost of the wait. See [AgentSession.onQuotaWall].
     */
    val resumeAfterReset: Boolean = false,
    val endpoint: String? = null,
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
            Provider.Anthropic, Provider.Local -> homePath.resolve(".credentials.json")
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
 *
 * The account comes back running [Account.handoverModel] when [from] names one.
 */
fun List<Account>.handoverTarget(from: Account): Account? {
    val currentFrom = firstOrNull { it.name == from.name } ?: from
    if (currentFrom.resumeAfterReset) return null
    val nominated = currentFrom.handoverTo?.takeIf { it != currentFrom.name } ?: return null
    val target = firstOrNull { it.name == nominated } ?: return null
    if (target.provider == Provider.Local) return target
    val model = currentFrom.handoverModel ?: return target
    return target.copy(model = model.takeIf { it != DEFAULT_CHOICE })
}

/**
 * Whether [from] waits out its wall and is told to resume, rather than handing over or asking. Read
 * from [this] list for the same reason as [handoverTarget]: a change made mid-session applies.
 */
fun List<Account>.resumesAfterReset(from: Account): Boolean =
    (firstOrNull { it.name == from.name } ?: from).resumeAfterReset

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
    /** The folder agent sessions are backed up to, or null when nothing is. See [Backup]. */
    val backupDir: String? = null,
    /** The store the chosen projects' agent tabs are kept in step through, or null when none is. See [ProjectSync]. */
    val sync: SyncRemote? = null,
    /**
     * The repository roots on this machine whose agent tabs go to [sync]; every other project keeps
     * its tabs here. Chosen one by one, since a busy project sends a lot. Each machine chooses for
     * itself, as the paths are its own.
     */
    val syncProjects: List<String> = emptyList(),
) {
    /** Whether the repository at [root] is one of the [syncProjects]. */
    fun syncs(root: Path): Boolean {
        val want = normalRoot(root)
        return syncProjects.any { runCatching { normalRoot(Path.of(it)) == want }.getOrDefault(false) }
    }

    /** This config with the repository at [root] synced or not, as [on] says. */
    fun withSynced(root: Path, on: Boolean): AgentConfig {
        val want = normalRoot(root)
        val others = syncProjects.filterNot { runCatching { normalRoot(Path.of(it)) == want }.getOrDefault(false) }
        return copy(syncProjects = if (on) others + want.toString() else others)
    }

    /** The remote [root]'s tabs go to: [sync] when it is one of the [syncProjects], otherwise none. */
    fun syncFor(root: Path): SyncRemote? = sync?.takeIf { syncs(root) }

    /**
     * This config without the account called [name], and without anybody's nomination of it. A
     * nomination left pointing at a removed account is a handover that silently does not happen at
     * the one moment it was wanted, which is worse than never having set it.
     *
     * Only the entry goes: the account's home, with its sign-in and every conversation it has had,
     * stays on disk.
     */
    fun withoutAccount(name: String): AgentConfig = copy(
        accounts = accounts
            .filterNot { it.name == name }
            .map { if (it.handoverTo == name) it.copy(handoverTo = null, handoverModel = null) else it },
    )
}

private fun normalRoot(path: Path): Path = path.toAbsolutePath().normalize()

/**
 * The S3-compatible store (Cloudflare R2, MinIO, Backblaze B2, AWS S3) that holds each project's
 * agent tabs, so another machine can show them and take them over. nop only calls out to it, so no
 * machine has to accept a connection. See [SyncLink].
 *
 * [endpoint] is the store's address (`https://<account>.r2.cloudflarestorage.com`), [bucket] the
 * bucket, and [prefix] the folder in it everything is kept under. [accessKey] and [secretKey] are
 * the key pair the store issued, best limited to that one bucket. [syncKey] seals everything nop
 * puts there, so the store sees no conversation; every machine must have the same one.
 *
 * [rateLimit] caps what each copy sends or fetches, in KiB a second, so a project's first copy
 * cannot fill the machine's uplink; 0 leaves it uncapped.
 *
 * [Accounts.save] keeps [secretKey] and [syncKey] in [Accounts.syncKeysFile], not in `agent.json`,
 * so a backup carries neither.
 *
 * A config written by a nop that synced over ssh holds only an old `target`; it decodes with the
 * store left blank, and [SyncLink.problem] asks for one.
 */
@Serializable
data class SyncRemote(
    val endpoint: String = "",
    val region: String = DEFAULT_REGION,
    val bucket: String = "",
    val prefix: String = DEFAULT_PREFIX,
    val accessKey: String = "",
    val secretKey: String = "",
    val syncKey: String = "",
    val rateLimit: Int = DEFAULT_RATE_LIMIT,
) {
    /** Where the tabs go, as the user is shown it: the bucket and the folder in it. */
    val target: String
        get() = prefix.trim('/').let { if (it.isEmpty()) bucket else "$bucket/$it" }

    /** Leaves the keys out, so a log line or a failed test never shows them. */
    override fun toString(): String =
        "SyncRemote(endpoint=$endpoint, region=$region, bucket=$bucket, prefix=$prefix, " +
            "accessKey=${if (accessKey.isEmpty()) "" else "…"}, secretKey=${if (secretKey.isEmpty()) "" else "…"}, " +
            "syncKey=${if (syncKey.isEmpty()) "" else "…"}, rateLimit=$rateLimit)"

    companion object {
        const val DEFAULT_REGION = "auto"
        const val DEFAULT_PREFIX = "nop-sync"
        const val DEFAULT_RATE_LIMIT = 500
    }
}

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
        val config = runCatching { json.decodeFromString<AgentConfig>(text) }
            .onFailure { Log.warn("agent.json is unreadable, ignoring it: $it") }
            .getOrDefault(AgentConfig())
        val sync = config.sync ?: return config
        val keys = readSyncKeys() ?: return config
        return config.copy(
            sync = sync.copy(
                secretKey = keys.secretKey.ifEmpty { sync.secretKey },
                syncKey = keys.syncKey.ifEmpty { sync.syncKey },
            ),
        )
    }

    /**
     * Where [save] keeps the sync store's secret key and the sync key, apart from [configFile]:
     * a backup copies `agent.json` to the backup folder, and keeps no key there, as it keeps no login.
     */
    val syncKeysFile: Path
        get() = configFile.resolveSibling("sync-keys.json")

    @Serializable
    private data class SyncKeys(val secretKey: String = "", val syncKey: String = "")

    private fun readSyncKeys(): SyncKeys? {
        val f = syncKeysFile
        if (!Files.isRegularFile(f)) return null
        return runCatching { json.decodeFromString<SyncKeys>(Files.readString(f)) }
            .onFailure { Log.warn("sync-keys.json is unreadable, ignoring it: $it") }
            .getOrNull()
    }

    /** Writes [text] to [f] through an owner-only temp file and an atomic rename. */
    private fun writePrivately(f: Path, text: String) {
        // createTempFile is already owner-only on POSIX, and the move carries that across, so
        // the file is never briefly world-readable under a name anything would look for.
        val tmp = Files.createTempFile(f.parent, f.fileName.toString().substringBefore('.'), ".json")
        Files.writeString(tmp, text)
        Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        OwnerOnly.tighten(f)
    }

    /**
     * Writes through a temp file and an atomic rename. A half-written `agent.json` would cost the
     * user every account they had configured, and the window for it is a crash or a full disk —
     * both of which happen.
     *
     * The sync keys go to [syncKeysFile] first, so a crash between the two writes loses neither; it
     * is removed once sync is turned off.
     */
    fun save(config: AgentConfig) {
        val f = configFile
        runCatching {
            Files.createDirectories(f.parent)
            val sync = config.sync
            if (sync == null) {
                Files.deleteIfExists(syncKeysFile)
            } else if (sync.secretKey.isNotEmpty() || sync.syncKey.isNotEmpty()) {
                writePrivately(syncKeysFile, json.encodeToString(SyncKeys(sync.secretKey, sync.syncKey)))
            }
            val stored = config.copy(sync = sync?.copy(secretKey = "", syncKey = ""))
            writePrivately(f, json.encodeToString(stored))
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
