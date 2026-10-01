package iondrive.nop.git

/**
 * Spaces repeated status walks of one tree by what each walk costs.
 *
 * A walk's price is set by the size of the working tree, not by how much changed, and JGit pays it
 * in garbage as well as time: one status of a 220,000-file checkout allocates ~470 MB and takes
 * over a second. Re-walked on a fixed few-second poll while agents write into it, that tree kept a
 * core ~40% busy and turned the whole heap over every few seconds, which is what drove nop into
 * back-to-back full collections. Waiting [multiple] times the last walk's duration between walks
 * holds any tree to a fixed share of one core — a fifth at the default — and leaves a small tree,
 * whose walk takes milliseconds, on the poll's own cadence.
 *
 * [due] and [walk] may be called from different threads, one after the other.
 */
class WalkPacer(
    private val multiple: Int = 4,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    @Volatile private var lastEndMs = Long.MIN_VALUE / 2
    @Volatile private var lastCostMs = 0L

    /** Whether enough time has passed since the last walk to start another. */
    fun due(): Boolean = nowMs() - lastEndMs >= lastCostMs * multiple

    /** Runs [body] as a walk, timing it for the next [due]. */
    fun <T> walk(body: () -> T): T {
        val start = nowMs()
        try {
            return body()
        } finally {
            val end = nowMs()
            lastCostMs = end - start
            lastEndMs = end
        }
    }
}
