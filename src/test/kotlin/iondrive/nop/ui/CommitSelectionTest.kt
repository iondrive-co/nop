package iondrive.nop.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CommitSelectionTest {

    private val test = "systemtest/test_political_ad.py"
    private val fix = "persistence/CreativeApprovalsStore.java"

    private fun CommitSelection.move(from: Set<String>, to: Set<String>, headMoved: Boolean = false, writing: Boolean = false) =
        reconcile(from, to, headMoved, writing)

    @Test
    fun `a file stashed and popped back comes back ticked and is asked about`() {
        val start = setOf(test, fix)
        val popped = CommitSelection.loaded(start)
            .move(start, setOf(test))
            .move(setOf(test), start)

        assertEquals(start, popped.ticked)
        assertEquals(setOf(fix), popped.returned)
        assertEquals(listOf(fix), popped.unreviewed(listOf(test, fix)))
    }

    @Test
    fun `an unticked file that leaves and comes back stays unticked`() {
        val start = setOf(test, fix)
        val popped = CommitSelection.loaded(start).toggle(fix)
            .move(start, setOf(test))
            .move(setOf(test), start)

        assertEquals(setOf(test), popped.ticked)
        assertEquals(setOf(fix), popped.returned)
    }

    @Test
    fun `a file committed and edited again is a new change`() {
        val start = setOf(test, fix)
        val edited = CommitSelection.loaded(start)
            .move(start, setOf(test), headMoved = true)
            .move(setOf(test), start)

        assertEquals(start, edited.ticked)
        assertEquals(emptyList<String>(), edited.unreviewed(listOf(test, fix)))
    }

    @Test
    fun `a file that leaves on a commit and returns from a stash made before it is still returned`() {
        val start = setOf(test, fix)
        val popped = CommitSelection.loaded(start)
            .move(start, setOf(test))
            .move(setOf(test), emptySet(), headMoved = true)
            .move(emptySet(), setOf(fix))

        assertEquals(setOf(fix), popped.returned)
    }

    @Test
    fun `a new file is ticked, and asked about only when it appears while a message is written`() {
        val quiet = CommitSelection.loaded(setOf(test)).move(setOf(test), setOf(test, fix))
        val writing = CommitSelection.loaded(setOf(test)).move(setOf(test), setOf(test, fix), writing = true)

        assertEquals(setOf(test, fix), quiet.ticked)
        assertEquals(emptyList<String>(), quiet.unreviewed(listOf(test, fix)))
        assertEquals(setOf(test, fix), writing.ticked)
        assertEquals(listOf(fix), writing.unreviewed(listOf(test, fix)))
    }

    @Test
    fun `opening a file or toggling it clears its marks`() {
        val start = setOf(test, fix)
        val popped = CommitSelection.loaded(start)
            .move(start, setOf(test))
            .move(setOf(test), start)

        assertEquals(emptyList<String>(), popped.acknowledge(fix).unreviewed(listOf(test, fix)))
        assertEquals(emptyList<String>(), popped.toggle(fix).toggle(fix).unreviewed(listOf(test, fix)))
    }

    @Test
    fun `an unticked marked file is not asked about`() {
        val start = setOf(test, fix)
        val popped = CommitSelection.loaded(start).toggle(fix)
            .move(start, setOf(test))
            .move(setOf(test), start)

        assertEquals(emptyList<String>(), popped.unreviewed(popped.ticked))
    }
}
