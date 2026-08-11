package iondrive.nop.history

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.name

/**
 * One state a file was in, as nop saw it. [timestampMillis] is both when the snapshot was taken and
 * its identity within a file's history — it names the file the content lives in, so a revision can
 * be re-found across restarts from nothing but (file, timestamp).
 */
data class LocalRevision(
    val timestampMillis: Long,
    val sizeBytes: Long,
    /** Where the content lives. Read it through [LocalHistory.read] rather than directly. */
    internal val path: Path,
)

/**
 * nop's own record of what a file contained, independent of git — the same safety net IntelliJ's
 * "Local History" provides. Every version nop writes to disk is snapshotted here first, so work that
 * never reached a commit (and edits an agent, a checkout or another editor has since overwritten)
 * can still be read back.
 *
 * Snapshots are plain UTF-8 copies under one directory per file, named by their timestamp:
 *
 *     <root>/<file-name>-<hash of its absolute path>/<epoch millis>
 *
 * Plain because the whole point is recovering content, and a format needing nop to decode it is a
 * worse place to keep the only copy of an hour's work than a file `cat` can read. Two files with the
 * same name in different directories get separate directories because the suffix comes from the full
 * path — the same scheme [iondrive.nop.Settings.projectDataDir] uses for projects.
 *
 * Every method swallows IO failures: a history that can't be written is a missing safety net, never
 * a reason to fail the save it was recording.
 */
class LocalHistory(private val root: Path) {

    /**
     * Snapshots [text] as [file]'s state at [at], and returns the revision it wrote — or null when
     * nothing was recorded, which is the common case: an unchanged file (the newest revision already
     * holds this exact text) or one too large to be worth keeping copies of.
     *
     * Deduplicating against the newest revision is what lets callers record freely: the save path
     * offers up the pre-save baseline on *every* write, and only the first one after the file changes
     * actually stores anything.
     */
    fun record(file: File, text: String, at: Long = System.currentTimeMillis()): LocalRevision? {
        if (text.length > MAX_SNAPSHOT_CHARS) return null
        val dir = dirFor(file)
        val bytes = text.toByteArray()
        val newest = revisionsIn(dir).firstOrNull()
        // Size first: every save asks this question, and a file that grew or shrank is ruled
        // different without reading the previous copy back off disk at all.
        if (newest != null && newest.sizeBytes == bytes.size.toLong() && read(newest) == text) return null
        return runCatching {
            Files.createDirectories(dir)
            // Two saves inside one millisecond would otherwise land on the same name, and the second
            // would silently replace the first rather than being a revision of its own.
            var stamp = at
            while (Files.exists(dir.resolve(stamp.toString()))) stamp++
            val target = dir.resolve(stamp.toString())
            Files.write(target, bytes)
            prune(dir, at)
            LocalRevision(stamp, bytes.size.toLong(), target)
        }.getOrNull()
    }

    /** [file]'s snapshots, newest first. Empty when nothing has been recorded for it. */
    fun revisions(file: File): List<LocalRevision> = revisionsIn(dirFor(file))

    /** The snapshot of [file] taken at [timestampMillis], or null if it has been pruned away. */
    fun revisionAt(file: File, timestampMillis: Long): LocalRevision? =
        revisions(file).firstOrNull { it.timestampMillis == timestampMillis }

    /** The text [revision] holds, or null when it can no longer be read. */
    fun read(revision: LocalRevision): String? =
        runCatching { Files.readString(revision.path) }.getOrNull()

    private fun revisionsIn(dir: Path): List<LocalRevision> {
        if (!Files.isDirectory(dir)) return emptyList()
        return runCatching {
            Files.list(dir).use { stream ->
                stream.toList()
                    .mapNotNull { p ->
                        val stamp = p.name.toLongOrNull() ?: return@mapNotNull null
                        LocalRevision(stamp, runCatching { Files.size(p) }.getOrDefault(0L), p)
                    }
                    .sortedByDescending { it.timestampMillis }
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Drops what a file's history no longer needs: everything past [MAX_REVISIONS_PER_FILE], and
     * anything older than [MAX_AGE_MILLIS]. The newest revision always survives both rules — a
     * history whose only entry aged out would leave the file looking as if it had never been
     * recorded, which is exactly when someone comes looking for it.
     */
    private fun prune(dir: Path, now: Long) {
        val revisions = revisionsIn(dir)
        revisions.forEachIndexed { index, revision ->
            if (index == 0) return@forEachIndexed
            val tooMany = index >= MAX_REVISIONS_PER_FILE
            val tooOld = now - revision.timestampMillis > MAX_AGE_MILLIS
            if (tooMany || tooOld) runCatching { Files.deleteIfExists(revision.path) }
        }
    }

    /** Where [file]'s snapshots live: a readable name, disambiguated by a hash of the full path. */
    private fun dirFor(file: File): Path {
        val abs = file.absoluteFile.normalize().path
        val digest = MessageDigest.getInstance("SHA-1").digest(abs.toByteArray())
        val short = digest.joinToString("") { "%02x".format(it) }.take(10)
        val safeName = file.name.replace(Regex("[^A-Za-z0-9_.-]"), "_").takeIf { it.isNotEmpty() } ?: "file"
        return root.resolve("$safeName-$short")
    }

    companion object {
        /**
         * Above this, a file is not worth keeping dozens of copies of — a generated bundle or a data
         * dump, not the source someone is editing. Counted in characters (the text is in hand;
         * measuring its encoded size would mean encoding it twice).
         */
        const val MAX_SNAPSHOT_CHARS = 2 * 1024 * 1024

        /** Snapshots kept per file, newest first. */
        const val MAX_REVISIONS_PER_FILE = 50

        /** How far back history reaches. Matches IntelliJ's default local-history window. */
        const val MAX_AGE_MILLIS = 5L * 24 * 60 * 60 * 1000
    }
}
