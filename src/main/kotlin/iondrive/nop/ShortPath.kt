package iondrive.nop

import java.nio.file.Path

/**
 * A directory as a person reads it in a strip a few hundred pixels wide: `~` for the home
 * directory, and leading segments dropped when it still doesn't fit.
 *
 * Truncating from the *left* is the whole point. What this labels is which checkout an agent is
 * running in, and that is the last segment — `~/work/clients/acme/backend` cut from the right reads
 * as `~/work/clients/ac…`, which names no project at all. Cut from the left it reads `…/acme/backend`,
 * which still does.
 *
 * The last segment is kept whatever its length: a path with nothing of it left would be worse than
 * one that overflows, and the caller's own ellipsis is a better answer to that than this is.
 */
object ShortPath {
    /**
     * Roughly what fits beside an account name in the agent session bar. Not a hard layout
     * constraint — the callers still ellipsize — but the point at which dropping directories says
     * more than letting the row squeeze.
     */
    const val DEFAULT_MAX: Int = 28

    fun of(
        path: Path,
        max: Int = DEFAULT_MAX,
        home: String? = System.getProperty("user.home"),
    ): String {
        val abs = runCatching { path.toAbsolutePath().normalize().toString() }
            .getOrElse { path.toString() }
        val short = underHome(abs, home)
        if (short.length <= max) return short

        val parts = short.split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return short
        // Grow the tail one directory at a time and stop at the last one that fits, so a path is
        // shortened by as little as it takes rather than to a fixed depth.
        var kept = listOf(parts.last())
        for (part in parts.dropLast(1).asReversed()) {
            val candidate = listOf(part) + kept
            if ((ELLIPSIS + candidate.joinToString("/")).length > max) break
            kept = candidate
        }
        return ELLIPSIS + kept.joinToString("/")
    }

    /** `~`-relative when [abs] is inside [home], and [abs] untouched when it isn't. */
    private fun underHome(abs: String, home: String?): String {
        val root = home?.takeIf { it.isNotBlank() }
            ?.let { runCatching { Path.of(it).toAbsolutePath().normalize().toString() }.getOrNull() }
            ?: return abs
        return when {
            abs == root -> "~"
            abs.startsWith("$root/") -> "~" + abs.substring(root.length)
            else -> abs
        }
    }

    private const val ELLIPSIS = "…/"
}
