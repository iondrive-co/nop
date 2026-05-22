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
 * Ties break on shorter filename first, then alphabetical. An empty query returns the head of
 * the input list unchanged, capped to [limit] — handy when the dialog first opens and the user
 * hasn't typed anything yet.
 */
object FileSearchRanking {
    fun rank(query: String, files: List<String>, limit: Int = 30): List<String> {
        if (query.isEmpty()) return files.take(limit)
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
                    .thenBy { it.first.substringAfterLast('/').length }
                    .thenBy { it.first.lowercase() },
            )
            .take(limit)
            .map { it.first }
    }
}
