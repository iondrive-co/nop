package iondrive.nop

import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * nop's log file: $XDG_CONFIG_HOME/nop/nop.log (default ~/.config/nop/nop.log).
 *
 * nop is a desktop binary launched from a .desktop entry or a shell, so its stderr goes somewhere
 * nobody thinks to look — ~/.xsession-errors interleaved with every other GUI app's chatter, at
 * best, /dev/null at worst. Anything worth reading after the fact goes here instead: startup, which
 * project and file were opened, and every uncaught throwable. When nop vanishes, this file is the
 * answer to "what happened".
 *
 * Lines are appended and flushed one at a time under a lock, so a crash cannot lose the lines that
 * explain it. Logging never throws — a log that can't be written is not worth taking the editor
 * down for.
 */
object Log {
    /** Rotate past this size; one previous generation is kept as nop.log.1. */
    private const val MAX_BYTES = 2L * 1024 * 1024

    // A StackOverflowError arrives with ~1024 near-identical frames. Keeping the head (where the
    // interesting code is) and the tail (the recursing cycle) makes the log readable without
    // losing the diagnosis.
    private const val TRACE_HEAD_LINES = 60
    private const val TRACE_TAIL_LINES = 15

    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    private val lock = Any()

    val file: Path get() = Settings.configRoot.resolve("nop").resolve("nop.log")

    fun info(message: String) = write("INFO ", message)

    fun warn(message: String) = write("WARN ", message)

    fun error(message: String, t: Throwable? = null) =
        write("ERROR", if (t == null) message else "$message\n${formatTrace(t)}")

    /**
     * Installs the process-wide crash handler and logs a startup line. Call once from main() before
     * any UI exists. The default handler also covers the AWT event thread, which is where Compose
     * rethrows anything a composition threw — the path that used to lose crashes entirely.
     */
    fun install(args: Array<String>) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, t ->
            error("uncaught in thread \"${thread.name}\"", t)
            // Chain to whatever was there so stderr still carries it when someone is watching.
            previous?.uncaughtException(thread, t)
        }
        info(
            "nop starting — pid=${ProcessHandle.current().pid()} " +
                "java=${System.getProperty("java.version")} " +
                "args=[${args.joinToString(" ")}]",
        )
    }

    private fun formatTrace(t: Throwable): String {
        val sw = StringWriter()
        PrintWriter(sw).use { t.printStackTrace(it) }
        val lines = sw.toString().trimEnd().lines()
        if (lines.size <= TRACE_HEAD_LINES + TRACE_TAIL_LINES + 1) return lines.joinToString("\n")
        val elided = lines.size - TRACE_HEAD_LINES - TRACE_TAIL_LINES
        return (
            lines.take(TRACE_HEAD_LINES) +
                "\t... $elided frames elided ..." +
                lines.takeLast(TRACE_TAIL_LINES)
            ).joinToString("\n")
    }

    private fun write(level: String, message: String) {
        synchronized(lock) {
            runCatching {
                val f = file
                Files.createDirectories(f.parent)
                rotateIfNeeded(f)
                Files.writeString(
                    f,
                    "${LocalDateTime.now().format(stamp)} $level $message\n",
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND,
                )
            }
        }
    }

    private fun rotateIfNeeded(f: Path) {
        runCatching {
            if (Files.exists(f) && Files.size(f) > MAX_BYTES) {
                Files.move(f, f.resolveSibling("nop.log.1"), StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }
}
