package iondrive.nop.lang

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JavaHeaderTest {
    private val ours = UsageTarget.Type("Widget", "com.ours.Widget", "com.ours")

    @Test
    fun `package and imports are read`() {
        val h = JavaHeader.of(
            """
            package com.ours;

            import java.util.List;
            import static java.util.Map.entry;
            import com.other.*;

            class C { }
            """.trimIndent(),
        )
        assertEquals("com.ours", h.packageName)
        assertEquals(listOf("java.util.List", "java.util.Map.entry"), h.imports)
        assertEquals(listOf("com.other"), h.wildcardPackages)
    }

    @Test
    fun `a file in the same package can see the type`() {
        assertTrue(JavaHeader.of("package com.ours; class C { Widget w; }").canSee(ours))
    }

    @Test
    fun `a file importing the type by name can see it`() {
        assertTrue(JavaHeader.of("package z; import com.ours.Widget; class C { }").canSee(ours))
    }

    @Test
    fun `a file importing a different same-named type cannot`() {
        // The case that separates this from a word search.
        assertFalse(JavaHeader.of("package z; import com.other.Widget; class C { Widget w; }").canSee(ours))
    }

    @Test
    fun `a wildcard import of the package can see it`() {
        assertTrue(JavaHeader.of("package z; import com.ours.*; class C { }").canSee(ours))
    }

    @Test
    fun `an unrelated file cannot see it`() {
        assertFalse(JavaHeader.of("package z; import java.util.List; class C { Widget w; }").canSee(ours))
    }

    @Test
    fun `a nested type is visible through an import of its outer type`() {
        val nested = UsageTarget.Type("Inner", "com.ours.Outer.Inner", "com.ours")
        assertTrue(JavaHeader.of("package z; import com.ours.Outer; class C { }").canSee(nested))
        assertEquals("com.ours.Outer", nested.outermostFqn)
    }

    @Test
    fun `the scan stops at the first type declaration`() {
        // `import` inside a string or after the class opens must not be read as a real import.
        val h = JavaHeader.of("package p;\nclass C { String s = \"import com.fake.X;\"; }")
        assertEquals(emptyList(), h.imports)
    }

    @Test
    fun `comments inside a header do not corrupt it`() {
        val h = JavaHeader.of("package /* here */ com.ours;\nimport com.ours.Widget; // used\nclass C {}")
        assertEquals("com.ours", h.packageName)
        assertEquals(listOf("com.ours.Widget"), h.imports)
    }

    @Test
    fun `an import split across lines still resolves`() {
        val h = JavaHeader.of("package p;\nimport com\n  .ours\n  .Widget;\nclass C {}")
        assertEquals(listOf("com.ours.Widget"), h.imports)
    }

    @Test
    fun `importedFqnFor finds a type by simple name`() {
        val h = JavaHeader.of("package p; import com.ours.Widget; import java.util.List; class C {}")
        assertEquals("com.ours.Widget", h.importedFqnFor("Widget"))
        assertNull(h.importedFqnFor("Gadget"))
    }

    @Test
    fun `a file with no package is in the default package`() {
        assertEquals("", JavaHeader.of("class C { }").packageName)
    }
}
