package iondrive.nop.agent

import iondrive.nop.Log
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.time.Instant

/**
 * What one backup folder already holds, kept on this machine so that a run need not ask the folder:
 * for each copy, by its path under the folder, the size and time its source had when the copy was
 * last brought up to date. A source that still has both is passed over without looking at the folder
 * at all. On a network share every look is a round trip, and from one run to the next nearly every
 * file is unchanged, so those looks are most of what a run costs.
 *
 * It also stands in for the copy's own time, which is how [Backup] otherwise tells a changed file
 * from an unchanged one of the same size. A share that does not let a copy's time be set leaves each
 * one dated when it was made, and every such copy would look changed on every run.
 *
 * Passing a copy over trusts that nothing else has removed it or cut it short, so every [FULL_CHECK]
 * a run looks at every copy's size as well, and the record is written afresh from what that run
 * found. Between those it only grows: a run appends a line for each copy it brought up to date, and
 * on reading, the last line for a path wins.
 */
internal class BackupRecord private constructor(
    /** Where it is kept, or null for one held only in memory. */
    private val file: Path?,
    /** The folder it describes. A record of another folder says nothing about this one. */
    val dest: Path,
    /** When a run last looked at every copy, or null when none has. */
    private var checkedAt: Instant?,
    private val stamps: HashMap<String, Stamp>,
) {
    /** A source file's size, and its modification time in milliseconds. */
    data class Stamp(val size: Long, val modified: Long)

    /** The paths a full check has found, while one runs. */
    private var seen: HashSet<String>? = null

    /** Lines for the copies brought up to date since the record was last written. */
    private val added = StringBuilder()

    /**
     * Starts a run at [now], returning whether it is to look at every copy: when [asked] to, when no
     * run has yet, and when the last one that did is [FULL_CHECK] old.
     */
    fun begin(now: Instant, asked: Boolean): Boolean {
        val full = asked || checkedAt?.let { Duration.between(it, now) >= FULL_CHECK } ?: true
        seen = if (full) HashSet() else null
        return full
    }

    /** Whether the copy at [path] holds its source as of [stamp], as far as the record knows. */
    fun holds(path: String, stamp: Stamp): Boolean = stamps[path] == stamp

    /** Notes that the copy at [path] now holds its source as of [stamp]. */
    fun note(path: String, stamp: Stamp) {
        // A name that would break a line is never recorded, and so is always looked at.
        if ('\n' in path || '\r' in path) return
        val full = seen
        full?.add(path)
        if (stamps.put(path, stamp) != stamp && full == null) {
            added.append(stamp.size).append('\t').append(stamp.modified).append('\t').append(path).append('\n')
        }
    }

    /**
     * Ends the run [begin] started at [now] and keeps what it found. A full check forgets the copies
     * it did not find, whose sources have gone or could not be copied, so they are looked at again.
     */
    fun finish(now: Instant) {
        val full = seen
        seen = null
        if (full != null) {
            stamps.keys.retainAll(full)
            checkedAt = now
        }
        if (full != null || added.isNotEmpty()) write(whole = full != null)
        added.setLength(0)
    }

    private fun write(whole: Boolean) {
        val file = file ?: return
        runCatching {
            if (whole || !Files.isRegularFile(file)) {
                OwnerOnly.directory(file.parent)
                // createTempFile is already owner-only on POSIX, and the rename carries that across.
                val temp = Files.createTempFile(file.parent, file.fileName.toString(), ".tmp")
                try {
                    Files.newBufferedWriter(temp).use { out ->
                        out.write("${checkedAt?.toEpochMilli() ?: ""}\t$dest\n")
                        for ((path, stamp) in stamps) out.write("${stamp.size}\t${stamp.modified}\t$path\n")
                    }
                    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } finally {
                    Files.deleteIfExists(temp)
                }
            } else {
                Files.writeString(file, added, StandardOpenOption.APPEND)
            }
        }.onFailure { Log.warn("backup: could not keep its record in $file: $it") }
    }

    companion object {
        /** How long copies are passed over on the record's word before a run looks at them all again. */
        val FULL_CHECK: Duration = Duration.ofHours(24)

        /** An empty record of [dest] that is kept nowhere. */
        fun inMemory(dest: Path): BackupRecord = BackupRecord(null, dest.toAbsolutePath().normalize(), null, HashMap())

        /**
         * The record kept in [file] when it is of [dest], or otherwise an empty one of [dest] that
         * will be kept there. A line cut short by a crash is skipped, and its copy looked at again.
         */
        fun load(file: Path, dest: Path): BackupRecord {
            val target = dest.toAbsolutePath().normalize()
            val lines = runCatching { if (Files.isRegularFile(file)) Files.readAllLines(file) else emptyList() }
                .onFailure { Log.warn("backup: could not read its record in $file: $it") }
                .getOrDefault(emptyList())
            val header = lines.firstOrNull()?.split('\t', limit = 2)
            if (header == null || header.size < 2 || header[1] != target.toString()) {
                return BackupRecord(file, target, null, HashMap())
            }
            val stamps = HashMap<String, Stamp>(lines.size * 2)
            for (line in lines.subList(1, lines.size)) {
                val parts = line.split('\t', limit = 3)
                if (parts.size < 3 || parts[2].isEmpty()) continue
                val size = parts[0].toLongOrNull() ?: continue
                val modified = parts[1].toLongOrNull() ?: continue
                stamps[parts[2]] = Stamp(size, modified)
            }
            return BackupRecord(file, target, header[0].toLongOrNull()?.let(Instant::ofEpochMilli), stamps)
        }
    }
}
