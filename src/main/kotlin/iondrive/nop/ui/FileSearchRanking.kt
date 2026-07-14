package iondrive.nop.ui

/**
 * Ranks a list of project-relative file paths against a query. Pure function so it can be unit
 * tested without spinning up Compose. Scoring buckets, highest first:
 *
 *   * exact filename match           — name == query
 *   * filename starts with query
 *   * filename contains query
 *   * path contains query (folder hit)
 *
 * Ties break on the most-frequently-accessed file first (see [accessCounts]), then shorter
 * filename, then alphabetical.
 *
 * An empty query — the state right after the double-shift box opens — reserves the top
 * [TOP_SLOTS] positions for the most-frequently-accessed files, then fills the remainder with the
 * head of the input list in its natural order, all capped to [limit]. With no access history it
 * degrades to the plain head of the list.
 */
object FileSearchRanking {
    /** How many leading positions the empty-query view reserves for most-frequently-accessed files. */
    const val TOP_SLOTS = 3

    /**
     * The most-frequently-accessed files still present in [files], highest count first (ties break
     * on shorter filename then alphabetical), capped to [TOP_SLOTS]. These fill the reserved top
     * slots of the empty-query view; the dialog draws a divider after them. Empty when there is no
     * access history.
     */
    fun frequent(files: List<String>, accessCounts: Map<String, Int>): List<String> =
        files
            .filter { (accessCounts[it] ?: 0) > 0 }
            .sortedWith(
                compareByDescending<String> { accessCounts[it] ?: 0 }
                    .thenBy { it.substringAfterLast('/').length }
                    .thenBy { it.lowercase() },
            )
            .take(TOP_SLOTS)

    fun rank(
        query: String,
        files: List<String>,
        accessCounts: Map<String, Int> = emptyMap(),
        limit: Int = 30,
    ): List<String> {
        if (query.isEmpty()) {
            // Reserve the top slots for the most-used files, then keep the natural order for the
            // rest. Only files still present in `files` (and actually accessed) qualify.
            val frequent = frequent(files, accessCounts)
            if (frequent.isEmpty()) return files.take(limit)
            val promoted = frequent.toHashSet()
            return (frequent + files.filterNot { it in promoted }).take(limit)
        }
        val q = query.lowercase()
        val scored = ArrayList<Pair<String, Int>>(files.size)
        for (f in files) {
            val name = f.substringAfterLast('/').lowercase()
            val full = f.lowercase()
            val score = when {
                name == q -> 1000
                name.startsWith(q) -> 800
                name.contains(q) -> 500
                full.contains(q) -> 200
                else -> 0
            }
            if (score > 0) scored += f to score
        }
        return scored
            .sortedWith(
                compareByDescending<Pair<String, Int>> { it.second }
                    .thenByDescending { accessCounts[it.first] ?: 0 }
                    .thenBy { it.first.substringAfterLast('/').length }
                    .thenBy { it.first.lowercase() },
            )
            .take(limit)
            .map { it.first }
    }
}
