package iondrive.nop.ui

import iondrive.nop.git.FileChange
import iondrive.nop.git.GitStatus

/**
 * What a fresh git status means for the working-tree diffs currently open — see [syncDiffTabs].
 */
internal data class DiffTabSync(
    /**
     * Tabs to swap in and re-read: still backed by a change, but with a stale kind, a moved HEAD,
     * or both. Each carries the kind git reports now, so a file that was untracked when the tab
     * opened and is committed-and-edited now stops being diffed against an empty left side.
     */
    val reload: List<Tab.Diff> = emptyList(),
    /** Tabs to close: git has no change for these paths any more, so there is no diff to draw. */
    val close: List<Tab.Diff> = emptyList(),
)

/**
 * Reconciles the open [Tab.Diff]s against [status].
 *
 * A working-tree diff is a view of a change git currently has, and every commit, merge, pull,
 * stash or revert moves the ground under it: HEAD gains commits (so the left-hand side is no
 * longer what the file is being compared against), a change can switch kind, and it can go away
 * altogether. The last case is the one with no sensible fallback — the tab is left showing a diff
 * of two revisions the repository has stopped disagreeing about — so it closes.
 *
 * [headMoved] says whether HEAD is a different commit than when these diffs were last read; the
 * working side needs no such signal, since the shared edit buffer already tracks the file live.
 * [hasUnsavedEdit] keeps a tab whose buffer holds work the user hasn't saved: git calling the file
 * clean means only that *disk* matches HEAD, and closing the tab would take the unsaved version
 * off screen along with the autosave that was about to persist it.
 *
 * Tabs of every other kind are left alone: a commit's diff and a local-history diff are both of
 * fixed revisions, and neither is described by the working tree's status.
 */
internal fun syncDiffTabs(
    tabs: List<Tab>,
    status: GitStatus,
    headMoved: Boolean,
    hasUnsavedEdit: (Tab.Diff) -> Boolean = { false },
): DiffTabSync {
    val reload = ArrayList<Tab.Diff>()
    val close = ArrayList<Tab.Diff>()
    for (tab in tabs.filterIsInstance<Tab.Diff>()) {
        val kind = status.byPath[tab.change.path]
        when {
            kind == null -> if (!hasUnsavedEdit(tab)) close.add(tab)
            kind != tab.change.kind -> reload.add(tab.copy(change = FileChange(tab.change.path, kind)))
            headMoved -> reload.add(tab)
        }
    }
    return DiffTabSync(reload, close)
}
