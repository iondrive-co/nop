package iondrive.nop

/**
 * How long ago something happened, in the words a person would use — "just now", "5 minutes ago",
 * "2 hours ago", "3 days ago". Used by the window picker to say when each parked window was closed,
 * which is most of how the user tells one from another when several are waiting.
 *
 * Rounds rather than truncates, so 59 minutes reads as an hour ago instead of 59 minutes ago, and
 * steps up a unit only once the smaller one would be unwieldy.
 */
object Ago {
    fun of(whenMs: Long?, nowMs: Long = System.currentTimeMillis()): String {
        if (whenMs == null) return "just now"
        val seconds = ((nowMs - whenMs) / 1000).coerceAtLeast(0)
        if (seconds < 60) return "just now"
        val minutes = Math.round(seconds / 60.0)
        if (minutes < 60) return "$minutes ${plural(minutes, "minute")} ago"
        val hours = Math.round(minutes / 60.0)
        if (hours < 24) return "$hours ${plural(hours, "hour")} ago"
        val days = Math.round(hours / 24.0)
        return "$days ${plural(days, "day")} ago"
    }

    private fun plural(n: Long, unit: String) = if (n == 1L) unit else "${unit}s"
}
