package iondrive.nop.lang

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JavaUsagesTest {
    private fun parse(src: String, name: String = "T.java") = assertNotNull(JavaParse.parse(src, name))

    /** The target the caret lands on when placed at the first occurrence of [at]. */
    private fun targetOf(src: String, at: String, occurrence: Int = 0): UsageTarget? {
        var index = -1
        repeat(occurrence + 1) { index = src.indexOf(at, index + 1) }
        return JavaUsages.targetAt(parse(src), index + 1)
    }

    // ---- what the caret is on -----------------------------------------------------------------

    @Test
    fun `a caret on a local resolves to that local`() {
        val src = "class C { void m() { int total = 1; return total; } }"
        val target = targetOf(src, "total")
        assertTrue(target is UsageTarget.Local, "was $target")
    }

    @Test
    fun `a caret on a parameter resolves to a local`() {
        val src = "class C { void m(int size) { use(size); } void use(int x) {} }"
        assertTrue(targetOf(src, "size") is UsageTarget.Local)
    }

    @Test
    fun `a caret on a field resolves to a member`() {
        val src = "package p; class C { private int count; void m() { count++; } }"
        val target = targetOf(src, "count")
        assertEquals(UsageTarget.Member("count", "p.C", JavaDeclKind.FIELD, isPrivate = true), target)
    }

    @Test
    fun `a caret on a method name resolves to a member`() {
        val src = "package p; class C { public void greet() {} }"
        val target = targetOf(src, "greet") as UsageTarget.Member
        assertEquals(JavaDeclKind.METHOD, target.kind)
        assertFalse(target.isPrivate)
    }

    @Test
    fun `a caret on a class name resolves to a type`() {
        val src = "package com.x; class Widget { }"
        assertEquals(UsageTarget.Type("Widget", "com.x.Widget", "com.x"), targetOf(src, "Widget"))
    }

    @Test
    fun `a caret on an imported type resolves through the import`() {
        val src = "package p; import com.other.Gadget; class C { Gadget g; }"
        val target = targetOf(src, "Gadget", occurrence = 1) as UsageTarget.Type
        assertEquals("com.other.Gadget", target.fqn)
    }

    @Test
    fun `a local shadowing a field wins at the caret`() {
        // The narrowest thing that explains the name is the right answer.
        val src = "class C { int value; void m() { int value = 2; use(value); } void use(int v){} }"
        val target = targetOf(src, "value", occurrence = 2)
        assertTrue(target is UsageTarget.Local, "the local shadows the field here, but got $target")
    }

    @Test
    fun `a caret on a keyword resolves to nothing`() {
        assertNull(targetOf("class C { void m() { return; } }", "return"))
    }

    @Test
    fun `a caret on an unresolvable name resolves to nothing`() {
        // No declaration, no import — there is nothing honest to search for.
        assertNull(targetOf("class C { void m() { Mystery.call(); } }", "Mystery"))
    }

    // ---- finding usages -----------------------------------------------------------------------

    private fun find(root: Path, target: UsageTarget, origin: String?): UsageResult = runBlocking {
        val files = root.toFile().walkTopDown().filter { it.isFile }
            .map { it.relativeTo(root.toFile()).path.replace(File.separatorChar, '/') }.toList()
        JavaUsages.find(root.toFile(), files, target, origin, Dispatchers.Default)
    }

    private fun write(root: Path, rel: String, text: String) {
        val f = root.resolve(rel)
        f.parent.createDirectories()
        f.writeText(text)
    }

    @Test
    fun `a local's usages stop at its own scope`(@TempDir tmp: Path) {
        val src = """
            class C {
                void a() { int n = 1; use(n); }
                void b() { int n = 2; use(n); }
                void use(int x) {}
            }
        """.trimIndent()
        write(tmp, "C.java", src)
        val target = JavaUsages.targetAt(parse(src, "C.java"), src.indexOf("int n = 1") + 5)
        val result = find(tmp, assertNotNull(target), "C.java")
        assertTrue(result.exact)
        // The declaration and the one use in method a() — not the identically-named pair in b().
        assertEquals(2, result.usages.size)
        assertTrue(result.usages.all { it.line == 2 })
    }

    @Test
    fun `a local's usages exclude an inner redeclaration`(@TempDir tmp: Path) {
        val src = """
            class C {
                void m() {
                    int n = 1;
                    use(n);
                    { int n = 2; use(n); }
                }
                void use(int x) {}
            }
        """.trimIndent()
        write(tmp, "C.java", src)
        val target = JavaUsages.targetAt(parse(src, "C.java"), src.indexOf("int n = 1") + 5)
        val result = find(tmp, assertNotNull(target), "C.java")
        assertEquals(2, result.usages.size, "the shadowed inner pair must not be included")
        assertTrue(result.usages.none { it.line >= 5 })
    }

    @Test
    fun `a local's usages exclude a same-named member selection`(@TempDir tmp: Path) {
        val src = "class C { void m() { int len = 1; other.len = len; } }"
        write(tmp, "C.java", src)
        val target = JavaUsages.targetAt(parse(src, "C.java"), src.indexOf("int len") + 5)
        val result = find(tmp, assertNotNull(target), "C.java")
        // The declaration and the right-hand `len`; `other.len` is a field on something else.
        assertEquals(2, result.usages.size)
    }

    @Test
    fun `a private member is searched in its own file only`(@TempDir tmp: Path) {
        val src = "package p;\nclass C {\n  private int tally;\n  void m() { tally++; }\n}"
        write(tmp, "C.java", src)
        write(tmp, "Other.java", "package p;\nclass Other { int tally = 5; void m() { tally++; } }")
        val target = JavaUsages.targetAt(parse(src, "C.java"), src.indexOf("tally") + 2)
        val result = find(tmp, assertNotNull(target), "C.java")
        assertTrue(result.exact)
        assertTrue(result.usages.all { it.path == "C.java" }, "found ${result.usages.map { it.path }}")
        assertEquals(2, result.usages.size)
    }

    @Test
    fun `type usages follow imports and skip a same-named type elsewhere`(@TempDir tmp: Path) {
        val widget = "package com.ours;\npublic class Widget { }"
        write(tmp, "com/ours/Widget.java", widget)
        write(tmp, "com/ours/Near.java", "package com.ours;\nclass Near { Widget w; }")
        write(tmp, "com/far/Importer.java", "package com.far;\nimport com.ours.Widget;\nclass Importer { Widget w; }")
        write(tmp, "com/far/Impostor.java", "package com.far;\nimport com.other.Widget;\nclass Impostor { Widget w; }")

        val target = JavaUsages.targetAt(parse(widget, "Widget.java"), widget.indexOf("Widget") + 2)
        val result = find(tmp, assertNotNull(target), "com/ours/Widget.java")
        assertTrue(result.exact)
        val paths = result.usages.map { it.path }.toSet()
        assertEquals(
            setOf("com/ours/Widget.java", "com/ours/Near.java", "com/far/Importer.java"),
            paths,
            "the file importing a different Widget must be excluded",
        )
    }

    @Test
    fun `a public member search is marked approximate and says why`(@TempDir tmp: Path) {
        val src = "package p;\npublic class C { public int size() { return 0; } }"
        write(tmp, "C.java", src)
        write(tmp, "User.java", "package p;\nclass User { void m(C c) { c.size(); } }")
        val target = JavaUsages.targetAt(parse(src, "C.java"), src.indexOf("size") + 2)
        val result = find(tmp, assertNotNull(target), "C.java")
        assertFalse(result.exact, "without a classpath this cannot be exact")
        assertNotNull(result.note)
        assertTrue(result.usages.size >= 2)
    }

    @Test
    fun `a public member search excludes occurrences a local shadows`(@TempDir tmp: Path) {
        val src = "package p;\npublic class C { public int size;\n}"
        write(tmp, "C.java", src)
        write(tmp, "User.java", "package p;\nclass User { void m() { int size = 3; use(size); } void use(int i){} }")
        val target = JavaUsages.targetAt(parse(src, "C.java"), src.indexOf("size") + 2)
        val result = find(tmp, assertNotNull(target), "C.java")
        // The local `size` in User is provably not the field, even with no types in play.
        assertTrue(result.usages.none { it.path == "User.java" }, "found ${result.usages.map { it.path }}")
    }

    @Test
    fun `a field assigned through this in a constructor is found despite the parameter`(@TempDir tmp: Path) {
        // The standard constructor shape: the parameter shares the field's name and is in scope for
        // the whole body, but `this.label` names the field outright and cannot be shadowed by it.
        val src = """
            package p;
            class Widget {
                private final String label;
                Widget(String label) { this.label = label; }
                String show() { return label; }
            }
        """.trimIndent()
        write(tmp, "Widget.java", src)
        val target = JavaUsages.targetAt(parse(src, "Widget.java"), src.indexOf("String label;") + 8)
        val result = find(tmp, assertNotNull(target), "Widget.java")
        val lines = result.usages.map { it.line }.sorted()
        assertEquals(listOf(3, 4, 5), lines, "declaration, this.label, and the bare read in show()")
    }

    @Test
    fun `the parameter in that constructor is still its own local`(@TempDir tmp: Path) {
        val src = """
            package p;
            class Widget {
                private final String label;
                Widget(String label) { this.label = label; }
                String show() { return label; }
            }
        """.trimIndent()
        write(tmp, "Widget.java", src)
        // Caret on the parameter's own declaration.
        val target = JavaUsages.targetAt(parse(src, "Widget.java"), src.indexOf("Widget(String label") + 16)
        assertTrue(target is UsageTarget.Local, "was $target")
        val result = find(tmp, target, "Widget.java")
        // The parameter and the right-hand side of the assignment — not `this.label`, not show()'s.
        assertEquals(2, result.usages.size, "found ${result.usages.map { it.line to it.columnStart }}")
        assertTrue(result.usages.all { it.line == 4 })
    }

    @Test
    fun `usages carry the line text and the column of the name`(@TempDir tmp: Path) {
        val src = "package p;\nclass C {\n    private int hit;\n    void m() { hit = 2; }\n}"
        write(tmp, "C.java", src)
        val target = JavaUsages.targetAt(parse(src, "C.java"), src.indexOf("hit") + 1)
        val result = find(tmp, assertNotNull(target), "C.java")
        val use = result.usages.single { it.line == 4 }
        assertEquals("    void m() { hit = 2; }", use.lineText)
        assertEquals("hit", use.lineText.substring(use.columnStart, use.columnEnd))
        assertEquals("hit", src.substring(use.offsetStart, use.offsetEnd))
        assertFalse(use.isDeclaration)
        assertTrue(result.usages.single { it.line == 3 }.isDeclaration)
    }

    @Test
    fun `several usages on one line each get their own column`(@TempDir tmp: Path) {
        val src = "package p;\nclass C {\n    private int n;\n    void m() { this.n = this.n + this.n; }\n}"
        write(tmp, "C.java", src)
        val target = JavaUsages.targetAt(parse(src, "C.java"), src.indexOf("int n") + 4)
        val result = find(tmp, assertNotNull(target), "C.java")
        val onLine4 = result.usages.filter { it.line == 4 }
        assertEquals(3, onLine4.size)
        // Distinct, ascending columns, each actually landing on the name.
        assertEquals(onLine4.map { it.columnStart }.sorted(), onLine4.map { it.columnStart })
        for (use in onLine4) {
            assertEquals("n", use.lineText.substring(use.columnStart, use.columnEnd))
        }
    }

    @Test
    fun `a usage on the last line without a trailing newline is still mapped`(@TempDir tmp: Path) {
        val src = "package p;\nclass C { private int n; void m() { this.n = 1; } }"
        write(tmp, "C.java", src)
        val target = JavaUsages.targetAt(parse(src, "C.java"), src.indexOf("int n") + 4)
        val result = find(tmp, assertNotNull(target), "C.java")
        assertTrue(result.usages.isNotEmpty())
        for (use in result.usages) {
            assertEquals("n", use.lineText.substring(use.columnStart, use.columnEnd))
        }
    }

    @Test
    fun `windows line endings do not bleed into the row text`(@TempDir tmp: Path) {
        val src = "package p;\r\nclass C {\r\n    private int n;\r\n    void m() { this.n = 1; }\r\n}\r\n"
        write(tmp, "C.java", src)
        val target = JavaUsages.targetAt(parse(src, "C.java"), src.indexOf("int n") + 4)
        val result = find(tmp, assertNotNull(target), "C.java")
        assertTrue(result.usages.isNotEmpty())
        for (use in result.usages) {
            assertFalse(use.lineText.contains('\r'), "row text kept a carriage return")
            assertEquals("n", use.lineText.substring(use.columnStart, use.columnEnd))
        }
    }

    @Test
    fun `occurrences inside comments and strings are never usages`(@TempDir tmp: Path) {
        val src = """
            package p;
            class C {
                private int marker;
                // marker in a comment
                String s = "marker in a string";
                void m() { marker = 1; }
            }
        """.trimIndent()
        write(tmp, "C.java", src)
        val target = JavaUsages.targetAt(parse(src, "C.java"), src.indexOf("marker") + 2)
        val result = find(tmp, assertNotNull(target), "C.java")
        assertEquals(2, result.usages.size, "declaration + one real use")
    }
}
