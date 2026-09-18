package iondrive.nop.agent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/**
 * The one memory every agent nop runs shares. What matters is that it exists before any run is told
 * to read it, that nop never writes over what the agents have put in it, and that `agy` — which has
 * no flag for instructions — is given them where it actually looks.
 */
class SharedMemoryTest {

    @Test
    fun `the instructions name the file and ask for it to be read first`(@TempDir tmp: Path) {
        val file = tmp.resolve("memory.md")

        val text = SharedMemory.instructions(file)

        assertTrue(file.toString() in text, "a run can only read a file it is told the path of")
        assertTrue("Read the file before you start" in text)
    }

    @Test
    fun `the first run creates the file with a heading that says what it is`(@TempDir tmp: Path) {
        val file = tmp.resolve("nop/agent/memory/memory.md")

        SharedMemory.ensure(file)

        assertTrue(Files.readString(file).startsWith("# Shared agent memory\n"))
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `the file is readable only by its owner`(@TempDir tmp: Path) {
        val file = tmp.resolve("memory.md")

        SharedMemory.ensure(file)

        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
    }

    @Test
    fun `a memory that already exists is never rewritten`(@TempDir tmp: Path) {
        val file = tmp.resolve("memory.md")
        Files.writeString(file, "- ptah: never pkill xfwm4\n")

        SharedMemory.ensure(file)

        assertEquals("- ptah: never pkill xfwm4\n", Files.readString(file))
    }

    @Test
    fun `agy gets the instructions as an always-on rule in the account's own home`(@TempDir tmp: Path) {
        val home = tmp.resolve("antigravity-homes/google-main")
        val file = tmp.resolve("memory.md")

        SharedMemory.installRule(home, file)

        val rule = Files.readString(home.resolve(".gemini/config/rules/nop-shared-memory.md"))
        // Rules under rules/ are only loaded unconditionally with this trigger; without it the model
        // decides whether to read them, which for "read this first" is the same as not having it.
        assertTrue(rule.startsWith("---\ntrigger: always_on\n---\n"), rule)
        assertTrue(SharedMemory.instructions(file) in rule)
    }

    @Test
    fun `installing the rule again replaces it rather than adding a second`(@TempDir tmp: Path) {
        val home = tmp.resolve("home")
        SharedMemory.installRule(home, tmp.resolve("old/memory.md"))

        SharedMemory.installRule(home, tmp.resolve("new/memory.md"))

        val rules = home.resolve(".gemini/config/rules")
        val names = Files.list(rules).use { files -> files.map { it.fileName.toString() }.toList() }
        assertEquals(listOf("nop-shared-memory.md"), names)
        assertTrue(tmp.resolve("new/memory.md").toString() in Files.readString(rules.resolve(names.single())))
    }
}
