package iondrive.nop.index

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.io.File

class JumpResolverTest {

    private val root = File("/proj")
    private val emptySymbols = SymbolIndex()
    private val files = FileIndex(
        listOf("src/demo/App.kt", "src/demo/Theme.kt", "build.gradle.kts", ".gitignore"),
    )

    private fun fileJump(text: String, offset: Int): JumpTarget? =
        JumpResolver.resolve(emptySymbols, root, null, text, offset, files)

    @Test
    fun `jumps to an exact relative path under the cursor`() {
        val t = fileJump("open src/demo/Theme.kt please", 12)
        assertEquals(File(root, "src/demo/Theme.kt"), t?.file)
        assertEquals(1, t?.line)
    }

    @Test
    fun `jumps on a basename match`() {
        assertEquals(File(root, "src/demo/App.kt"), fileJump("import App.kt", 8)?.file)
    }

    @Test
    fun `jumps on a path suffix on a segment boundary`() {
        assertEquals(File(root, "src/demo/Theme.kt"), fileJump("see demo/Theme.kt", 8)?.file)
    }

    @Test
    fun `jumps on a dotted FQN mapped to a path`() {
        assertEquals(File(root, "src/demo/Theme.kt"), fileJump("ref demo.Theme here", 6)?.file)
    }

    @Test
    fun `resolves a leading-dot dotfile`() {
        assertEquals(File(root, ".gitignore"), fileJump("edit .gitignore", 8)?.file)
    }

    @Test
    fun `a bare identifier never resolves to a file`() {
        // No '.' or '/', so an ordinary variable that shares a filename stem doesn't false-jump.
        assertNull(fileJump("the message value", 6))
    }

    @Test
    fun `a path-like token with no matching file returns null`() {
        assertNull(fileJump("nope/missing.kt", 5))
    }

    @Test
    fun `symbol resolution still wins and is unaffected by the file fallback`() {
        val symbols = SymbolIndex(listOf(IndexEntry("greet", "src/demo/App.kt", 7, SymbolKind.KOTLIN_SYMBOL)))
        val t = JumpResolver.resolve(symbols, root, null, "call greet()", 6, files)
        assertEquals(File(root, "src/demo/App.kt"), t?.file)
        assertEquals(7, t?.line)
    }

    @Test
    fun `wordAt returns the word straddling the offset`() {
        val text = "hello world"
        assertEquals("hello", JumpResolver.wordAt(text, 0))
        assertEquals("hello", JumpResolver.wordAt(text, 3))
        // offset 5 lands on the trailing boundary of "hello" — still returns the preceding word.
        assertEquals("hello", JumpResolver.wordAt(text, 5))
        assertEquals("world", JumpResolver.wordAt(text, 7))
    }

    @Test
    fun `wordRangeAt returns inclusive bounds matching wordAt`() {
        val text = "foo-bar baz"
        val range = JumpResolver.wordRangeAt(text, 2)!!
        assertEquals(0, range.first)
        assertEquals(6, range.last)
        assertEquals("foo-bar", text.substring(range.first, range.last + 1))
    }

    @Test
    fun `wordRangeAt is null when both sides are non-word characters`() {
        // Two-space gap; offset 4 is whitespace with whitespace on either side.
        assertNull(JumpResolver.wordRangeAt("abc  def", 4))
    }

    @Test
    fun `wordRangeAt tolerates out-of-bounds offsets`() {
        assertNull(JumpResolver.wordRangeAt("abc", -1))
        assertNull(JumpResolver.wordRangeAt("abc", 99))
    }

    @Test
    fun `wordRangeAt includes underscores and hyphens`() {
        val text = "my_role-name = 1"
        val range = JumpResolver.wordRangeAt(text, 4)!!
        assertEquals("my_role-name", text.substring(range.first, range.last + 1))
    }
}
