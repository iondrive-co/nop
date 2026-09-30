package iondrive.nop.git

import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.storage.file.FileBasedConfig
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.util.FS
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.readText
import kotlin.io.path.writeText

class CommitIdentityTest {
    @Test
    fun `the repository's own identity comes first, then the global, included and history ones`(@TempDir tmp: Path) {
        val work = tmp / "work"
        runShell(work, "git init -q && git config user.name Dev && git config user.email dev@example.test")
        commit(work, "one", author = null)
        commit(work, "two", author = "Visitor <visitor@example.test>")
        commit(work, "three", author = null)

        // The condition is never true, and the file is offered anyway: nop cannot evaluate it.
        (tmp / "employer.inc").writeText("[user]\n\temail = dev@employer.test\n")
        val user = userConfig(
            tmp,
            "[user]\n\tname = Dev Global\n\temail = dev@global.test\n" +
                "[includeIf \"gitdir:/nowhere/\"]\n\tpath = employer.inc\n",
        )

        val choices = withRepo(work) { identityChoices(it, user) }

        assertEquals(
            listOf(
                IdentityChoice(CommitIdentity("Dev", "dev@example.test"), IdentityChoice.REPO),
                IdentityChoice(CommitIdentity("Dev Global", "dev@global.test"), IdentityChoice.GLOBAL),
                // The included file sets only an email, so the name is the including config's.
                IdentityChoice(CommitIdentity("Dev Global", "dev@employer.test"), "employer.inc"),
                IdentityChoice(CommitIdentity("Visitor", "visitor@example.test"), IdentityChoice.HISTORY),
            ),
            choices,
        )
    }

    @Test
    fun `history offers only the most recent authors`(@TempDir tmp: Path) {
        val work = tmp / "work"
        runShell(work, "git init -q && git config user.name Dev && git config user.email dev@example.test")
        repeat(MAX_HISTORY_IDENTITIES + 3) { i -> commit(work, "c$i", author = "Author $i <a$i@example.test>") }

        val history = withRepo(work) { identityChoices(it, userConfig(tmp, "")) }
            .filter { it.source == IdentityChoice.HISTORY }

        assertEquals(MAX_HISTORY_IDENTITIES, history.size)
        assertEquals("Author ${MAX_HISTORY_IDENTITIES + 2}", history.first().identity.name, "newest first")
    }

    @Test
    fun `saving an identity writes it into the repository's own config`(@TempDir tmp: Path) {
        val work = tmp / "work"
        runShell(work, "git init -q")
        commit(work, "one", author = "Dev <dev@example.test>")

        GitRepo.discover(work)!!.use { it.saveIdentity(CommitIdentity("Saved Name", "saved@example.test")) }

        assertEquals("Saved Name", gitOutput(work, "git config --local user.name"))
        assertEquals("saved@example.test", gitOutput(work, "git config --local user.email"))
        assertEquals(
            IdentityChoice(CommitIdentity("Saved Name", "saved@example.test"), IdentityChoice.REPO),
            withRepo(work) { identityChoices(it, userConfig(tmp, "")) }.first(),
        )
        assertEquals(
            "Saved Name <saved@example.test>",
            gitOutput(work, "git var GIT_COMMITTER_IDENT").substringBefore('>') + ">",
            "command-line git commits as the saved identity too",
        )
    }

    @Test
    fun `saving keeps the rest of the repository's config`(@TempDir tmp: Path) {
        val work = tmp / "work"
        runShell(work, "git init -q && git config user.name Dev && git config user.email dev@example.test")
        runShell(work, "git config core.autocrlf input && git remote add origin https://example.test/repo.git")

        GitRepo.discover(work)!!.use { it.saveIdentity(CommitIdentity("Other", "other@example.test")) }

        val config = (work / ".git" / "config").readText()
        assertEquals("input", gitOutput(work, "git config --local core.autocrlf"), config)
        assertEquals("https://example.test/repo.git", gitOutput(work, "git config --local remote.origin.url"), config)
        assertEquals("Other", gitOutput(work, "git config --local --get-all user.name"), "one user.name, replaced")
    }

    @Test
    fun `stageAndCommit commits as the identity it is given`(@TempDir tmp: Path) {
        val work = tmp / "work"
        runShell(work, "git init -q && git config user.name Dev && git config user.email dev@example.test")
        commit(work, "one", author = null)
        (work / "a.txt").writeText("changed\n")

        GitRepo.discover(work)!!.use { repo ->
            repo.stageAndCommit("as someone", repo.loadStatus().changes, identity = CommitIdentity("Picked", "picked@example.test"))
        }

        assertEquals(
            "Picked <picked@example.test>|Picked <picked@example.test>",
            gitOutput(work, "git log -1 --format='%an <%ae>|%cn <%ce>'"),
        )
        assertEquals("Dev", gitOutput(work, "git config --local user.name"), "a one-off pick leaves the config alone")
    }

    @Test
    fun `stageAndCommit without an identity commits as the repository's config says`(@TempDir tmp: Path) {
        val work = tmp / "work"
        runShell(work, "git init -q && git config user.name Dev && git config user.email dev@example.test")
        commit(work, "one", author = null)
        (work / "a.txt").writeText("changed\n")

        GitRepo.discover(work)!!.use { repo -> repo.stageAndCommit("as configured", repo.loadStatus().changes) }

        assertEquals("Dev <dev@example.test>", gitOutput(work, "git log -1 --format='%an <%ae>'"))
    }

    @Test
    fun `included paths resolve the way git resolves them`(@TempDir tmp: Path) {
        val home = (tmp / "home").also { it.createDirectories() }.toFile()
        val configDir = (tmp / "etc").also { it.createDirectories() }.toFile()
        File(home, ".gitconfig-work").writeText("")
        File(configDir, "relative.inc").writeText("")

        assertEquals(File(home, ".gitconfig-work"), includedFile("~/.gitconfig-work", configDir, home))
        assertEquals(File(configDir, "relative.inc"), includedFile("relative.inc", configDir, home))
        assertEquals(File(home, ".gitconfig-work"), includedFile(File(home, ".gitconfig-work").path, configDir, home))
        assertNull(includedFile("~/missing", configDir, home), "a file that is not there offers nothing")
    }

    @Test
    fun `an included path under the home directory is named from it`() {
        val home = File("/home/dev")
        assertEquals("~/.gitconfig-work", shortPath("/home/dev/.gitconfig-work", home))
        assertEquals("~/.gitconfig-work", shortPath("~/.gitconfig-work", home))
        assertEquals("/home/developer/.gitconfig", shortPath("/home/developer/.gitconfig", home))
        assertEquals("relative.inc", shortPath("relative.inc", home))
    }

    /** A global config for the test to hand [identityChoices], in place of the real one. */
    private fun userConfig(dir: Path, text: String): FileBasedConfig {
        val file = (dir / "user.gitconfig").also { it.writeText(text) }.toFile()
        return FileBasedConfig(null, file, FS.DETECTED).apply { load() }
    }

    private fun <T> withRepo(work: Path, block: (Repository) -> T): T =
        FileRepositoryBuilder().setGitDir((work / ".git").toFile()).build().use(block)

    /** Commits a change to a.txt, as [author] ("Name <email>") or as the repository's config says. */
    private fun commit(work: Path, content: String, author: String?) {
        (work / "a.txt").writeText("$content\n")
        val env = author?.let {
            val name = it.substringBefore(" <")
            val email = it.substringAfter("<").removeSuffix(">")
            "GIT_AUTHOR_NAME='$name' GIT_AUTHOR_EMAIL='$email' GIT_COMMITTER_NAME='$name' GIT_COMMITTER_EMAIL='$email' "
        } ?: ""
        runShell(work, "git add -A && ${env}git commit -q -m '$content'")
    }

    private fun gitOutput(cwd: Path, cmd: String): String {
        val proc = ProcessBuilder("sh", "-c", cmd).directory(cwd.toFile()).redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText().trim()
        check(proc.waitFor() == 0) { "Command failed: $cmd\n$out" }
        return out
    }

    private fun runShell(cwd: Path, cmd: String) {
        cwd.createDirectories()
        val proc = ProcessBuilder("sh", "-c", cmd).directory(cwd.toFile()).redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText()
        check(proc.waitFor() == 0) { "Command failed: $cmd\n$out" }
    }
}
