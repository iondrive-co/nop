package iondrive.nop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * The command line, which is read before anything can reach a running nop. Each of these was once a
 * bare launch — and a bare launch from a rebuilt binary quits the nop that is running.
 */
class LaunchArgsTest {

    @Test
    fun `help is help wherever it is, and touches nothing`() {
        assertEquals(LaunchArgs.Help, LaunchArgs.parse(arrayOf("--help")))
        assertEquals(LaunchArgs.Help, LaunchArgs.parse(arrayOf("/tmp", "-h")))
    }

    @Test
    fun `an option nop does not have is an error, not a launch`() {
        val parsed = LaunchArgs.parse(arrayOf("--version"))
        assertTrue(parsed is LaunchArgs.Invalid && "--version" in parsed.why, parsed.toString())
    }

    @Test
    fun `an empty argument from an unset variable is an error`() {
        assertTrue(LaunchArgs.parse(arrayOf("")) is LaunchArgs.Invalid)
    }

    @Test
    fun `a path that does not exist is an error`(@TempDir tmp: Path) {
        assertTrue(LaunchArgs.parse(arrayOf(tmp.resolve("gone").toString())) is LaunchArgs.Invalid)
    }

    @Test
    fun `directories open, and a file dropped on the launcher is ignored as before`(@TempDir tmp: Path) {
        val dir = Files.createDirectories(tmp.resolve("proj"))
        val file = Files.writeString(tmp.resolve("notes.txt"), "x")
        assertEquals(LaunchArgs.Open(listOf(dir)), LaunchArgs.parse(arrayOf(dir.toString(), file.toString())))
        assertEquals(LaunchArgs.Open(emptyList()), LaunchArgs.parse(emptyArray()))
    }

    @Test
    fun `after a double dash a directory may start with a dash`(@TempDir tmp: Path) {
        val dir = Files.createDirectories(tmp.resolve("-odd"))
        assertEquals(LaunchArgs.Open(listOf(dir)), LaunchArgs.parse(arrayOf("--", dir.toString())))
    }
}
