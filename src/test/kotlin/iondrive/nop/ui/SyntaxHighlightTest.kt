package iondrive.nop.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SyntaxHighlightTest {

    private fun List<Token>.containsExact(text: String, snippet: String, kind: TokenKind): Boolean =
        any { it.kind == kind && text.substring(it.start, it.endExclusive) == snippet }

    @Test
    fun `kotlin extension picks kotlin tokenizer`() {
        assertNotNull(tokenizerForExtension("kt"))
        assertNotNull(tokenizerForExtension("kts"))
    }

    @Test
    fun `unknown extension falls back to null`() {
        assertNull(tokenizerForExtension("xyz"))
        assertNull(tokenizerForExtension(""))
        assertNull(tokenizerForExtension(null))
    }

    @Test
    fun `kotlin lexer highlights keywords strings numbers and comments`() {
        val src = """
            // hi
            fun foo(): Int { return 42 + 1 }
            val s = "hello"
        """.trimIndent()
        val tokens = tokenizeKotlin(src)
        assertTrue(tokens.containsExact(src, "// hi", TokenKind.COMMENT))
        assertTrue(tokens.containsExact(src, "fun", TokenKind.KEYWORD))
        assertTrue(tokens.containsExact(src, "return", TokenKind.KEYWORD))
        assertTrue(tokens.containsExact(src, "val", TokenKind.KEYWORD))
        assertTrue(tokens.containsExact(src, "42", TokenKind.NUMBER))
        assertTrue(tokens.containsExact(src, "\"hello\"", TokenKind.STRING))
    }

    @Test
    fun `kotlin keyword inside string is not tokenized`() {
        val src = "val s = \"fun is a keyword\""
        val tokens = tokenizeKotlin(src)
        // The "fun" inside the string must not be a KEYWORD — the string swallows it.
        val funKeyword = tokens.find { it.kind == TokenKind.KEYWORD && src.substring(it.start, it.endExclusive) == "fun" }
        assertNull(funKeyword, "string-internal 'fun' should not be highlighted as keyword")
        assertTrue(tokens.containsExact(src, "val", TokenKind.KEYWORD))
        assertTrue(tokens.containsExact(src, "\"fun is a keyword\"", TokenKind.STRING))
    }

    @Test
    fun `json lexer highlights strings numbers and literals`() {
        val src = """{"name": "nop", "count": 3, "ok": true, "x": null}"""
        val tokens = tokenizeJson(src)
        assertTrue(tokens.containsExact(src, "\"name\"", TokenKind.STRING))
        assertTrue(tokens.containsExact(src, "\"nop\"", TokenKind.STRING))
        assertTrue(tokens.containsExact(src, "3", TokenKind.NUMBER))
        assertTrue(tokens.containsExact(src, "true", TokenKind.LITERAL))
        assertTrue(tokens.containsExact(src, "null", TokenKind.LITERAL))
    }

    @Test
    fun `markdown lexer highlights headings code and emphasis`() {
        val src = """
            # Title
            Some **bold** text and *italic* and `code`.
            ```kotlin
            val x = 1
            ```
        """.trimIndent()
        val tokens = tokenizeMarkdown(src)
        assertTrue(tokens.any { it.kind == TokenKind.HEADING })
        assertTrue(tokens.any { it.kind == TokenKind.EMPHASIS })
        assertTrue(tokens.any { it.kind == TokenKind.STRING }, "fenced code should be a STRING token")
    }

    @Test
    fun `js ts lexer highlights keywords strings and template literals`() {
        val src = """
            const greeting = `hello ${'$'}{name}`;
            function go() { return 'x'; }
            // done
        """.trimIndent()
        val tokens = tokenizeJsTs(src)
        assertTrue(tokens.containsExact(src, "const", TokenKind.KEYWORD))
        assertTrue(tokens.containsExact(src, "function", TokenKind.KEYWORD))
        assertTrue(tokens.containsExact(src, "return", TokenKind.KEYWORD))
        assertTrue(tokens.containsExact(src, "// done", TokenKind.COMMENT))
        assertTrue(tokens.any { it.kind == TokenKind.STRING })
    }

    @Test
    fun `shell lexer highlights keywords comments and variables`() {
        // Note: $HOME inside double quotes is correctly subsumed by the STRING token —
        // shell expands it at runtime, but the source text is still a string literal.
        val src = """
            # build
            if [ -d ${'$'}HOME ]; then
              echo "yes"
            fi
        """.trimIndent()
        val tokens = tokenizeShell(src)
        assertTrue(tokens.containsExact(src, "if", TokenKind.KEYWORD))
        assertTrue(tokens.containsExact(src, "then", TokenKind.KEYWORD))
        assertTrue(tokens.containsExact(src, "fi", TokenKind.KEYWORD))
        assertTrue(tokens.containsExact(src, "# build", TokenKind.COMMENT))
        assertTrue(tokens.containsExact(src, "\$HOME", TokenKind.LITERAL))
        assertTrue(tokens.containsExact(src, "\"yes\"", TokenKind.STRING))
    }

    @Test
    fun `ansible extension picks ansible tokenizer`() {
        assertNotNull(tokenizerForExtension("yml"))
        assertNotNull(tokenizerForExtension("yaml"))
        assertNotNull(tokenizerForExtension("j2"))
    }

    @Test
    fun `ansible lexer highlights keys comments and jinja`() {
        val src = """
            ---
            # Install nginx
            - name: ensure nginx is running
              hosts: web
              become: true
              tasks:
                - name: install
                  apt:
                    name: nginx
                    state: present
                  when: ansible_os_family == "Debian"
                - name: render config
                  template:
                    src: nginx.conf.j2
                    dest: "/etc/nginx/nginx.conf"
                  vars:
                    port: 8080
                  notify: restart nginx
        """.trimIndent()
        val tokens = tokenizeAnsible(src)
        assertTrue(tokens.containsExact(src, "# Install nginx", TokenKind.COMMENT))
        assertTrue(tokens.containsExact(src, "name", TokenKind.KEYWORD))
        assertTrue(tokens.containsExact(src, "hosts", TokenKind.KEYWORD))
        assertTrue(tokens.containsExact(src, "become", TokenKind.KEYWORD))
        assertTrue(tokens.containsExact(src, "tasks", TokenKind.KEYWORD))
        assertTrue(tokens.containsExact(src, "apt", TokenKind.KEYWORD))
        assertTrue(tokens.containsExact(src, "template", TokenKind.KEYWORD))
        assertTrue(tokens.containsExact(src, "when", TokenKind.KEYWORD))
        assertTrue(tokens.containsExact(src, "true", TokenKind.LITERAL))
        assertTrue(tokens.containsExact(src, "8080", TokenKind.NUMBER))
        assertTrue(tokens.containsExact(src, "\"Debian\"", TokenKind.STRING))
    }

    @Test
    fun `ansible lexer highlights jinja blocks`() {
        val src = """
            - debug:
                msg: hello {{ user.name }} on {% if prod %}prod{% endif %}
        """.trimIndent()
        val tokens = tokenizeAnsible(src)
        assertTrue(tokens.any { it.kind == TokenKind.EMPHASIS && src.substring(it.start, it.endExclusive).startsWith("{{") })
        assertTrue(tokens.any { it.kind == TokenKind.EMPHASIS && src.substring(it.start, it.endExclusive).startsWith("{%") })
    }

    @Test
    fun `ansible lexer leaves user-defined keys unhighlighted`() {
        val src = """
            my_custom_var: hello
            another_one: world
        """.trimIndent()
        val tokens = tokenizeAnsible(src)
        // Neither key is a known Ansible directive, so neither becomes a KEYWORD.
        assertTrue(tokens.none { it.kind == TokenKind.KEYWORD && src.substring(it.start, it.endExclusive) == "my_custom_var" })
        assertTrue(tokens.none { it.kind == TokenKind.KEYWORD && src.substring(it.start, it.endExclusive) == "another_one" })
    }

    @Test
    fun `ansible keyword inside a string is not tokenized`() {
        val src = """msg: "tasks are great""""
        val tokens = tokenizeAnsible(src)
        // The string subsumes the "tasks" inside it.
        val tasksAsKeyword = tokens.find { it.kind == TokenKind.KEYWORD && src.substring(it.start, it.endExclusive) == "tasks" }
        assertNull(tasksAsKeyword)
    }

    @Test
    fun `go extension picks go tokenizer`() {
        assertNotNull(tokenizerForExtension("go"))
        assertNotNull(tokenizerForExtension("GO"))
    }

    @Test
    fun `go lexer highlights keywords types strings numbers and comments`() {
        val src = """
            // pkg
            package main

            import "fmt"

            func add(a int, b int) int {
                s := "sum"
                return a + b // 0x2A
            }
        """.trimIndent()
        val tokens = tokenizeGo(src)
        assertTrue(tokens.containsExact(src, "// pkg", TokenKind.COMMENT))
        assertTrue(tokens.containsExact(src, "package", TokenKind.KEYWORD))
        assertTrue(tokens.containsExact(src, "import", TokenKind.KEYWORD))
        assertTrue(tokens.containsExact(src, "func", TokenKind.KEYWORD))
        assertTrue(tokens.containsExact(src, "return", TokenKind.KEYWORD))
        assertTrue(tokens.containsExact(src, "int", TokenKind.KEYWORD), "predeclared type int is a keyword")
        assertTrue(tokens.containsExact(src, "\"fmt\"", TokenKind.STRING))
        assertTrue(tokens.containsExact(src, "\"sum\"", TokenKind.STRING))
        // The hex constant appears only inside the trailing comment, so it must NOT be a number.
        assertNull(tokens.find { it.kind == TokenKind.NUMBER && src.substring(it.start, it.endExclusive) == "0x2A" })
    }

    @Test
    fun `go lexer highlights raw strings runes literals and numeric forms`() {
        val src = "var r = `raw \\n stays` ; c := 'x' ; n := 0xFF + 0b1010 + 3.14 + 1_000 ; ok := nil"
        val tokens = tokenizeGo(src)
        assertTrue(tokens.containsExact(src, "`raw \\n stays`", TokenKind.STRING), "backtick raw string is one STRING")
        assertTrue(tokens.containsExact(src, "'x'", TokenKind.STRING), "rune literal is a STRING")
        assertTrue(tokens.containsExact(src, "0xFF", TokenKind.NUMBER))
        assertTrue(tokens.containsExact(src, "0b1010", TokenKind.NUMBER))
        assertTrue(tokens.containsExact(src, "3.14", TokenKind.NUMBER))
        assertTrue(tokens.containsExact(src, "1_000", TokenKind.NUMBER))
        assertTrue(tokens.containsExact(src, "nil", TokenKind.LITERAL))
        assertTrue(tokens.containsExact(src, "var", TokenKind.KEYWORD))
    }

    @Test
    fun `go keyword inside string is not tokenized`() {
        val src = "s := \"func is a keyword\""
        val tokens = tokenizeGo(src)
        assertNull(
            tokens.find { it.kind == TokenKind.KEYWORD && src.substring(it.start, it.endExclusive) == "func" },
            "string-internal 'func' should not be highlighted as keyword",
        )
        assertTrue(tokens.containsExact(src, "\"func is a keyword\"", TokenKind.STRING))
    }

    @Test
    fun `every token range is within text bounds and non-empty`() {
        val src = """
            // top
            fun main() {
                val xs = listOf(1, 2, 3)
                println("hello")
            }
        """.trimIndent()
        val tokens = tokenizeKotlin(src)
        for (t in tokens) {
            assertTrue(t.start in 0..src.length, "start ${t.start} out of bounds")
            assertTrue(t.endExclusive in 0..src.length, "end ${t.endExclusive} out of bounds")
            assertTrue(t.start < t.endExclusive, "empty/inverted range $t")
        }
    }

    @Test
    fun `tokens are non-overlapping after lexing`() {
        val src = """
            /* block comment with val and 42 */
            val n = 10
        """.trimIndent()
        val tokens = tokenizeKotlin(src).sortedBy { it.start }
        for (i in 1 until tokens.size) {
            assertTrue(
                tokens[i].start >= tokens[i - 1].endExclusive,
                "tokens overlap: ${tokens[i - 1]} and ${tokens[i]}",
            )
        }
    }
}
