package iondrive.nop.diff

import com.github.difflib.DiffUtils

/**
 * Line-based three-way merge, used when a save finds the file has moved under the editor.
 *
 * The three inputs are the ones the editor already has to hand: [base] is the buffer's saved
 * baseline — the bytes that were on disk when we last read or wrote the file — [ours] is what the
 * user has typed since, and [theirs] is what the file holds now. Edits that touch different parts
 * of the file are both kept; only where the two sides changed overlapping lines do we fall back to
 * git-style conflict markers, which [ConflictParser] then renders as resolvable regions in the diff
 * view.
 *
 * Deliberately conservative in the one direction that matters: it never drops a line. Anything it
 * can't merge cleanly ends up in a conflict block, visible in the buffer, rather than silently
 * resolved in one side's favour.
 */
object ThreeWayMerge {

    /** [text] is the merged buffer; [conflicts] counts the marker blocks it had to emit. */
    data class Result(val text: String, val conflicts: Int)

    /** A replacement of base lines `[start, end)` with [lines]. A pure insert has `start == end`. */
    private data class Change(val start: Int, val end: Int, val lines: List<String>)

    fun merge(
        base: String,
        ours: String,
        theirs: String,
        oursLabel: String = "yours (in the editor)",
        theirsLabel: String = "on disk",
    ): Result {
        if (ours == theirs) return Result(ours, 0)
        if (base == ours) return Result(theirs, 0)
        if (base == theirs) return Result(ours, 0)

        val baseLines = splitKeepingEol(base)
        val ourChanges = changesFrom(baseLines, splitKeepingEol(ours))
        val theirChanges = changesFrom(baseLines, splitKeepingEol(theirs))
        val eol = if (base.contains("\r\n") || ours.contains("\r\n")) "\r\n" else "\n"

        val out = StringBuilder()
        var cursor = 0 // next base line not yet emitted
        var a = 0
        var b = 0
        var conflicts = 0

        fun emitBase(upTo: Int) {
            while (cursor < upTo) out.append(baseLines[cursor++])
        }

        while (a < ourChanges.size || b < theirChanges.size) {
            val ca = ourChanges.getOrNull(a)
            val cb = theirChanges.getOrNull(b)

            // Whichever side changes an earlier, non-overlapping region goes in on its own.
            if (cb == null || (ca != null && ca.start <= cb.start && !overlaps(ca, cb))) {
                emitBase(ca!!.start)
                ca.lines.forEach { out.append(it) }
                cursor = ca.end
                a++
                continue
            }
            if (ca == null || !overlaps(ca, cb)) {
                emitBase(cb.start)
                cb.lines.forEach { out.append(it) }
                cursor = cb.end
                b++
                continue
            }

            // Overlapping edits. Widen the window until neither side has another change reaching
            // into it, so one conflict block covers the whole entangled region rather than
            // interleaving markers with half-applied edits.
            val start = minOf(ca.start, cb.start)
            var end = maxOf(ca.end, cb.end)
            var lastA = a
            var lastB = b
            var grew = true
            while (grew) {
                grew = false
                while (lastA + 1 < ourChanges.size && ourChanges[lastA + 1].start < end) {
                    lastA++
                    end = maxOf(end, ourChanges[lastA].end)
                    grew = true
                }
                while (lastB + 1 < theirChanges.size && theirChanges[lastB + 1].start < end) {
                    lastB++
                    end = maxOf(end, theirChanges[lastB].end)
                    grew = true
                }
            }

            val oursSide = applyOver(baseLines, ourChanges.subList(a, lastA + 1), start, end)
            val theirsSide = applyOver(baseLines, theirChanges.subList(b, lastB + 1), start, end)

            emitBase(start)
            if (oursSide == theirsSide) {
                // Both sides landed on the same text — an agreed change, not a conflict.
                oursSide.forEach { out.append(it) }
            } else {
                conflicts++
                appendMarker(out, "<<<<<<< $oursLabel", eol)
                oursSide.forEach { out.append(it) }
                appendMarker(out, "=======", eol)
                theirsSide.forEach { out.append(it) }
                appendMarker(out, ">>>>>>> $theirsLabel", eol)
            }
            cursor = end
            a = lastA + 1
            b = lastB + 1
        }
        emitBase(baseLines.size)

        return Result(out.toString(), conflicts)
    }

    /** Strict overlap, so edits on merely adjacent lines stay independent. Two inserts at the
     *  same point are the one zero-length case that still has to conflict — there is no way to
     *  order them that isn't a guess. */
    private fun overlaps(x: Change, y: Change): Boolean {
        if (x.start == x.end && y.start == y.end) return x.start == y.start
        return x.start < y.end && y.start < x.end
    }

    private fun changesFrom(baseLines: List<String>, sideLines: List<String>): List<Change> =
        DiffUtils.diff(baseLines, sideLines).deltas.map {
            Change(it.source.position, it.source.position + it.source.size(), it.target.lines)
        }

    /** Base lines `[start, end)` with [changes] (all inside that window) applied. */
    private fun applyOver(
        baseLines: List<String>,
        changes: List<Change>,
        start: Int,
        end: Int,
    ): List<String> {
        val out = ArrayList<String>()
        var cursor = start
        for (c in changes) {
            while (cursor < c.start) out.add(baseLines[cursor++])
            out.addAll(c.lines)
            cursor = c.end
        }
        while (cursor < end) out.add(baseLines[cursor++])
        return out
    }

    /**
     * Markers have to start their own line. When the text they follow is the end of a file that
     * had no trailing newline, one is added — the alternative is a marker welded onto the end of
     * the user's last line, which neither [ConflictParser] nor a human can read.
     */
    private fun appendMarker(out: StringBuilder, marker: String, eol: String) {
        if (out.isNotEmpty() && !out.endsWith("\n")) out.append(eol)
        out.append(marker).append(eol)
    }

    /** Lines with their terminators attached, so concatenation rebuilds the text byte-for-byte. */
    private fun splitKeepingEol(text: String): List<String> {
        val lines = ArrayList<String>()
        var start = 0
        var i = 0
        while (i < text.length) {
            if (text[i] == '\n') {
                lines.add(text.substring(start, i + 1))
                start = i + 1
            }
            i++
        }
        if (start < text.length) lines.add(text.substring(start))
        return lines
    }
}
