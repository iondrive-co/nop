package iondrive.nop.agent.transcript

import java.util.concurrent.ConcurrentHashMap

/**
 * The vendor sessions nop has started or followed, by native id, and the agent tab each belongs to.
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
 * A session stays its tab's after the run following it has ended. The registry used to forget a
 * session the moment its run ended, and a run ends at every handover — the second the CLI is killed
 * and still flushing its last records. At the 14:11 handover in hermes on 2026-09-24, one tab
 * handed over, let go of its Claude session, and two sibling tabs, still running, adopted it as
 * their own `/clear`: their handoffs were built from its conversation, and Codex was sent to do
 * the other tab's work in both. The same thing happened again with the Codex rollouts that
 * followed. Nothing that another tab started is ever this tab's `/clear` or this tab's rollout, so
 * nothing is let go. A later claim from another tab — a session reopened from the picker — moves
 * it.
 *
 * Ids rather than paths, because that is what both tailers can name a candidate by before they have
 * opened it, and what a Claude run knows about itself before its file exists.
 */
object LiveTranscripts {
    private val owners = ConcurrentHashMap<String, String>()

    /** Records that the agent tab [owner] (its nop session id) is following [id]. */
    fun claim(id: String?, owner: String) {
        if (id != null) owners[id] = owner
    }

    /** Whether [id] belongs to a tab other than [owner]. See [RunContext.foreign] for the caller. */
    fun isForeign(id: String, owner: String): Boolean = owners[id].let { it != null && it != owner }
}
