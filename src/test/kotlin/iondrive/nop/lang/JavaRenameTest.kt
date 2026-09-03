package iondrive.nop.lang

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JavaRenameTest {
    private fun usage(path: String, offsetStart: Int, offsetEnd: Int, line: Int = 1) = JavaUsage(
        path = path,
        line = line,
        lineText = "",
        columnStart = 0,
        columnEnd = 0,
        offsetStart = offsetStart,
        offsetEnd = offsetEnd,
        isDeclaration = false,
    )

    private fun result(
        target: UsageTarget,
        usages: List<JavaUsage>,
        exact: Boolean = true,
        note: String? = null,
    ) = UsageResult(target, usages, exact, note)

    private val member = UsageTarget.Member("count", "p.C", JavaDeclKind.FIELD, isPrivate = true)

    // ---- rewriting ----------------------------------------------------------------------------

    @Test
    fun `edits are applied back to front so later ranges stay valid`() {
        val text = "int n = n + n;"
        val ranges = listOf(4 until 5, 8 until 9, 12 until 13)
        assertEquals("int total = total + total;", JavaRename.applyEdits(text, ranges, "total"))
    }

    @Test
    fun `applying a shorter name is equally safe`() {
        val text = "int total = total + total;"
        val ranges = JavaLexer.occurrencesOf(text, "total")
        assertEquals("int n = n + n;", JavaRename.applyEdits(text, ranges, "n"))
    }

    @Test
    fun `an empty edit list leaves the text alone`() {
        assertEquals("unchanged", JavaRename.applyEdits("unchanged", emptyList(), "x"))
    }

    @Test
    fun `ranges past the end of the text are skipped rather than throwing`() {
        assertEquals("abc", JavaRename.applyEdits("abc", listOf(10 until 12), "z"))
    }

    // ---- validation ---------------------------------------------------------------------------

    @Test
    fun `a keyword is refused`() {
        val plan = JavaRename.plan(result(member, listOf(usage("C.java", 0, 5))), "class", "C.java", null)
        assertFalse(plan.canApply)
        assertTrue(plan.problems.single().contains("keyword"))
    }

    @Test
    fun `an invalid identifier is refused`() {
        val plan = JavaRename.plan(result(member, listOf(usage("C.java", 0, 5))), "2fast", "C.java", null)
        assertFalse(plan.canApply)
        assertTrue(plan.problems.single().contains("not a valid Java identifier"))
    }

    @Test
    fun `an empty name is refused`() {
        val plan = JavaRename.plan(result(member, listOf(usage("C.java", 0, 5))), "  ", "C.java", null)
        assertFalse(plan.canApply)
    }

    @Test
    fun `renaming to the current name is refused`() {
        val plan = JavaRename.plan(result(member, listOf(usage("C.java", 0, 5))), "count", "C.java", null)
        assertFalse(plan.canApply)
        assertTrue(plan.problems.single().contains("already its name"))
    }

    @Test
    fun `a plan with no occurrences cannot be applied`() {
        val plan = JavaRename.plan(result(member, emptyList()), "tally", "C.java", null)
        assertFalse(plan.canApply)
    }

    // ---- collisions ---------------------------------------------------------------------------

    @Test
    fun `a member colliding with an existing member of the same type is refused`() {
        val src = "package p; class C { private int count; private int tally; }"
        val plan = JavaRename.plan(result(member, listOf(usage("C.java", 0, 5))), "tally", "C.java", src)
        assertFalse(plan.canApply)
        assertTrue(plan.problems.single().contains("already declares"))
    }

    @Test
    fun `a member not colliding is allowed`() {
        val src = "package p; class C { private int count; }"
        val plan = JavaRename.plan(result(member, listOf(usage("C.java", 0, 5))), "tally", "C.java", src)
        assertTrue(plan.canApply)
    }

    @Test
    fun `a member of a different type is not a collision`() {
        val src = "package p; class C { private int count; }\nclass D { private int tally; }"
        val plan = JavaRename.plan(result(member, listOf(usage("C.java", 0, 5))), "tally", "C.java", src)
        assertTrue(plan.canApply, "D.tally is a different type's member: ${plan.problems}")
    }

    @Test
    fun `a local colliding with a name already in scope is refused`() {
        val src = "class C { void m() { int n = 1; int taken = 2; } }"
        val scope = src.indexOf("{ int n")..src.length - 1
        val target = UsageTarget.Local("n", scope, emptyList())
        val plan = JavaRename.plan(result(target, listOf(usage("C.java", 0, 1))), "taken", "C.java", src)
        assertFalse(plan.canApply)
        assertTrue(plan.problems.single().contains("already used in this scope"))
    }

    @Test
    fun `a type colliding with another type in the same file is refused`() {
        val src = "package p; class Widget { } class Gadget { }"
        val target = UsageTarget.Type("Widget", "p.Widget", "p")
        val plan = JavaRename.plan(result(target, listOf(usage("Widget.java", 17, 23))), "Gadget", "Widget.java", src)
        assertFalse(plan.canApply)
    }

    // ---- file renames -------------------------------------------------------------------------

    @Test
    fun `renaming a type renames the file named after it`() {
        val target = UsageTarget.Type("Widget", "com.x.Widget", "com.x")
        val plan = JavaRename.plan(
            result(target, listOf(usage("com/x/Widget.java", 0, 6))),
            "Gadget", "com/x/Widget.java", "package com.x; class Widget {}",
        )
        assertEquals(FileRename("com/x/Widget.java", "Gadget.java"), plan.fileRename)
    }

    @Test
    fun `a type rename onto an existing file name is refused before anything is written`() {
        // The half-done state this prevents: every reference rewritten, then the file move fails.
        val target = UsageTarget.Type("Widget", "com.x.Widget", "com.x")
        val plan = JavaRename.plan(
            result(target, listOf(usage("com/x/Widget.java", 0, 6))),
            "Gadget", "com/x/Widget.java", "package com.x; class Widget {}",
            pathExists = { it == "com/x/Gadget.java" },
        )
        assertFalse(plan.canApply)
        assertTrue(plan.problems.single().contains("already exists"))
    }

    @Test
    fun `a free destination file name is allowed`() {
        val target = UsageTarget.Type("Widget", "com.x.Widget", "com.x")
        val plan = JavaRename.plan(
            result(target, listOf(usage("com/x/Widget.java", 0, 6))),
            "Gadget", "com/x/Widget.java", "package com.x; class Widget {}",
            pathExists = { false },
        )
        assertTrue(plan.canApply, "${plan.problems}")
    }

    @Test
    fun `a type at the project root checks the right destination path`() {
        val target = UsageTarget.Type("Widget", "Widget", "")
        val plan = JavaRename.plan(
            result(target, listOf(usage("Widget.java", 0, 6))),
            "Gadget", "Widget.java", "class Widget {}",
            pathExists = { it == "Gadget.java" },
        )
        assertFalse(plan.canApply, "a root-level clash must be caught too")
    }

    @Test
    fun `a type in a file not named after it leaves the file alone`() {
        val target = UsageTarget.Type("Helper", "com.x.Helper", "com.x")
        val plan = JavaRename.plan(
            result(target, listOf(usage("com/x/Widget.java", 0, 6))),
            "Aide", "com/x/Widget.java", "package com.x; class Widget {} class Helper {}",
        )
        assertNull(plan.fileRename)
    }

    @Test
    fun `renaming a member never renames a file`() {
        val plan = JavaRename.plan(
            result(member, listOf(usage("C.java", 0, 5))), "tally", "C.java", "package p; class C { int count; }",
        )
        assertNull(plan.fileRename)
    }

    // ---- what the plan reports ----------------------------------------------------------------

    @Test
    fun `the plan counts occurrences and files`() {
        val plan = JavaRename.plan(
            result(member, listOf(usage("A.java", 0, 5), usage("A.java", 10, 15), usage("B.java", 3, 8))),
            "tally", "A.java", null,
        )
        assertEquals(3, plan.occurrenceCount)
        assertEquals(2, plan.fileCount)
        assertEquals(listOf(0 until 5, 10 until 15), plan.edits.getValue("A.java"))
    }

    @Test
    fun `an approximate search carries its warning into the plan`() {
        val public = UsageTarget.Member("size", "p.C", JavaDeclKind.METHOD, isPrivate = false)
        val plan = JavaRename.plan(
            result(public, listOf(usage("C.java", 0, 4)), exact = false, note = "may include others"),
            "length", "C.java", null,
        )
        assertFalse(plan.exact)
        assertEquals("may include others", plan.note)
        // Approximate is not the same as forbidden: the user is told, and decides.
        assertTrue(plan.canApply)
    }
}
