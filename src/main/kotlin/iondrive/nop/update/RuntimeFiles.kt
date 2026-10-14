package iondrive.nop.update

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/**
 * Whether the Java runtime on disk where this nop started is still the one it runs on.
 *
 * A JVM goes on finding parts of itself by path long after it has started: every program it starts
 * goes through `lib/jspawnhelper` under its runtime, run from there each time. Replace the install
 * this nop started from while it runs — a build or an install script putting another version at the
 * same path — and that path is another runtime's helper: starting a program then fails ("Failed to
 * exec spawn helper") or the helper crashes, until nop restarts and runs on the files that are there.
 */
object RuntimeFiles {

    private val helper: Path? = System.getProperty("java.home")?.let { Path.of(it, "lib", "jspawnhelper") }

    private val atStart: Any? = helper?.let(::identity)

    /** Notes the runtime as it is now. Called as nop starts; until then, nothing can be compared. */
    fun note() {
        atStart
    }

    /** True once the runtime at the path this nop started from is not the one it started with. */
    fun replaced(): Boolean = atStart != null && helper != null && identity(helper) != atStart

    /** The file's identity: the device and inode a path leads to, whatever the path. */
    internal fun identity(path: Path): Any? =
        runCatching { Files.readAttributes(path, BasicFileAttributes::class.java).fileKey() }.getOrNull() ?: Unit
}
