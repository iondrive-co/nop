package iondrive.nop.agent

import iondrive.nop.Log
import iondrive.nop.agent.transcript.ClaudeTailer
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

/**
 * The sessions a vendor CLI has on this project, read out of the vendor's own store.
 *
 * nop keeps an event log of every session *it* started, and the picker was built on that alone —
 * which quietly made "sessions on this project" mean "sessions nop ran". It is not the same set. A
 * `claude` run from a shell in the same checkout is a session on this project by any reading the
 * user would recognise, and it was invisible here; so was everything from before nop was installed.
 *
 * The two sets cannot be merged by moving files around, because of how the CLIs are built: Claude
 * Code files a transcript under `$CLAUDE_CONFIG_DIR/projects/<slug>/`, and that same directory is
 * where its credentials live. Pointing it somewhere per-account — which is the whole mechanism
 * behind running three accounts side by side — therefore splits the transcripts as a side effect,
 * with no separate setting to undo it. So nop reads every store instead of trying to make one:
 * whichever account it is, and the default directory a plain `claude` uses, are all just
 * directories with a `projects/` in them, and a session in any of them can be resumed by launching
 * the CLI against the directory it was found in. That is what [PastSession.home] carries.
 *
 * Claude only, for now. Codex files its rollouts by date rather than by project, so finding the
 * ones belonging to a checkout means walking the whole tree and reading the head of every file —
 * see [iondrive.nop.agent.transcript.CodexTailer.locate], which does exactly that once per run and
 * is already the slowest thing a run does. Codex sessions still reach the picker through nop's own
 * event log, the way every session did before this.
 */
object NativeSessions {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * How far into a transcript a title is looked for.
     *
     * The CLI writes its own name for the session a turn or two in, so the head is where it is or
     * nowhere worth paying for: the alternative is reading every transcript in the project in full,
     * every time the picker is drawn, to relabel a row that already has a usable name.
     *
     * A busy project has hundreds of these — 199 across two checkouts on the machine this was
     * written on — so what the head costs is multiplied by that. Hence the pre-filter in
     * [summarise]: a transcript line carrying a tool result is tens of kilobytes, and decoding one
     * to discover it is not a title is the expensive way to learn nothing.
     */
    private const val HEAD_LINES = 200

    /**
     * One directory a vendor CLI keeps its state in: the account it belongs to, or the default one
     * that a CLI run from a shell uses.
     */
    data class Store(
        /** What the picker calls it — an account's name, or something naming the default directory. */
        val label: String,
        val dir: Path,
    )

    /**
     * Every Claude session filed under [project] across [stores], newest first.
     *
     * Failures are per store and per file: a directory that cannot be listed, or a transcript whose
     * first lines are unreadable, costs its own row rather than the whole list. The picker is a way
     * back into work, and half of it is worth more than an error.
     */
    fun claude(project: Path, stores: List<Store>): List<PastSession> {
        val slug = ClaudeTailer.slug(project)
        val projectPath = project.toAbsolutePath().normalize().toString()
        return stores.flatMap { store ->
            val dir = store.dir.resolve("projects").resolve(slug)
            val files = runCatching {
                Files.list(dir).use { stream ->
                    stream.filter { it.fileName.toString().endsWith(".jsonl") }.toList()
                }
            }.getOrElse { emptyList() }
            files.mapNotNull { file -> summarise(file, store, projectPath) }
        }.sortedByDescending { it.startedAt }
    }

    /**
     * The stores to read: one per configured Anthropic account, plus the directory a CLI launched
     * from a shell would use when that is not already one of them.
     *
     * The default directory earns its place precisely because it is the one nop never launches: it
     * holds the sessions the user did *not* do in nop, which are the ones they cannot currently get
     * back to from here.
     */
    fun stores(accounts: List<Account>): List<Store> {
        val configured = accounts
            .filter { it.provider == Provider.Anthropic }
            .map { Store(it.name, it.homePath.toAbsolutePath().normalize()) }
        val fallback = defaultConfigDir()
        return if (configured.any { it.dir == fallback }) {
            configured
        } else {
            configured + Store(DEFAULT_STORE_LABEL, fallback)
        }
    }

    /**
     * Where a `claude` with nothing set in its environment keeps its state.
     *
     * `CLAUDE_CONFIG_DIR` is read from nop's *own* environment on purpose: a user who exports it in
     * their shell profile has moved the default, and the sessions they want back are in the
     * directory their shell would have used, not in `~/.claude`.
     */
    fun defaultConfigDir(): Path {
        val configured = System.getenv("CLAUDE_CONFIG_DIR")
        val dir = if (configured.isNullOrBlank()) {
            Path.of(System.getProperty("user.home"), ".claude")
        } else {
            Path.of(configured)
        }
        return dir.toAbsolutePath().normalize()
    }

    /** What the picker calls a session that was not run under any configured account. */
    const val DEFAULT_STORE_LABEL: String = "outside nop"

    private fun summarise(file: Path, store: Store, projectPath: String): PastSession? {
        var startedAt: Long? = null
        var titled: String? = null
        var firstPrompt: String? = null

        val read = runCatching {
            Files.newBufferedReader(file).use { reader ->
                var index = 0
                for (line in reader.lineSequence()) {
                    index += 1
                    if (index > HEAD_LINES) break
                    if (line.isBlank()) continue
                    // Only three kinds of line can say anything here, and every other one in the
                    // head is an assistant turn or a tool result that is expensive to decode and
                    // has nothing in it for us. The first line is always read, because that is
                    // where the session's clock starts.
                    val interesting = index == 1 ||
                        (titled == null && ("\"ai-title\"" in line || "\"custom-title\"" in line)) ||
                        (firstPrompt == null && "\"user\"" in line)
                    if (!interesting) continue
                    val record = runCatching {
                        json.parseToJsonElement(line).obj()
                    }.getOrNull() ?: continue
                    if (startedAt == null) {
                        startedAt = record["timestamp"].str()?.let(::epochMillis)
                    }
                    when (record["type"].str()) {
                        // Both spellings, the way ClaudeTailer takes them: newer builds write
                        // `ai-title`, older ones `custom-title`.
                        "ai-title", "custom-title" ->
                            titled = titled ?: (record["aiTitle"] ?: record["title"]).str()
                                ?.takeIf { it.isNotBlank() }
                        "user" -> if (firstPrompt == null) {
                            // A plain string is the user typing; a list is the CLI handing tool
                            // results back, which is not a name for anything.
                            firstPrompt = record["message"].obj()?.get("content").str()
                                ?.takeIf { it.isNotBlank() }
                        }
                    }
                    if (titled != null && startedAt != null) break
                }
            }
        }
        if (read.isFailure) {
            Log.warn("could not read the transcript $file: ${read.exceptionOrNull()}")
            return null
        }

        val id = file.fileName.toString().removeSuffix(".jsonl")
        // A transcript with nothing in it yet is a session that has not started, not one to offer.
        val at = startedAt ?: modified(file) ?: return null
        if (titled == null && firstPrompt == null) return null

        return PastSession(
            sessionId = id,
            projectPath = projectPath,
            startedAt = at,
            title = titled ?: firstPrompt?.firstLine() ?: "Untitled session",
            lastProvider = Provider.Anthropic.id,
            lastAccount = store.label,
            lastNativeSessionId = id,
            home = store.dir.toString(),
        )
    }

    private fun modified(file: Path): Long? =
        runCatching { Files.getLastModifiedTime(file).toMillis() }.getOrNull()

    private fun epochMillis(value: String): Long? =
        runCatching { java.time.Instant.parse(value).toEpochMilli() }.getOrNull()

    private fun String.firstLine(): String =
        lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(120) ?: "Untitled session"
}
