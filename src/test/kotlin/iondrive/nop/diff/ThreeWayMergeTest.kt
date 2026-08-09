package iondrive.nop.diff

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ThreeWayMergeTest {

    private fun merge(base: String, ours: String, theirs: String) =
        ThreeWayMerge.merge(base, ours, theirs, oursLabel = "mine", theirsLabel = "disk")

    @Test
    fun `edits in different parts of the file both survive`() {
        // The case the whole feature exists for: the user retyped the top of the file while an
        // agent rewrote the bottom. Neither edit should be lost, and neither is a conflict.
        val base = "one\ntwo\nthree\nfour\nfive\n"
        val ours = "ONE\ntwo\nthree\nfour\nfive\n"
        val theirs = "one\ntwo\nthree\nfour\nFIVE\n"

        val r = merge(base, ours, theirs)

        assertEquals("ONE\ntwo\nthree\nfour\nFIVE\n", r.text)
        assertEquals(0, r.conflicts)
    }

    @Test
    fun `edits to adjacent lines are independent, not a conflict`() {
        val base = "a\nb\nc\nd\n"
        val ours = "a\nB\nc\nd\n"
        val theirs = "a\nb\nC\nd\n"

        val r = merge(base, ours, theirs)

        assertEquals("a\nB\nC\nd\n", r.text)
        assertEquals(0, r.conflicts)
    }

    @Test
    fun `both sides changing the same line conflicts, keeping both versions`() {
        val base = "a\nb\nc\n"
        val ours = "a\nMINE\nc\n"
        val theirs = "a\nDISK\nc\n"

        val r = merge(base, ours, theirs)

        assertEquals(1, r.conflicts)
        assertEquals(
            "a\n<<<<<<< mine\nMINE\n=======\nDISK\n>>>>>>> disk\nc\n",
            r.text,
        )
    }

    @Test
    fun `a conflict block is parseable by ConflictParser, so the diff view can resolve it`() {
        val r = merge("a\nb\nc\n", "a\nMINE\nc\n", "a\nDISK\nc\n")

        assertTrue(ConflictParser.hasConflicts(r.text))
        val segments = ConflictParser.parse(r.text)
        val conflict = segments.filterIsInstance<ConflictParser.MergeSegment.Conflict>().single()
        assertEquals("MINE\n", conflict.ours)
        assertEquals("DISK\n", conflict.theirs)
        // Lossless: the parse can rebuild exactly what the merge produced.
        val rebuilt = segments.joinToString("") {
            when (it) {
                is ConflictParser.MergeSegment.Stable -> it.text
                is ConflictParser.MergeSegment.Conflict -> it.raw
            }
        }
        assertEquals(r.text, rebuilt)
        // And resolving it yields a clean file.
        val resolved = ConflictParser.resolve(segments, 0, ConflictParser.Choice.OURS)
        assertEquals("a\nMINE\nc\n", resolved)
    }

    @Test
    fun `both sides making the identical change is not a conflict`() {
        val base = "a\nb\nc\n"
        val same = "a\nSAME\nc\n"

        val r = merge(base, same, same)

        assertEquals(same, r.text)
        assertEquals(0, r.conflicts)
    }

    @Test
    fun `an untouched side is taken wholesale`() {
        val base = "a\nb\n"
        assertEquals("a\nb\nc\n", merge(base, base, "a\nb\nc\n").text)
        assertEquals("a\nb\nc\n", merge(base, "a\nb\nc\n", base).text)
        assertEquals(0, merge(base, base, "a\nb\nc\n").conflicts)
    }

    @Test
    fun `entangled multi-line edits collapse into one conflict block, not interleaved markers`() {
        val base = "1\n2\n3\n4\n5\n"
        val ours = "1\nX\nY\nZ\n5\n"
        val theirs = "1\nP\nQ\n5\n"

        val r = merge(base, ours, theirs)

        assertEquals(1, r.conflicts)
        assertEquals("1\n<<<<<<< mine\nX\nY\nZ\n=======\nP\nQ\n>>>>>>> disk\n5\n", r.text)
    }

    @Test
    fun `insertions at the same point conflict rather than being silently doubled`() {
        val base = "a\nb\n"
        val ours = "a\nmine\nb\n"
        val theirs = "a\ndisk\nb\n"

        val r = merge(base, ours, theirs)

        assertEquals(1, r.conflicts)
        // Match whole lines: the marker labels are "mine"/"disk" too.
        assertEquals(1, Regex("^mine$", RegexOption.MULTILINE).findAll(r.text).count(), "our line appears once")
        assertEquals(1, Regex("^disk$", RegexOption.MULTILINE).findAll(r.text).count())
    }

    @Test
    fun `nothing is ever dropped — every line of both sides reaches the output`() {
        val base = "a\nb\nc\nd\ne\nf\n"
        val ours = "a\nB1\nB2\nc\nd\ne\nf\n"
        val theirs = "a\nb\nc\nd\nE1\nf\nG\n"

        val text = merge(base, ours, theirs).text

        listOf("B1", "B2", "E1", "G").forEach {
            assertTrue(text.contains(it), "$it must survive the merge")
        }
    }

    @Test
    fun `a file with no trailing newline still gets well-formed markers`() {
        val base = "a\nb"
        val ours = "a\nMINE"
        val theirs = "a\nDISK"

        val r = merge(base, ours, theirs)

        assertEquals(1, r.conflicts)
        assertTrue(ConflictParser.hasConflicts(r.text))
        assertTrue(r.text.lineSequence().any { it == ">>>>>>> disk" }, "end marker is on its own line")
        assertEquals(1, ConflictParser.parse(r.text).filterIsInstance<ConflictParser.MergeSegment.Conflict>().size)
    }

    @Test
    fun `CRLF line endings are preserved and markers match them`() {
        val base = "a\r\nb\r\nc\r\n"
        val ours = "a\r\nMINE\r\nc\r\n"
        val theirs = "a\r\nDISK\r\nc\r\n"

        val r = merge(base, ours, theirs)

        assertEquals("a\r\n<<<<<<< mine\r\nMINE\r\n=======\r\nDISK\r\n>>>>>>> disk\r\nc\r\n", r.text)
    }

    @Test
    fun `deletion on one side and edit on the other conflicts`() {
        val base = "a\nb\nc\n"
        val ours = "a\nc\n" // we deleted b
        val theirs = "a\nB!\nc\n" // disk edited it

        val r = merge(base, ours, theirs)

        assertEquals(1, r.conflicts)
        assertTrue(r.text.contains("B!"), "the disk version is not lost to our delete")
    }

    @Test
    fun `an empty buffer against a populated disk conflicts rather than blanking the file`() {
        val r = merge("a\nb\n", "", "a\nb\nc\n")

        assertTrue(r.text.contains("c"), "disk content survives an emptied buffer")
    }
}
