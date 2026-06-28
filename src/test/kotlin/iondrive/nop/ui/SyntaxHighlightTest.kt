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

    // ---------- Native YAML ----------

    @Test
    fun `yaml extension picks native yaml tokenizer`() {
        assertNotNull(tokenizerForExtension("yml"))
        assertNotNull(tokenizerForExtension("yaml"))
        assertNotNull(tokenizerForExtension("YAML"))
    }

    @Test
    fun `yaml lexer highlights every key not just a fixed vocabulary`() {
        val src = """
            # config
            my_custom_key: hello
            another_one: 42
            enabled: true
            nothing: null
            quoted: "a string"
        """.trimIndent()
        val tokens = tokenizeYaml(src)
        assertTrue(tokens.containsExact(src, "# config", TokenKind.COMMENT))
        // Native YAML colours ALL keys, including user-defined ones the Ansible lexer left plain.
        assertTrue(tokens.containsExact(src, "my_custom_key", TokenKind.KEYWORD))
        assertTrue(tokens.containsExact(src, "another_one", TokenKind.KEYWORD))
        assertTrue(tokens.containsExact(src, "enabled", TokenKind.KEYWORD))
        assertTrue(tokens.containsExact(src, "42", TokenKind.NUMBER))
        assertTrue(tokens.containsExact(src, "true", TokenKind.LITERAL))
        assertTrue(tokens.containsExact(src, "null", TokenKind.LITERAL))
        assertTrue(tokens.containsExact(src, "\"a string\"", TokenKind.STRING))
    }

    @Test
    fun `yaml lexer highlights anchors aliases and tags`() {
        val src = """
            base: &anchor
              key: val
            ref: *anchor
            typed: !!str 123
        """.trimIndent()
        val tokens = tokenizeYaml(src)
        assertTrue(tokens.containsExact(src, "&anchor", TokenKind.EMPHASIS))
        assertTrue(tokens.containsExact(src, "*anchor", TokenKind.EMPHASIS))
        assertTrue(tokens.containsExact(src, "!!str", TokenKind.EMPHASIS))
    }

    @Test
    fun `yaml block scalar body is one string and is not mis-flagged`() {
        val src = """
            script: |
              line one
              line two: not a key
            next: 1
        """.trimIndent()
        val tokens = tokenizeYaml(src)
        // Block scalar lines are literal text — coloured STRING, and the free-form ":" inside must
        // never be parsed as a key or trip the indentation validator.
        assertTrue(tokens.any { it.kind == TokenKind.STRING && src.substring(it.start, it.endExclusive).contains("not a key") })
        assertTrue(tokens.none { it.kind == TokenKind.ERROR }, "valid block scalar must not produce errors")
        assertTrue(tokens.containsExact(src, "next", TokenKind.KEYWORD), "lexer recovers after the block scalar")
    }

    @Test
    fun `yaml flow collection across lines is balanced and error-free`() {
        val src = """
            items: [
              1,
              2,
              "three"
            ]
        """.trimIndent()
        val tokens = tokenizeYaml(src)
        assertTrue(tokens.none { it.kind == TokenKind.ERROR }, "balanced multi-line flow has no errors")
        assertTrue(tokens.containsExact(src, "\"three\"", TokenKind.STRING))
    }

    @Test
    fun `yaml valid document produces no error tokens`() {
        val src = """
            ---
            name: example
            version: 1.2
            list:
              - a
              - b
            nested:
              child:
                deep: true
        """.trimIndent()
        val tokens = tokenizeYaml(src)
        assertTrue(tokens.none { it.kind == TokenKind.ERROR }, "well-formed YAML must not be flagged")
    }

    @Test
    fun `yaml flags a tab used for indentation`() {
        val src = "root:\n\tchild: 1"
        val tokens = tokenizeYaml(src)
        val tab = tokens.find { it.kind == TokenKind.ERROR && src.substring(it.start, it.endExclusive) == "\t" }
        assertNotNull(tab, "a leading tab is an indentation error in YAML")
    }

    @Test
    fun `yaml flags a dedent that lines up with no open block`() {
        val src = "a:\n  b:\n    c: 1\n   d: 2"
        val tokens = tokenizeYaml(src)
        // `d` is indented 3 — between the open levels 2 and 4 — which is a real YAML indent error.
        assertTrue(tokens.any { it.kind == TokenKind.ERROR }, "mis-aligned dedent should be flagged")
    }

    @Test
    fun `yaml flags an unterminated quoted string`() {
        val src = "key: \"unterminated"
        val tokens = tokenizeYaml(src)
        assertTrue(tokens.any { it.kind == TokenKind.ERROR }, "an unclosed quote at EOF is an error")
    }

    @Test
    fun `yaml flags an unclosed flow collection`() {
        val src = "items: [1, 2, 3"
        val tokens = tokenizeYaml(src)
        val openBracket = tokens.find { it.kind == TokenKind.ERROR && src.substring(it.start, it.endExclusive) == "[" }
        assertNotNull(openBracket, "an unclosed flow collection should flag its opening bracket")
    }

    @Test
    fun `yaml keyword inside a quoted value is not tokenized as a key`() {
        val src = """msg: "key: not a real key""""
        val tokens = tokenizeYaml(src)
        // The whole quoted value is one STRING; the inner "key" must not become a KEYWORD.
        assertNull(tokens.find { it.kind == TokenKind.KEYWORD && src.substring(it.start, it.endExclusive) == "key" }?.takeIf { it.start > 0 })
        assertTrue(tokens.containsExact(src, "msg", TokenKind.KEYWORD))
        assertTrue(tokens.containsExact(src, "\"key: not a real key\"", TokenKind.STRING))
    }

    @Test
    fun `yaml mapping keys under a sequence dash do not look like a bad dedent`() {
        // `- hosts:` puts the play's keys at column 2 (after the dash); siblings like `serial`
        // align there. Before the dash-column fix this dedented "into open air" and false-flagged.
        val src = """
            - hosts:
                - adn_collect_server
              serial: 1
              strategy: mitogen_linear
              roles:
                - adn_collect_server
        """.trimIndent()
        val tokens = tokenizeYaml(src)
        assertTrue(tokens.none { it.kind == TokenKind.ERROR }, "sequence-of-mappings indentation is valid")
        assertTrue(tokens.containsExact(src, "serial", TokenKind.KEYWORD))
    }

    @Test
    fun `yaml plain scalar with jinja quotes and brackets is not an error`() {
        // Ansible values are full of Jinja containing quotes/brackets that are NOT YAML syntax.
        val src = """
            - debug: msg="{{ update_result.cmd | join(' ') }}"
            - set_fact: host="{{ groups['ssh_bastion'] | map(attribute="name") | first }}"
            failed: "{% if x|length > 0 %}{{ y.split(',') }}{% else %}[]{% endif %}"
        """.trimIndent()
        val tokens = tokenizeYaml(src)
        assertTrue(tokens.none { it.kind == TokenKind.ERROR }, "Jinja internals must not trip the lexer")
        // The bare Jinja block is still highlighted as a template unit.
        assertTrue(tokens.any { it.kind == TokenKind.EMPHASIS && src.substring(it.start, it.endExclusive).startsWith("{{") })
    }

    @Test
    fun `yaml plain scalar containing spaces stays one scalar`() {
        // A space inside a plain scalar must not restart token scanning (which used to misread a
        // later quote as a string opener).
        val src = "msg: this is a plain value with 'quotes' and : colons"
        val tokens = tokenizeYaml(src)
        assertTrue(tokens.none { it.kind == TokenKind.ERROR })
        assertTrue(tokens.none { it.kind == TokenKind.STRING }, "no real string here, just a plain scalar")
    }

    @Test
    fun `yaml multi-line quoted scalar spans lines without error`() {
        val src = "key: \"first line\n  second line\"\nnext: 1"
        val tokens = tokenizeYaml(src)
        assertTrue(tokens.none { it.kind == TokenKind.ERROR }, "a properly closed multi-line quote is valid")
        assertTrue(tokens.containsExact(src, "next", TokenKind.KEYWORD), "lexer recovers after the multi-line quote")
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
