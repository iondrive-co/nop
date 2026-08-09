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
 *
 * Tab groups are written as `group<TAB>name<TAB>active?<TAB>collapsed?` rows and own the tab rows
 * that follow them, the same way a group header owns the tabs to its right on screen. A file with no
 * group rows is one written by a pre-groups build: everything in it restores into the default group.
 */
data class SavedTab(
    val kind: String,
    val path: String,
    val selected: Boolean,
    val sha: String? = null,
    val changeType: String? = null,
    val collapsed: Boolean = false,
)

/**
 * Immutable copy of everything the strip persists, taken on the UI thread by
 * [TabsState.snapshot] so the write itself can run on an IO thread.
 */
data class TabsSnapshot(
    val groups: List<TabGroup>,
    val tabs: List<Tab>,
    val groupOf: Map<String, Long>,
    val selectedId: String?,
    val activeGroupId: Long,
)

object TabsPersistence {
    private const val KIND_FILE = "file"
    private const val KIND_HISTORY = "history"
    private const val KIND_COMMITDIFF = "commitdiff"
    private const val KIND_GROUP = "group"

    fun save(target: Path, snapshot: TabsSnapshot) {
        val rows = buildList {
            for (group in snapshot.groups) {
                add(
                    listOf(
                        KIND_GROUP,
                        // The row format is one line of tab-separated fields, so a name carrying
                        // either is flattened rather than allowed to split the row in two.
                        group.name.replace('\t', ' ').replace('\n', ' ').replace('\r', ' '),
                        if (group.id == snapshot.activeGroupId) "1" else "0",
                        if (group.collapsed) "1" else "0",
                    ).joinToString("\t"),
                )
                for (tab in snapshot.tabs) {
                    if (snapshot.groupOf[tab.id] != group.id) continue
                    add(encodeTab(tab, snapshot.selectedId) ?: continue)
                }
            }
        }
        runCatching {
            Files.createDirectories(target.parent)
            // Empty file on no persistable tabs — easier than tracking "did we ever save" for
            // the load side. An empty file loads as an empty list.
            Files.writeString(target, rows.joinToString("\n"))
        }
    }

    /** One tab's row, or null for the kinds that can't outlive the session. */
    private fun encodeTab(tab: Tab, selectedId: String?): String? {
        val selected = if (tab.id == selectedId) "1" else "0"
        val (kind, file) = when (tab) {
            is Tab.FileView -> KIND_FILE to tab.file
            is Tab.History -> KIND_HISTORY to tab.file
            // Repo-relative path, and the two extra columns the diff can't be rebuilt without.
            is Tab.CommitDiff -> return listOf(
                KIND_COMMITDIFF, tab.file.path, selected, tab.sha, tab.file.changeType.name,
            ).joinToString("\t")
            is Tab.Diff, is Tab.Terminal -> return null
        }
        return "$kind\t${file.absolutePath}\t$selected"
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
                // A group's "selected" column means "this is the group new tabs open into".
                KIND_GROUP -> out += SavedTab(kind, path, selected, collapsed = parts.getOrNull(3) == "1")
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
     *
     * Each tab goes back into the group whose row precedes it. Collapsed state is applied last,
     * because opening a tab unfolds the group it lands in.
     */
    fun restore(
        state: TabsState,
        saved: List<SavedTab>,
        repoRoot: File?,
    ) {
        val groupRows = saved.filter { it.kind == KIND_GROUP }
        // No group rows means a file from a pre-groups build: everything restores into the default.
        val groups = if (groupRows.isEmpty()) state.groups else state.replaceGroups(groupRows.map { it.path })
        var preferredSelectedId: String? = null
        var activeGroupId = groups.firstOrNull()?.id
        var groupIdx = -1
        for (s in saved) {
            if (s.kind == KIND_GROUP) {
                groupIdx++
                val group = groups.getOrNull(groupIdx) ?: continue
                state.selectGroup(group.id)
                if (s.selected) activeGroupId = group.id
                continue
            }
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
        groupRows.forEachIndexed { i, row ->
            if (row.collapsed) groups.getOrNull(i)?.let { state.setCollapsed(it.id, collapsed = true) }
        }
        activeGroupId?.let { state.selectGroup(it) }
    }
}
