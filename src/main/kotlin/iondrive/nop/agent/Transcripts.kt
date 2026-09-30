package iondrive.nop.agent

import iondrive.nop.agent.transcript.ClaudeTailer
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * Where each vendor keeps the transcript its resume reads back, and whether a given conversation's
 * is still there.
 *
 * A resume needs that file and nothing of nop's: the CLI reads its own transcript and no other. With
 * it gone — its home deleted, or Claude Code's own cleanup having taken it after 30 days — the CLI
 * starts, says it has no such conversation and exits, and resuming again does the same. Asking first
 * is what lets nop fetch it back from the [Backup] before the CLI looks, and say plainly when it
 * cannot.
 */
object Transcripts {

    /**
     * The file [id]'s conversation is kept in under [home], or null when there is none. Always null
     * for Antigravity, whose conversations are databases nop does not read — see [canTell].
     */
    fun find(provider: Provider, home: Path, projectDir: File, id: String): Path? = when (provider) {
        Provider.Anthropic -> claude(home, projectDir, id)
        Provider.OpenAI -> codex(home, id)
        Provider.Antigravity -> null
    }

    /** Whether [find] can answer for [provider] at all. */
    fun canTell(provider: Provider): Boolean = provider != Provider.Antigravity

    /** True when [account]'s CLI would be asked to resume [id] with nothing to resume it from. */
    fun missing(account: Account, projectDir: File, id: String): Boolean =
        canTell(account.provider) && find(account.provider, account.homePath, projectDir, id) == null

    /**
     * Claude files a conversation under the directory it was started in, so that is looked at first,
     * and every other project directory after it: the same id is the same conversation wherever it
     * is filed.
     */
    private fun claude(home: Path, projectDir: File, id: String): Path? {
        val projects = home.resolve("projects")
        val name = "$id.jsonl"
        val expected = projects.resolve(ClaudeTailer.slug(projectDir.toPath())).resolve(name)
        if (Files.isRegularFile(expected)) return expected
        if (!Files.isDirectory(projects)) return null
        return runCatching {
            Files.list(projects).use { dirs ->
                dirs.map { it.resolve(name) }.filter { Files.isRegularFile(it) }.findFirst().orElse(null)
            }
        }.getOrNull()
    }

    /** Codex files its rollouts by date: `.codex/sessions/YYYY/MM/DD/rollout-<time>-<id>.jsonl`. */
    private fun codex(home: Path, id: String): Path? {
        val root = home.resolve(".codex").resolve("sessions")
        if (!Files.isDirectory(root)) return null
        return runCatching {
            Files.walk(root).use { stream ->
                stream.filter { path ->
                    val name = path.fileName.toString()
                    name.startsWith("rollout-") && name.endsWith("-$id.jsonl") && Files.isRegularFile(path)
                }.findFirst().orElse(null)
            }
        }.getOrNull()
    }
}
