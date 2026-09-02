package iondrive.nop.git

/**
 * How far a running commit has got, so the commit button can show more than "Committing…".
 * A commit of a large change set is minutes of work — see [GitRepo.stageBlobsInParallel] for the
 * 1.9 GB case that motivated parallel staging — and until the object writes report in, the only
 * signal the user has is a greyed-out button.
 *
 * Progress is measured in *bytes of the change set written into the object store*, not files:
 * change sets are wildly uneven (one 400 MB checkpoint beside 300 one-line edits), so a file count
 * would race to 99% and then sit there. [bytesTotal] is 0 while the size of the job isn't known
 * yet — before the staging plan exists, and on the paths that hand every file to JGit's
 * AddCommand, which reports nothing back — and callers must then show elapsed time instead of a
 * percentage. See [iondrive.nop.ui.commitProgressLabel].
 */
data class CommitProgress(
    val phase: Phase,
    /** Bytes of the change set already in the object store. */
    val bytesDone: Long = 0,
    /** Bytes the staging pass has to get through, or 0 when that isn't measurable. */
    val bytesTotal: Long = 0,
    /** Files staged so far, against [filesTotal]. Counts blob writes, not removals. */
    val filesDone: Int = 0,
    val filesTotal: Int = 0,
    /**
     * Wall clock (epoch millis) the commit started at, so elapsed and ETA are computed against a
     * live clock rather than the age of this snapshot — a snapshot can be a minute old when one
     * huge file is still being deflated.
     */
    val startedAtMillis: Long = 0,
) {
    /** The step of the commit now running. Ordered as they happen. */
    enum class Phase(val label: String) {
        /** Re-reading the working tree, to catch changes that appeared since the panel loaded. */
        CHECKING("Checking"),

        /** Planning the staging pass, and running the files that need git's filters through it. */
        STAGING("Staging"),

        /** Deflating and inserting blobs — the phase that dominates a large commit. */
        WRITING("Writing"),

        /** Writing the index, then the tree and commit objects. */
        COMMITTING("Committing"),

        /** Reloading status after the commit landed. */
        REFRESHING("Refreshing"),
    }

    /** Share of the work done, 0f..1f, or null when there is nothing measurable to divide by. */
    val fraction: Float?
        get() = if (bytesTotal <= 0L) null else (bytesDone.toDouble() / bytesTotal).coerceIn(0.0, 1.0).toFloat()
}
