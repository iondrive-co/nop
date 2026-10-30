package iondrive.nop.ui

/** File-row highlights, independent of the checkboxes that choose what to commit. */
internal data class CommitRowSelection(
    val paths: Set<String> = emptySet(),
    private val anchor: String? = null,
) {
    fun click(path: String, order: List<String>, toggle: Boolean, range: Boolean): CommitRowSelection {
        val end = order.indexOf(path)
        if (end < 0) return this
        val start = order.indexOf(anchor)
        if (range && start >= 0) {
            val span = order.subList(minOf(start, end), maxOf(start, end) + 1).toSet()
            return copy(paths = if (toggle) paths + span else span)
        }
        return CommitRowSelection(
            paths = if (toggle) {
                if (path in paths) paths - path else paths + path
            } else setOf(path),
            anchor = path,
        )
    }

    fun reconcile(available: Set<String>): CommitRowSelection =
        copy(paths = paths intersect available, anchor = anchor?.takeIf { it in available })
}
