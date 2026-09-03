package iondrive.nop.lang

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JavaParseTest {
    @Test
    fun `compiler is available under the test runtime`() {
        assertTrue(JavaParse.available, "tests run on a JDK; jdk.compiler must be present")
    }

    @Test
    fun `a clean file parses with no problems`() {
        val parsed = JavaParse.parse(
            """
            package com.example;

            public class Greeter {
                private final String name;

                Greeter(String name) { this.name = name; }

                public String greet() { return "hi " + name; }
            }
            """.trimIndent(),
            "Greeter.java",
        )
        assertNotNull(parsed)
        assertEquals(emptyList(), parsed.problems)
        assertEquals("com.example", parsed.packageName)
    }

    @Test
    fun `a missing semicolon underlines the token before the gap`() {
        val text = "class A { int x = 1 }\n"
        val parsed = assertNotNull(JavaParse.parse(text, "A.java"))
        assertEquals(1, parsed.problems.size)
        val problem = parsed.problems.single()
        // javac points at the whitespace where the ';' should have gone. Underlining whitespace
        // draws nothing, so the range is anchored back onto the `1` the user has to look at.
        assertEquals("1", text.substring(problem.start, problem.endExclusive))
    }

    @Test
    fun `a missing semicolon at a line end anchors to the previous line's last token`() {
        val text = "class A {\n  void m() {\n    int total = 1\n    return;\n  }\n}\n"
        val problem = assertNotNull(JavaParse.parse(text, "A.java")).problems.first()
        assertEquals("1", text.substring(problem.start, problem.endExclusive))
    }

    @Test
    fun `an anchored problem takes the whole preceding word, not its last letter`() {
        val text = "class A { int x = value }\n"
        val problem = assertNotNull(JavaParse.parse(text, "A.java")).problems.first()
        assertEquals("value", text.substring(problem.start, problem.endExclusive))
    }

    @Test
    fun `a diagnostic that already covers real text is left alone`() {
        val text = "class A { void m() { int 1x = 2; } }\n"
        val parsed = assertNotNull(JavaParse.parse(text, "A.java"))
        for (p in parsed.problems) {
            val covered = text.substring(p.start, p.endExclusive)
            assertTrue(covered.isNotBlank(), "problem covered only whitespace: '$covered'")
        }
    }

    @Test
    fun `a broken file still yields a tree for the parts that parsed`() {
        val parsed = assertNotNull(
            JavaParse.parse("class A { void ok() {} void broken( { } }", "A.java"),
        )
        assertTrue(parsed.problems.isNotEmpty())
        assertTrue(parsed.unit.typeDecls.isNotEmpty(), "javac recovers — the class should survive")
    }

    @Test
    fun `problems never run past the end of the text`() {
        val text = "class A {"
        val parsed = assertNotNull(JavaParse.parse(text, "A.java"))
        assertTrue(parsed.problems.isNotEmpty())
        for (p in parsed.problems) {
            assertTrue(p.start in 0..text.length, "start ${p.start} outside 0..${text.length}")
            assertTrue(p.endExclusive in (p.start + 1)..maxOf(p.start + 1, text.length), "end ${p.endExclusive}")
        }
    }

    @Test
    fun `line numbers count from one`() {
        val parsed = assertNotNull(JavaParse.parse("class A {\n  int x;\n}\n", "A.java"))
        assertEquals(1, parsed.lineAt(0))
        assertEquals(2, parsed.lineAt(parsed.text.indexOf("int")))
        assertEquals(3, parsed.lineAt(parsed.text.indexOf('}', 10)))
    }
}
