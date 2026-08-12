package iondrive.nop.spell

import iondrive.nop.Settings
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class DictionaryTest {
    private val originalRoot: Path = Settings.configRoot

    @AfterEach
    fun restoreRoot() {
        Settings.configRoot = originalRoot
        // The combined set is cached across lookups, so a test that pointed the config root at a
        // temp directory has to drop it or the next test inherits its words.
        Dictionary.invalidate()
    }

    @Test
    fun `knows ordinary english in either spelling`() {
        assertTrue(Dictionary.knows("colour"))
        assertTrue(Dictionary.knows("color"))
        assertTrue(Dictionary.knows("behaviour"))
        assertTrue(Dictionary.knows("organize"))
        assertTrue(Dictionary.knows("organise"))
    }

    @Test
    fun `knows programming jargon from the bundled tech list`() {
        assertTrue(Dictionary.knows("kotlin"))
        assertTrue(Dictionary.knows("gradle"))
        assertTrue(Dictionary.knows("namespace"))
        assertTrue(Dictionary.knows("stdout"))
        assertTrue(Dictionary.knows("coroutine"))
    }

    @Test
    fun `does not know misspellings`() {
        assertFalse(Dictionary.knows("teh"))
        assertFalse(Dictionary.knows("recieve"))
        assertFalse(Dictionary.knows("seperate"))
    }

    @Test
    fun `case is ignored`() {
        assertTrue(Dictionary.knows("Kotlin"))
        assertTrue(Dictionary.knows("COLOUR"))
    }

    @Test
    fun `plurals and possessives of known words are accepted`() {
        assertTrue(Dictionary.knows("repos"), "plural of a jargon word")
        assertTrue(Dictionary.knows("gradle's"), "possessive of a jargon word")
        assertTrue(Dictionary.knows("tokenizers"))
    }

    @Test
    fun `a word added by the user is known and persisted`(@TempDir tmp: Path) {
        Settings.configRoot = tmp
        Dictionary.invalidate()
        assertFalse(Dictionary.knows("adnuntius"))

        Dictionary.add("Adnuntius")

        assertTrue(Dictionary.knows("adnuntius"), "added word should be known immediately")
        assertTrue(Dictionary.knows("Adnuntius"))
        assertEquals(listOf("adnuntius"), Dictionary.userWords())
        assertEquals("adnuntius\n", Files.readString(tmp.resolve("nop/dictionary")))

        // A fresh load of the same config root still has it.
        Dictionary.invalidate()
        assertTrue(Dictionary.knows("adnuntius"))
    }

    @Test
    fun `adding a word twice writes it once`(@TempDir tmp: Path) {
        Settings.configRoot = tmp
        Dictionary.invalidate()

        Dictionary.add("zorblat")
        Dictionary.add("zorblat")
        Dictionary.add("kotlin") // already known from the bundled list

        assertEquals(listOf("zorblat"), Dictionary.userWords())
    }

    @Test
    fun `blank additions are ignored`(@TempDir tmp: Path) {
        Settings.configRoot = tmp
        Dictionary.invalidate()

        Dictionary.add("   ")

        assertTrue(Dictionary.userWords().isEmpty())
    }

    @Test
    fun `a missing user dictionary is not an error`(@TempDir tmp: Path) {
        Settings.configRoot = tmp
        Dictionary.invalidate()

        assertTrue(Dictionary.userWords().isEmpty())
        assertTrue(Dictionary.knows("editor"))
    }
}
