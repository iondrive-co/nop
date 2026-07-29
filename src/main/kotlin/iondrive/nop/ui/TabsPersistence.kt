package iondrive.nop.ui

import iondrive.nop.git.CommitFile
import iondrive.nop.git.CommitFileChange
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * Disk format for restoring a project's tab strip — on the next launch, and on every switch away
 * from and back to the project (the tab state is re-keyed per project, so a switch reloads from
 * here). We persist the tab kinds that survive across runs:
 *
 *   * [Tab.FileView]   — refers to a stable absolute path
 *   * [Tab.History]    — same; the repo root is implicit (we're inside a single project)
 *   * [Tab.CommitDiff] — a (sha, path, change type) triple; a commit's content is immutable, so
 *                        this rebuilds into exactly the diff that was on screen
 *
 * Working-tree Diff tabs depend on the live [iondrive.nop.git.FileChange] blob which is recomputed
 * from git status, and Terminal tabs wrap a running PTY process — neither can be meaningfully
 * restored, so both are dropped at save time.
 *
 * Stored as TSV under the project's data dir: `kind<TAB>path<TAB>selected?`, plus
 * `<TAB>sha<TAB>changeType` for commit diffs. One tab per line; unparseable lines are skipped so a
 * partial corruption doesn't wipe the strip. [path] is absolute except for a commit diff, where it
 * is the repo-relative path git knows the file by.
 */
data class SavedTab(
    val kind: String,
    val path: String,
    val selected: Boolean,
    val sha: String? = null,
    val changeType: String? = null,
)

object TabsPersistence {
    private const val KIND_FILE = "file"
    private const val KIND_HISTORY = "history"
    private const val KIND_COMMITDIFF = "commitdiff"

    fun save(target: Path, tabs: List<Tab>, selectedId: String?) {
        val rows = tabs.mapNotNull { tab ->
            val selected = if (tab.id == selectedId) "1" else "0"
            val (kind, file) = when (tab) {
                is Tab.FileView -> KIND_FILE to tab.file
                is Tab.History -> KIND_HISTORY to tab.file
                // Repo-relative path, and the two extra columns the diff can't be rebuilt without.
                is Tab.CommitDiff -> return@mapNotNull listOf(
                    KIND_COMMITDIFF, tab.file.path, selected, tab.sha, tab.file.changeType.name,
                ).joinToString("\t")
                is Tab.Diff, is Tab.Terminal -> return@mapNotNull null
            }
            "$kind\t${file.absolutePath}\t$selected"
        }
        runCatching {
            Files.createDirectories(target.parent)
            // Empty file on no persistable tabs — easier than tracking "did we ever save" for
            // the load side. An empty file loads as an empty list.
            Files.writeString(target, rows.joinToString("\n"))
        }
    }

    fun load(source: Path): List<SavedTab> {
        if (!Files.isRegularFile(source)) return emptyList()
        val text = runCatching { Files.readString(source) }.getOrNull() ?: return emptyList()
        val out = ArrayList<SavedTab>()
        for (line in text.lines()) {
            if (line.isBlank()) continue
            val parts = line.split('\t')
            if (parts.size < 2) continue
            val kind = parts[0]
            val path = parts[1]
            val selected = parts.getOrNull(2) == "1"
            when (kind) {
                KIND_FILE, KIND_HISTORY -> out += SavedTab(kind, path, selected)
                KIND_COMMITDIFF -> {
                    // Both extra columns are mandatory for this kind — a line missing either can't
                    // name a diff, so it's dropped rather than guessed at.
                    val sha = parts.getOrNull(3)?.takeIf { it.isNotBlank() } ?: continue
                    val changeType = parts.getOrNull(4)?.takeIf { it.isNotBlank() } ?: continue
                    out += SavedTab(kind, path, selected, sha, changeType)
                }
                else -> continue
            }
        }
        return out
    }

    /**
     * Rebuilds a [TabsState] from the on-disk snapshot, filtering out anything whose working file
     * no longer exists (or is no longer a file) so a renamed/deleted file doesn't reopen as a broken
     * tab. Commit diffs are exempt — they read out of history, not the working tree.
     * Falls back to whichever tab was selected when saving; if that one didn't survive the
     * filter, leaves the last remaining tab selected (matching [TabsState.open]'s contract).
     */
    fun restore(
        state: TabsState,
        saved: List<SavedTab>,
        repoRoot: File?,
    ) {
        var preferredSelectedId: String? = null
        for (s in saved) {
            val tab: Tab = when (s.kind) {
                KIND_FILE -> Tab.FileView(File(s.path).takeIf { it.isFile } ?: continue)
                // History on a directory is valid (e.g. log for an entire role), so allow either
                // file or directory existence.
                KIND_HISTORY -> {
                    val file = File(s.path).takeIf { it.exists() } ?: continue
                    if (repoRoot == null) continue
                    Tab.History(file, repoRoot)
                }
                // Nothing to check on disk: the diff is read out of the commit, so it restores just
                // as well for a file the commit deleted or that has since been renamed away.
                KIND_COMMITDIFF -> {
                    if (repoRoot == null) continue
                    val sha = s.sha ?: continue
                    val changeType = runCatching {
                        CommitFileChange.valueOf(s.changeType ?: "")
                    }.getOrNull() ?: continue
                    Tab.CommitDiff(sha, sha.take(7), CommitFile(s.path, changeType), repoRoot)
                }
                else -> continue
            }
            // record = false: restoring last run's tabs must not count as fresh accesses.
            state.open(tab, record = false)
            if (s.selected) preferredSelectedId = tab.id
        }
        if (preferredSelectedId != null) state.select(preferredSelectedId)
    }
}
