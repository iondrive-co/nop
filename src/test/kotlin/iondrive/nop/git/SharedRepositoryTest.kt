package iondrive.nop.git

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class SharedRepositoryTest {
    @Test
    fun `two GitRepos on one git directory share a repository, even from a subdirectory`(@TempDir tmp: Path) {
        initRepo(tmp)
        val sub = (tmp / "sub").also { it.createDirectories() }
        val a = GitRepo.discover(tmp, ceiling = tmp)!!
        val b = GitRepo.discover(sub, ceiling = tmp)!!
        try {
            assertTrue(a.sharesRepositoryWith(b), "the window and the poller must not each load the pack index")
        } finally {
            a.close()
            b.close()
        }
    }

    @Test
    fun `closing one holder leaves the other working, and the last close lets a new open start fresh`(@TempDir tmp: Path) {
        initRepo(tmp)
        val a = GitRepo.discover(tmp, ceiling = tmp)!!
        val b = GitRepo.discover(tmp, ceiling = tmp)!!
        a.close()
        a.close() // idempotent: a second close must not take b's share away
        (tmp / "pending.txt").writeText("x\n")
        assertFalse(b.loadStatus().isClean, "b still holds the repository after a has gone")
        b.close()

        val c = GitRepo.discover(tmp, ceiling = tmp)!!
        try {
            assertFalse(c.sharesRepositoryWith(b), "once every holder has closed, the repository is closed too")
            assertFalse(c.loadStatus().isClean)
        } finally {
            c.close()
        }
    }

    @Test
    fun `releasing object caches leaves the repository usable`(@TempDir tmp: Path) {
        initRepo(tmp)
        // Pack it, so there is a pack index to let go of.
        runShell(tmp, "git gc -q")
        GitRepo.discover(tmp, ceiling = tmp)!!.use { repo ->
            val head = repo.headSha()
            repo.releaseObjectCaches()
            assertEquals(head, repo.headSha())
            (tmp / "committed.txt").writeText("v2\n")
            // Status compares against HEAD's tree, which is read back out of the pack.
            assertEquals(listOf("committed.txt"), repo.loadStatus().changes.map { it.path })
        }
    }

    private fun initRepo(dir: Path) {
        runShell(dir, "git init -q && git config user.email t@x && git config user.name T")
        (dir / "committed.txt").writeText("v1\n")
        runShell(dir, "git add -A && git commit -q -m init")
    }

    private operator fun Path.div(name: String): Path = resolve(name)

    private fun runShell(cwd: Path, cmd: String) {
        cwd.createDirectories()
        val proc = ProcessBuilder("sh", "-c", cmd)
            .directory(cwd.toFile())
            .redirectErrorStream(true)
            .start()
        val out = proc.inputStream.bufferedReader().readText()
        val exit = proc.waitFor()
        check(exit == 0) { "Command failed (exit=$exit): $cmd\n$out" }
    }
}
