package iondrive.nop.ui

/**
 * The commit panel's ticks, and the marks on paths that nop ticked by itself after the user may
 * have looked at the list.
 *
 * A path that leaves the change list while HEAD stays put (stashed, reverted, or set aside by an
 * agent) is remembered with the tick it had. When it comes back it gets that tick again, not the
 * default a new change gets, and it is marked [returned]. Without the mark a returning file looks
 * the same as one that never left: an agent stashed a fix to test a test alone, popped it back,
 * and the user committed the fix believing the list held only the test. A path that leaves because
 * HEAD moved (a commit, a checkout) is forgotten, so its next edit is a new change.
 *
 * [arrived] holds the new paths ticked while a commit message was being written. Opening a path's
 * diff or toggling its tick clears both marks for it.
 */
internal data class CommitSelection(
    val ticked: Set<String> = emptySet(),
    val returned: Set<String> = emptySet(),
    val arrived: Set<String> = emptySet(),
    private val departed: Map<String, Boolean> = emptyMap(),
) {
    /** The paths of [included] that the Commit button must ask about first. */
    fun unreviewed(included: Collection<String>): List<String> = included.filter { it in returned || it in arrived }

    fun toggle(path: String): CommitSelection =
        copy(ticked = if (path in ticked) ticked - path else ticked + path).acknowledge(path)

    fun acknowledge(path: String): CommitSelection = copy(returned = returned - path, arrived = arrived - path)

    /**
     * The change list has moved from [previous] to [fresh]. [headMoved] says HEAD changed in the same
     * step, and [writing] that the commit message was not blank.
     */
    fun reconcile(previous: Set<String>, fresh: Set<String>, headMoved: Boolean, writing: Boolean): CommitSelection {
        val left = if (headMoved) emptyMap() else (previous - fresh).associateWith { it in ticked }
        val appeared = fresh - previous
        val back = appeared.filter { it in departed }.toSet()
        val new = appeared - back
        return CommitSelection(
            ticked = (ticked intersect fresh) + new + back.filter { departed.getValue(it) },
            returned = (returned intersect fresh) + back,
            arrived = (arrived intersect fresh) + if (writing) new else emptySet(),
            departed = departed - back + left,
        )
    }

    companion object {
        /** A first load: every change is ticked, and nothing is marked. */
        fun loaded(paths: Set<String>) = CommitSelection(ticked = paths)
    }
}
