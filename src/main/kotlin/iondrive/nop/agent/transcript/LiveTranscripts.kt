package iondrive.nop.agent.transcript

import java.util.concurrent.ConcurrentHashMap

/**
 * The vendor sessions nop currently has a run following, by native id.
 *
 * It exists because "which transcript is mine?" cannot be answered from the directory alone. Both
 * tailers pick a file out of a directory the vendor shares between every session in a project —
 * Claude's by name and then, after a `/clear`, by "the newest one written since I started"; Codex's
 * by "the newest rollout whose `cwd` is this project". Those rules are right for one tab and wrong
 * for two: a second agent tab opened on the same project writes a newer transcript, and every older
 * tab sees exactly what an in-TUI `/clear` looks like and follows it. All of them then converge on
 * the newest session, and since a tab is named from the title in the transcript it is following,
 * every tab ends up wearing the newest one's name.
 *
 * A registry is the honest fix rather than a cleverer heuristic, because the two cases are
 * genuinely indistinguishable on disk — a `/clear` successor and a sibling tab's session are both
 * just a newer file — and nop is the one that knows the difference: it started them.
 *
 * Ids rather than paths, because that is what both tailers can name a candidate by before they have
 * opened it, and what a Claude run knows about itself before its file exists.
 */
object LiveTranscripts {
    private val ids = ConcurrentHashMap.newKeySet<String>()

    fun claim(id: String?) {
        if (id != null) ids.add(id)
    }

    fun release(id: String?) {
        if (id != null) ids.remove(id)
    }

    /** Whether some run is following this session. See [RunContext.foreign] for the caller. */
    fun isLive(id: String): Boolean = ids.contains(id)
}
