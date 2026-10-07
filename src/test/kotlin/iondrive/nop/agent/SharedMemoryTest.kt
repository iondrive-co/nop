package iondrive.nop.agent

import iondrive.nop.agent.SharedMemory.Category
import iondrive.nop.agent.SharedMemory.Scope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/**
 * The memory every agent nop runs shares: a file per project and three for every project. What
 * matters is that each run is told the right files, that nop never writes over what is in them, that
 * an entry only goes in by saying who it is for, and that one claimed for every project is turned
 * away when it plainly belongs to one.
 */
class SharedMemoryTest {

    private val home = Path.of("/home/someone")
    private val projects = listOf(home.resolve("shop"), home.resolve("ledger"), home.resolve("docker"))
    private fun problems(entry: String, onPath: (String) -> Boolean = { false }) =
        SharedMemory.globalProblems(entry, projects, home, onPath)

    @Test
    fun `the instructions name the project's file and the three shared ones`(@TempDir tmp: Path) {
        val project = tmp.resolve("shop")

        val text = SharedMemory.instructions(project, tmp)

        assertTrue(SharedMemory.projectFile(project, tmp).toString() in text, text)
        Category.entries.forEach { assertTrue(SharedMemory.file(it, tmp).toString() in text, it.title) }
        assertTrue("Read these before you start" in text)
        assertTrue("nop-msg remember project" in text && "nop-msg remember all" in text)
    }

    @Test
    fun `agy's instructions name the project's file by the variable its run carries`(@TempDir tmp: Path) {
        val text = SharedMemory.instructions(null, tmp)
        assertTrue("\$${SharedMemory.PROJECT_ENV}" in text, text)
    }

    @Test
    fun `two checkouts with the same name keep separate memories`(@TempDir tmp: Path) {
        val a = SharedMemory.projectFile(tmp.resolve("a/shop"), tmp)
        val b = SharedMemory.projectFile(tmp.resolve("b/shop"), tmp)
        assertNotEquals(a, b)
        assertTrue(a.fileName.toString().startsWith("shop-"))
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `the first run creates every file read-only with a heading`(@TempDir tmp: Path) {
        val project = tmp.resolve("shop")

        SharedMemory.ensure(project, tmp)

        assertTrue(Files.readString(SharedMemory.file(Category.User, tmp)).startsWith("# User Information\n"))
        assertTrue(Files.readString(SharedMemory.file(Category.Software, tmp)).startsWith("# Software and Agent Information\n"))
        val own = SharedMemory.projectFile(project, tmp)
        assertTrue("Project: $project" in Files.readString(own))
        assertEquals("r--------", PosixFilePermissions.toString(Files.getPosixFilePermissions(own)))
    }

    @Test
    fun `a memory that already exists is never rewritten`(@TempDir tmp: Path) {
        val file = SharedMemory.file(Category.Hardware, tmp)
        Files.writeString(file, "- ptah: never pkill xfwm4\n")

        SharedMemory.ensure(null, tmp)

        assertEquals("- ptah: never pkill xfwm4\n", Files.readString(file))
    }

    @Test
    fun `an entry goes in the file its scope names, once`(@TempDir tmp: Path) {
        val project = tmp.resolve("shop")

        assertFalse(SharedMemory.remember(project, Scope.Project, "Run the fixtures with -Dslow", emptyList(), tmp).isError)
        assertFalse(SharedMemory.remember(project, Scope.All(Category.User), "- Wants a script, not a\n command list", emptyList(), tmp).isError)
        SharedMemory.remember(project, Scope.All(Category.User), "Wants a script, not a command list", emptyList(), tmp)

        assertTrue(Files.readString(SharedMemory.projectFile(project, tmp)).endsWith("\n- Run the fixtures with -Dslow\n"))
        val user = Files.readString(SharedMemory.file(Category.User, tmp))
        assertEquals(1, user.lines().count { it == "- Wants a script, not a command list" }, user)
    }

    @Test
    fun `forget takes out exactly the line it is given`(@TempDir tmp: Path) {
        val project = tmp.resolve("shop")
        SharedMemory.remember(project, Scope.Project, "keep me", emptyList(), tmp)
        SharedMemory.remember(project, Scope.Project, "drop me", emptyList(), tmp)

        assertFalse(SharedMemory.forget(project, Scope.Project, "drop me", tmp).isError)
        assertTrue(SharedMemory.forget(project, Scope.Project, "not there", tmp).isError)

        val text = Files.readString(SharedMemory.projectFile(project, tmp))
        assertTrue("- keep me" in text && "drop me" !in text, text)
    }

    @Test
    fun `an entry for every project that is about one is refused, and nothing is written`(@TempDir tmp: Path) {
        val project = tmp.resolve("shop")
        val outcome = SharedMemory.remember(project, Scope.All(Category.Software), "In $project, run gradle with --offline", emptyList(), tmp)

        assertTrue(outcome.isError, outcome.text)
        assertTrue("remember project" in outcome.text, outcome.text)
        val software = SharedMemory.file(Category.Software, tmp)
        assertFalse(Files.exists(software) && "gradle" in Files.readString(software))
    }

    @Test
    fun `a path into a project is caught however it is written`() {
        assertTrue(problems("see /home/someone/shop/build.gradle").isNotEmpty())
        assertTrue(problems("see `~/ledger/bin/py`").isNotEmpty())
        assertTrue(problems("see \$HOME/shop").isNotEmpty())
        assertEquals(emptyList<String>(), problems("logs are in ~/.config/nop/nop.log and /mnt/share"))
    }

    @Test
    fun `a project's name is caught unless it is also a program`() {
        assertTrue(problems("In ledger, the venv has pytest").isNotEmpty())
        assertEquals(emptyList<String>(), problems("Ledgers are append-only"))
        assertEquals(emptyList<String>(), problems("the shopping list"))
        assertEquals(emptyList<String>(), problems("docker needs sudo here", onPath = { it == "docker" }))
        assertTrue(problems("docker needs sudo here").isNotEmpty())
    }

    @Test
    fun `commits and words that only mean something in one session are caught`() {
        assertTrue(problems("fixed in c0d0ea8").isNotEmpty())
        assertTrue(problems("the user wants this today").isNotEmpty())
        assertTrue(problems("use the supplied screenshot as reference").isNotEmpty())
        assertEquals(emptyList<String>(), problems("Selection paints as #264f78; USB bus 0a:00.3 never enumerates"))
    }

    @Test
    fun `every entry is short, one line, and holds no credential`() {
        assertTrue(SharedMemory.commonProblems("x".repeat(501)).isNotEmpty())
        assertTrue(SharedMemory.commonProblems("").isNotEmpty())
        assertTrue(SharedMemory.commonProblems("the key is sk-abcdefghijklmnopqrstu").isNotEmpty())
        assertTrue(SharedMemory.commonProblems("password=hunter22").isNotEmpty())
        assertEquals(emptyList<String>(), SharedMemory.commonProblems("Timezone is Australia/Melbourne"))
    }

    @Test
    fun `the scope is project, or all and a category`() {
        assertEquals(Scope.Project to 1, SharedMemory.parseScope(listOf("project", "x")))
        assertEquals(Scope.All(Category.Hardware) to 2, SharedMemory.parseScope(listOf("all", "hardware")))
        assertEquals(null, SharedMemory.parseScope(listOf("all")))
        assertEquals(null, SharedMemory.parseScope(listOf("all", "misc")))
        assertEquals(null, SharedMemory.parseScope(listOf("everywhere")))
    }

    @Test
    fun `agy gets the instructions as an always-on rule in the account's own home`(@TempDir tmp: Path) {
        val home = tmp.resolve("antigravity-homes/google-main")
        val dir = tmp.resolve("memory")

        SharedMemory.installRule(home, dir)

        val rule = Files.readString(home.resolve(".gemini/config/rules/nop-shared-memory.md"))
        // Rules under rules/ are only loaded unconditionally with this trigger; without it the model
        // decides whether to read them, which for "read this first" is the same as not having it.
        assertTrue(rule.startsWith("---\ntrigger: always_on\n---\n"), rule)
        assertTrue(SharedMemory.instructions(null, dir) in rule)
    }

    @Test
    fun `installing the rule again replaces it rather than adding a second`(@TempDir tmp: Path) {
        val home = tmp.resolve("home")
        SharedMemory.installRule(home, tmp.resolve("old"))

        SharedMemory.installRule(home, tmp.resolve("new"))

        val rules = home.resolve(".gemini/config/rules")
        val names = Files.list(rules).use { files -> files.map { it.fileName.toString() }.toList() }
        assertEquals(listOf("nop-shared-memory.md"), names)
        assertTrue(tmp.resolve("new").toString() in Files.readString(rules.resolve(names.single())))
    }
}
