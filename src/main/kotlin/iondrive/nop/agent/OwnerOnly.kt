package iondrive.nop.agent

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

/**
 * Creates the launcher's files so that only their owner can read them.
 *
 * Everything this feature writes is worth keeping to yourself. The session logs carry the prompts
 * you typed, the model's replies, the commands it ran and slices of the files it read; the handoff
 * summaries are the same material condensed; the account homes are where the vendor CLIs keep their
 * OAuth tokens. Left to the default umask all of it lands `-rw-rw-r--` — readable by every other
 * account on the machine — which is a worse posture than the vendors take with their own
 * credentials, and nop is the one choosing it.
 *
 * Permissions are set *as the file is created*, not fixed up afterwards, because the gap between
 * the two is exactly when a world-readable file exists.
 *
 * A non-POSIX filesystem has no such view, so everything here degrades to an ordinary create rather
 * than failing: on Windows the parent directory's ACL is what governs, and refusing to write a log
 * would be a strange way to protect it.
 */
internal object OwnerOnly {

    private val DIR_PERMISSIONS = PosixFilePermissions.asFileAttribute(
        setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
        ),
    )

    private val FILE_PERMISSIONS = setOf(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
    )

    private val FILE_ATTRIBUTE = PosixFilePermissions.asFileAttribute(FILE_PERMISSIONS)

    /**
     * Creates [dir] and every missing parent, each owner-only.
     *
     * Directories that already exist are left as they are: they may be somewhere the user chose —
     * an account home pointing at a directory another tool owns — and quietly changing the mode of
     * a path nop did not create is not nop's call to make.
     */
    fun directory(dir: Path): Path {
        if (Files.isDirectory(dir)) return dir
        // Deepest-missing-first, so each level is created with the right mode rather than inheriting
        // the umask from a bulk createDirectories and being tightened after the fact.
        val missing = generateSequence(dir.toAbsolutePath()) { it.parent }
            .takeWhile { !Files.exists(it) }
            .toList()
            .asReversed()
        for (level in missing) {
            runCatching { Files.createDirectory(level, DIR_PERMISSIONS) }
                .recoverCatching { Files.createDirectories(level) }
        }
        return dir
    }

    /** Creates [file] owner-only if it isn't there yet, and returns it either way. */
    fun file(file: Path): Path {
        file.parent?.let { directory(it) }
        if (!Files.exists(file)) {
            runCatching { Files.createFile(file, FILE_ATTRIBUTE) }
                .recoverCatching { Files.createFile(file) }
        }
        return file
    }

    /** Narrows an existing file to owner-only. Used after an atomic rename brings a new one in. */
    fun tighten(file: Path) {
        runCatching { Files.setPosixFilePermissions(file, FILE_PERMISSIONS) }
    }
}
