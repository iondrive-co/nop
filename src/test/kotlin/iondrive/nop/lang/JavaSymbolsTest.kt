package iondrive.nop.lang

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JavaSymbolsTest {
    private fun decls(source: String, fileName: String = "T.java"): List<JavaDecl> {
        val parsed = assertNotNull(JavaParse.parse(source, fileName), "should parse")
        return JavaSymbols.declarations(parsed)
    }

    /** The declaration's recorded range really is the name, not the line or the whole declaration. */
    private fun assertNamesItself(source: String, decls: List<JavaDecl>) {
        for (d in decls) {
            assertEquals(d.name, source.substring(d.nameStart, d.nameEnd), "range for ${d.kind} ${d.name}")
        }
    }

    @Test
    fun `types methods and fields are all found`() {
        val src = """
            package com.example;

            public class Greeter {
                private final String name;
                static int count = 0;

                Greeter(String name) { this.name = name; }

                public String greet() { return "hi " + name; }
            }
        """.trimIndent()
        val found = decls(src, "Greeter.java")
        assertNamesItself(src, found)
        assertEquals(
            listOf(
                "TYPE com.example.Greeter",
                "FIELD com.example.Greeter.name",
                "FIELD com.example.Greeter.count",
                "METHOD com.example.Greeter.Greeter",
                "METHOD com.example.Greeter.greet",
            ),
            found.map { "${it.kind} ${it.fqn}" },
        )
    }

    @Test
    fun `nested types are qualified by their outer type`() {
        val src = """
            package a.b;
            class Outer {
                static class Inner {
                    int deep;
                }
            }
        """.trimIndent()
        val found = decls(src, "Outer.java")
        assertNamesItself(src, found)
        assertEquals("a.b.Outer.Inner", found.single { it.name == "Inner" }.fqn)
        assertEquals("a.b.Outer.Inner", found.single { it.name == "deep" }.owner)
    }

    @Test
    fun `a field whose type shares its name resolves to the name, not the type`() {
        // The case a "first occurrence of the word" search gets wrong every time.
        val src = "class C { Foo Foo = new Foo(); }"
        val found = decls(src)
        val field = found.single { it.kind == JavaDeclKind.FIELD }
        assertEquals("Foo", field.name)
        assertEquals(src.indexOf("Foo Foo") + 4, field.nameStart, "must be the declarator, not the type")
    }

    @Test
    fun `an annotation sharing the type's name is not mistaken for it`() {
        val src = "@Widget\nclass Widget { }"
        val type = decls(src).single { it.kind == JavaDeclKind.TYPE }
        assertEquals(src.indexOf("class Widget") + "class ".length, type.nameStart)
    }

    @Test
    fun `constructors are recorded under the class's source name, not init`() {
        val found = decls("class Thing { Thing() {} Thing(int x) {} }")
        val ctors = found.filter { it.kind == JavaDeclKind.METHOD }
        assertEquals(listOf("Thing", "Thing"), ctors.map { it.name })
        assertEquals(listOf(0, 1), ctors.map { it.arity })
    }

    @Test
    fun `generic classes and methods are found`() {
        val src = "class Box<T> { <R> R map(T in) { return null; } }"
        val found = decls(src)
        assertNamesItself(src, found)
        assertEquals("Box", found.single { it.kind == JavaDeclKind.TYPE }.name)
        assertEquals("map", found.single { it.kind == JavaDeclKind.METHOD }.name)
    }

    @Test
    fun `interfaces enums records and annotation types are all types`() {
        val src = """
            interface I { void go(); }
            enum E { RED, GREEN; }
            record P(int x, int y) { }
            @interface A { }
        """.trimIndent()
        val found = decls(src)
        assertNamesItself(src, found)
        val types = found.filter { it.kind == JavaDeclKind.TYPE }.map { it.name }
        assertEquals(listOf("I", "E", "P", "A"), types)
        assertEquals("go", found.single { it.kind == JavaDeclKind.METHOD }.name)
        assertTrue(found.any { it.name == "RED" && it.kind == JavaDeclKind.FIELD })
    }

    @Test
    fun `a class with extends and implements still resolves its own name`() {
        val src = "class Impl extends Base implements Iface { }"
        val type = decls(src).single { it.kind == JavaDeclKind.TYPE }
        assertEquals(src.indexOf("Impl"), type.nameStart)
    }

    @Test
    fun `privacy is recorded`() {
        val found = decls("class C { private int hidden; public int shown; private void p() {} }")
        assertTrue(found.single { it.name == "hidden" }.isPrivate)
        assertTrue(!found.single { it.name == "shown" }.isPrivate)
        assertTrue(found.single { it.name == "p" }.isPrivate)
    }

    @Test
    fun `multiple declarators on one line are each found`() {
        val src = "class C { int a, b; }"
        val found = decls(src).filter { it.kind == JavaDeclKind.FIELD }
        assertNamesItself(src, found)
        assertEquals(listOf("a", "b"), found.map { it.name })
    }

    @Test
    fun `locals inside method bodies are not indexed`() {
        val found = decls("class C { void m() { int localOnly = 1; } }")
        assertNull(found.firstOrNull { it.name == "localOnly" })
    }

    @Test
    fun `anonymous class members are not indexed under a nameless owner`() {
        val found = decls(
            "class C { Runnable r = new Runnable() { public void run() {} }; }",
        )
        assertNull(found.firstOrNull { it.name == "run" })
        assertEquals(listOf("C", "r"), found.map { it.name })
    }

    @Test
    fun `line numbers point at the declaration`() {
        val src = "class C {\n\n    void second() {}\n}"
        assertEquals(3, decls(src).single { it.name == "second" }.line)
    }

    @Test
    fun `a name mentioned in a javadoc above the declaration is not taken for it`() {
        val src = "/** greet greet greet */\nclass C { /** greet */ void greet() {} }"
        val m = decls(src).single { it.kind == JavaDeclKind.METHOD }
        assertEquals(src.lastIndexOf("greet"), m.nameStart)
    }

    @Test
    fun `a file that does not parse still yields what did`() {
        val found = decls("class C { void ok() {} void broken( { }")
        assertTrue(found.any { it.name == "ok" }, "the good method should survive its broken sibling")
    }
}
