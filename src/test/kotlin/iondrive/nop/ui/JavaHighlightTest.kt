package iondrive.nop.ui

import iondrive.nop.lang.JavaParse
import iondrive.nop.lang.JavaSymbols
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JavaHighlightTest {
    private fun tokens(source: String): List<Token> {
        val parsed = assertNotNull(JavaParse.parse(source))
        assertTrue(parsed.problems.isEmpty(), parsed.problems.toString())
        return javaMemberTokens(parsed, JavaSymbols.declarations(parsed))
    }

    private fun kindAt(source: String, tokens: List<Token>, fragment: String, name: String): TokenKind? {
        val fragmentStart = source.indexOf(fragment)
        assertTrue(fragmentStart >= 0, "Missing fixture fragment: $fragment")
        val start = fragmentStart + fragment.indexOf(name)
        return tokens.singleOrNull { it.start == start && it.endExclusive == start + name.length }?.kind
    }

    @Test fun `constructor parameters remain plain while fields have member colours`() {
        val source = """
            class Worker {
                private final Service service;
                private static final int LIMIT = 7;
                Worker(Service service) { this.service = service; }
                void work() { service.execute(LIMIT); }
            }
        """.trimIndent()
        val tokens = tokens(source)
        assertEquals(TokenKind.FIELD, kindAt(source, tokens, "Service service;", "service"))
        assertEquals(TokenKind.STATIC_FIELD, kindAt(source, tokens, "int LIMIT =", "LIMIT"))
        assertEquals(null, kindAt(source, tokens, "Worker(Service service)", "service"))
        assertEquals(TokenKind.FIELD, kindAt(source, tokens, "this.service", "service"))
        assertEquals(null, kindAt(source, tokens, "= service;", "service"))
        assertEquals(TokenKind.FIELD, kindAt(source, tokens, "service.execute", "service"))
        assertEquals(TokenKind.STATIC_FIELD, kindAt(source, tokens, "execute(LIMIT)", "LIMIT"))
    }

    @Test fun `calls and types remain plain while declarations and external fields are coloured`() {
        val source = """
            class Worker {
                Object work(Traffic traffic) {
                    Logger.info(traffic.histogram.size(), TimeUnit.HOURS);
                    return new Object();
                }
            }
        """.trimIndent()
        val tokens = tokens(source)
        assertEquals(TokenKind.FUNCTION_DECLARATION, kindAt(source, tokens, "Object work(", "work"))
        assertEquals(TokenKind.FIELD, kindAt(source, tokens, "traffic.histogram", "histogram"))
        assertEquals(TokenKind.STATIC_FIELD, kindAt(source, tokens, "TimeUnit.HOURS", "HOURS"))
        for (name in listOf("Worker", "Object", "Traffic", "Logger", "info", "size", "TimeUnit")) {
            assertTrue(tokens.none { source.substring(it.start, it.endExclusive) == name }, name)
        }
    }

    @Test fun `locals lambdas and loop variables shadow fields only inside their scopes`() {
        val source = """
            class Worker {
                int value;
                void work() {
                    consume(value);
                    { int value = 1; consume(value + 1); }
                    items.forEach(value -> consume(value + 2));
                    for (int value : items) consume(value + 3);
                    consume(value + 4);
                }
            }
        """.trimIndent()
        val tokens = tokens(source)
        assertEquals(TokenKind.FIELD, kindAt(source, tokens, "consume(value);", "value"))
        for (n in 1..3) assertEquals(null, kindAt(source, tokens, "value + $n", "value"))
        assertEquals(TokenKind.FIELD, kindAt(source, tokens, "value + 4", "value"))
    }

    @Test fun `field and method with the same name keep distinct styles`() {
        val source = """
            class Worker {
                Runnable run;
                void run() { run.run(); this.run(); }
            }
        """.trimIndent()
        val tokens = tokens(source)
        assertEquals(TokenKind.FIELD, kindAt(source, tokens, "Runnable run;", "run"))
        assertEquals(TokenKind.FUNCTION_DECLARATION, kindAt(source, tokens, "void run()", "run"))
        assertEquals(TokenKind.FIELD, kindAt(source, tokens, "run.run()", "run"))
        assertEquals(null, kindAt(source, tokens, "this.run()", "run"))
    }

    @Test fun `nested fields interfaces and static imports use their own modifiers`() {
        val source = """
            import static example.Constants.LIMIT;
            class Worker {
                static int value;
                void outer() { consume(value, LIMIT); }
                class Inner {
                    int value;
                    void inner() { consume(value); }
                }
            }
            interface Settings { int DEFAULT = 3; }
        """.trimIndent()
        val tokens = tokens(source)
        assertEquals(TokenKind.STATIC_FIELD, kindAt(source, tokens, "consume(value, LIMIT)", "value"))
        assertEquals(TokenKind.STATIC_FIELD, kindAt(source, tokens, "consume(value, LIMIT)", "LIMIT"))
        assertEquals(TokenKind.FIELD, kindAt(source, tokens, "consume(value);", "value"))
        assertEquals(TokenKind.STATIC_FIELD, kindAt(source, tokens, "int DEFAULT =", "DEFAULT"))
    }
}
