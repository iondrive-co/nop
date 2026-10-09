package iondrive.nop

/**
 * The order file lists are shown in: by containing directory first, then by file name — so every
 * file in a folder sits together as one run, a folder's own files come before its subfolders', and
 * the whole list reads like the tree it came from.
 *
 * Used wherever a list of project-relative paths reaches the screen (the commit panel's changes,
 * find-in-files hits). Sorting them is what stops the list reshuffling under the user: git status
 * hands its paths back in hash order, so merely touching a file could send it to the top of the
 * list it was already in.
 *
 * Comparison is case-insensitive first (a folder named `Admin` belongs beside `advertising`, not
 * ahead of every lowercase name), with a case-sensitive tie-break so the order is total — two paths
 * differing only in case must not compare equal, or a sort could still swap them run to run.
 */
object PathOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int = Split(a, a).compareTo(Split(b, b))

    /**
     * [items] sorted by [path] in this order — what to use for a list rather than this comparator.
     *
     * Each path is split into its folder and its name once, up front. A comparator has to split
     * both of its paths on every call, and a sort makes n log n calls: on a change list of a few
     * hundred thousand paths that is tens of millions of throwaway strings, and most of the sort.
     */
    fun <T> sorted(items: Iterable<T>, path: (T) -> String): List<T> =
        items.map { Split(path(it), it) }.sorted().map { it.item }

    private class Split<T>(path: String, val item: T) : Comparable<Split<*>> {
        private val dir = path.substringBeforeLast('/', "")
        private val name = path.substringAfterLast('/')

        override fun compareTo(other: Split<*>): Int {
            compareParts(dir, other.dir).let { if (it != 0) return it }
            return compareParts(name, other.name)
        }
    }

    private fun compareParts(a: String, b: String): Int {
        val insensitive = a.compareTo(b, ignoreCase = true)
        return if (insensitive != 0) insensitive else a.compareTo(b)
    }
}
