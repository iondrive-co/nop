package iondrive.nop.ui

import iondrive.nop.git.ChangeKind
import iondrive.nop.git.CommitFile
import iondrive.nop.git.CommitFileChange
import iondrive.nop.git.FileChange
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
 *   * [Tab.LocalHistory] — an absolute path, like [Tab.History]
 *   * [Tab.LocalDiff]  — an absolute path plus the revision's timestamp, which is how a local-history
 *                        revision is named on disk (see [iondrive.nop.history.LocalHistory])
 *   * [Tab.RevisionDiff] — an absolute path plus the sha it is compared against; both sides are
 *                        re-read (commit and working file) when the tab is composed
 *   * [Tab.Diff]       — a working-tree diff: the repo-relative path plus the [ChangeKind] git saw,
 *                        which is all [iondrive.nop.ui.DiffView] needs — both sides are re-read
 *                        from HEAD and the working file when the tab is composed. The kind is a
 *                        snapshot: if git's view of the file moved on while nop was elsewhere
 *                        (committed, staged, deleted), the restored tab shows the file's state
 *                        against HEAD as it is now, which is what a reopen would have shown anyway.
 * *
 * Stored as TSV under the project's data dir: `kind<TAB>path<TAB>selected?`, plus
 * `<TAB>sha<TAB>changeType` for commit diffs, `<TAB>timestamp` for local ones, `<TAB>sha` for
 * revision diffs and `<TAB>changeKind` for working-tree diffs. One tab per line; unparseable lines
 * are skipped so a partial corruption doesn't wipe the strip. [path] is absolute except for the
 * commit and working-tree diffs, where it is the repo-relative path git knows the file by.
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
    private const val KIND_LOCALHISTORY = "localhistory"
    private const val KIND_LOCALDIFF = "localdiff"
    private const val KIND_REVISIONDIFF = "revisiondiff"
    private const val KIND_DIFF = "diff"
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
                    add(encodeTab(tab, snapshot.selectedId))
                }
            }
        }
        runCatching {
            Files.createDirectories(target.parent)
            // Empty file on no tabs — easier than tracking "did we ever save" for the load
            // side. An empty file loads as an empty list.
            Files.writeString(target, rows.joinToString("\n"))
        }
    }

    /** One tab's row. */
    private fun encodeTab(tab: Tab, selectedId: String?): String {
        val selected = if (tab.id == selectedId) "1" else "0"
        val (kind, file) = when (tab) {
            is Tab.FileView -> KIND_FILE to tab.file
            is Tab.History -> KIND_HISTORY to tab.file
            is Tab.LocalHistory -> KIND_LOCALHISTORY to tab.file
            // The timestamp *is* the revision's name in local history, so it restores by lookup.
            is Tab.LocalDiff -> return listOf(
                KIND_LOCALDIFF, tab.file.absolutePath, selected, tab.timestampMillis.toString(),
            ).joinToString("\t")
            // An absolute path (the working file is the diff's right-hand side) plus the sha its
            // left-hand side is read out of.
            is Tab.RevisionDiff -> return listOf(
                KIND_REVISIONDIFF, tab.file.absolutePath, selected, tab.sha,
            ).joinToString("\t")
            // Repo-relative path, and the two extra columns the diff can't be rebuilt without.
            is Tab.CommitDiff -> return listOf(
                KIND_COMMITDIFF, tab.file.path, selected, tab.sha, tab.file.changeType.name,
            ).joinToString("\t")
            // Repo-relative path plus the kind of change, in the same column the others use for
            // their one extra field.
            is Tab.Diff -> return listOf(
                KIND_DIFF, tab.change.path, selected, tab.change.kind.name,
            ).joinToString("\t")
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
                KIND_FILE, KIND_HISTORY, KIND_LOCALHISTORY -> out += SavedTab(kind, path, selected)
                // The timestamp rides in the sha column; a row without one names no revision.
                KIND_LOCALDIFF -> {
                    val stamp = parts.getOrNull(3)?.takeIf { it.toLongOrNull() != null } ?: continue
                    out += SavedTab(kind, path, selected, stamp)
                }
                // The sha column carries an actual sha here; without one the row names no revision.
                KIND_REVISIONDIFF -> {
                    val sha = parts.getOrNull(3)?.takeIf { it.isNotBlank() } ?: continue
                    out += SavedTab(kind, path, selected, sha)
                }
                // The change kind rides in the sha column; without one the row can't say which
                // sides the diff has (an untracked file has no HEAD side), so it names no diff.
                KIND_DIFF -> {
                    val changeKind = parts.getOrNull(3)?.takeIf { it.isNotBlank() } ?: continue
                    out += SavedTab(kind, path, selected, changeKind)
                }
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
     * tab. Commit diffs are exempt — they read out of history, not the working tree — as are the
     * working-tree diffs whose change is the file's absence.
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
                // Local history needs no repo — it's nop's own record, and it's the only history an
                // uncommitted (or untracked) file has.
                KIND_LOCALHISTORY -> Tab.LocalHistory(File(s.path).takeIf { it.exists() } ?: continue)
                // The revision may have been pruned since; the view says so rather than the tab
                // silently not coming back, which is the same contract commit diffs restore under.
                KIND_LOCALDIFF -> {
                    val file = File(s.path).takeIf { it.isFile } ?: continue
                    Tab.LocalDiff(file, s.sha?.toLongOrNull() ?: continue)
                }
                // A revision diff needs its repo (the sha is read out of it) and its working file,
                // which is the side it compares that revision against.
                KIND_REVISIONDIFF -> {
                    if (repoRoot == null) continue
                    val file = File(s.path).takeIf { it.isFile } ?: continue
                    val sha = s.sha ?: continue
                    Tab.RevisionDiff(file, sha, sha.take(7), repoRoot)
                }
                // A working-tree diff needs its repo to read HEAD from. The file itself only has to
                // exist for the kinds that imply it does — a REMOVED/MISSING diff is precisely the
                // one whose working file is gone.
                KIND_DIFF -> {
                    if (repoRoot == null) continue
                    val kind = runCatching { ChangeKind.valueOf(s.sha ?: "") }.getOrNull() ?: continue
                    val gone = kind == ChangeKind.REMOVED || kind == ChangeKind.MISSING
                    if (!gone && !File(repoRoot, s.path).isFile) continue
                    Tab.Diff(FileChange(s.path, kind), repoRoot)
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
