package io.iondrive.nop.diff

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DiffComputerTest {
    @Test
    fun `identical files have no changes`() {
        val r = DiffComputer.compute("a\nb\nc\n", "a\nb\nc\n")
        assertFalse(r.hasChanges)
        assertEquals(3, r.rows.size)
        assertTrue(r.rows.all { it.kind == RowKind.EQUAL })
    }

    @Test
    fun `pure addition produces INSERT rows on new side only`() {
        val r = DiffComputer.compute("a\n", "a\nb\n")
        val inserts = r.rows.filter { it.kind == RowKind.INSERT }
        assertEquals(1, inserts.size)
        assertEquals("b", inserts[0].newLine)
        assertNull(inserts[0].oldLine)
        assertEquals(2, inserts[0].newLineNumber)
    }

    @Test
    fun `pure deletion produces DELETE rows on old side only`() {
        val r = DiffComputer.compute("a\nb\n", "a\n")
        val deletes = r.rows.filter { it.kind == RowKind.DELETE }
        assertEquals(1, deletes.size)
        assertEquals("b", deletes[0].oldLine)
        assertNull(deletes[0].newLine)
        assertEquals(2, deletes[0].oldLineNumber)
    }

    @Test
    fun `change with inline word edit produces CHANGE row with changed span`() {
        val r = DiffComputer.compute("the quick brown fox\n", "the slow brown fox\n")
        val change = r.rows.single { it.kind == RowKind.CHANGE }

        assertEquals("the quick brown fox", change.oldLine)
        assertEquals("the slow brown fox", change.newLine)

        // The "quick" word in the old side should be in a changed span
        val oldChanged = change.oldSpans.filter { it.changed }
        assertTrue(oldChanged.isNotEmpty(), "expected at least one changed span on old side")
        val oldChangedText = oldChanged.joinToString("") {
            change.oldLine!!.substring(it.startChar, it.endCharExclusive)
        }
        assertTrue(oldChangedText.contains("quick"), "expected 'quick' in changed span; got '$oldChangedText'")

        val newChangedText = change.newSpans.filter { it.changed }.joinToString("") {
            change.newLine!!.substring(it.startChar, it.endCharExclusive)
        }
        assertTrue(newChangedText.contains("slow"), "expected 'slow' in changed span; got '$newChangedText'")
    }

    @Test
    fun `unchanged portions of a CHANGE line are spanned as unchanged`() {
        val r = DiffComputer.compute("the quick brown fox\n", "the slow brown fox\n")
        val change = r.rows.single { it.kind == RowKind.CHANGE }

        val unchangedOnNew = change.newSpans.filter { !it.changed }
        val text = unchangedOnNew.joinToString("") {
            change.newLine!!.substring(it.startChar, it.endCharExclusive)
        }
        // We expect the unchanged words (the / brown / fox) to be present
        assertTrue(text.contains("the"), "unchanged span should contain 'the'")
        assertTrue(text.contains("brown") || text.contains("fox"), "unchanged span should contain tail words")
    }

    @Test
    fun `addition at end with no trailing newline still parses`() {
        val r = DiffComputer.compute("a\nb", "a\nb\nc")
        assertNotNull(r.rows.find { it.newLine == "c" })
    }

    @Test
    fun `every span is in range for the line it annotates`() {
        // A bigger, jagged diff — exercises CHANGE / INSERT / DELETE / EQUAL with inline word
        // edits. Regression for the StringIndexOOB seen while scrolling: every emitted span
        // must satisfy 0 <= startChar <= endCharExclusive <= line.length.
        val old = buildString {
            for (i in 0 until 60) appendLine("fun line$i(x: Int): Int { return x * $i }")
        }
        val new = buildString {
            for (i in 0 until 60) {
                when (i) {
                    7, 23, 41 -> appendLine("fun line$i(x: Int): Int { return /* tweaked */ x * $i }")
                    13 -> { /* delete */ }
                    else -> appendLine("fun line$i(x: Int): Int { return x * $i }")
                }
            }
            appendLine("// extra trailing line")
        }
        val r = DiffComputer.compute(old, new)
        for (row in r.rows) {
            row.oldLine?.let { line ->
                for (s in row.oldSpans) {
                    assertTrue(s.startChar in 0..line.length, "old span start ${s.startChar} for '$line'")
                    assertTrue(s.endCharExclusive in s.startChar..line.length, "old span end ${s.endCharExclusive} for '$line'")
                }
            }
            row.newLine?.let { line ->
                for (s in row.newSpans) {
                    assertTrue(s.startChar in 0..line.length, "new span start ${s.startChar} for '$line'")
                    assertTrue(s.endCharExclusive in s.startChar..line.length, "new span end ${s.endCharExclusive} for '$line'")
                }
            }
        }
    }
}
