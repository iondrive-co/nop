package iondrive.nop.index

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * What a find-in-files reads: what git lists, less Git LFS. These run the real git, because the
 * point of [SearchScope] is that git's own rules decide, and a stand-in for git would only test
 * the stand-in.
 */
class SearchScopeTest {

    private fun git(dir: Path, vararg args: String) {
        val p = ProcessBuilder(listOf("git", "-C", dir.toString()) + args)
            .redirectErrorStream(true).start()
        p.inputStream.readAllBytes()
        check(p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0) { "git ${args.toList()} failed" }
    }

    private fun write(root: Path, rel: String, text: String = "needle\n") {
        val f = root.resolve(rel)
        Files.createDirectories(f.parent)
        Files.writeString(f, text)
    }

    @Test
    fun `ignored files and Git LFS files are left out, tracked and untracked ones kept`(@TempDir tmp: Path) {
        git(tmp, "init", "-q")
        write(tmp, ".gitignore", "*.log\n.venv/\n")
        // Left untracked on purpose: adding a file under filter=lfs would run the LFS clean filter.
        write(tmp, ".gitattributes", "*.csv filter=lfs diff=lfs merge=lfs -text\n")
        write(tmp, "src/tracked.kt")
        git(tmp, "add", ".gitignore", "src/tracked.kt")
        write(tmp, "src/untracked.kt")
        write(tmp, "run.log")
        write(tmp, ".venv/lib/site.py")
        write(tmp, "data/prices.csv")

        val files = FileIndex.build(tmp).files
        val scope = SearchScope.narrow(tmp, files)

        assertEquals(
            listOf(".gitattributes", ".gitignore", "src/tracked.kt", "src/untracked.kt"),
            scope.sorted(),
        )
    }

    @Test
    fun `outside a repository every indexed file is searched`(@TempDir tmp: Path) {
        write(tmp, "a.txt")
        write(tmp, "b.log")
        assertNull(SearchScope.gitListed(tmp))
        val files = FileIndex.build(tmp).files
        assertEquals(files, SearchScope.narrow(tmp, files))
    }

    /**
     * git names a submodule, and an untracked repository nested inside, by its directory alone. The
     * files the index holds under one are kept, not dropped for being absent from git's list.
     */
    @Test
    fun `files under a directory git lists as one entry are kept`() {
        val listed = setOf("README.md", "vendor/lib", "scratch/")
        assertTrue(SearchScope.keeps(listed, "README.md"))
        assertTrue(SearchScope.keeps(listed, "vendor/lib/src/a.c"))
        assertTrue(SearchScope.keeps(listed, "scratch/notes/todo.md"))
        assertFalse(SearchScope.keeps(listed, "vendor/other.c"))
        assertFalse(SearchScope.keeps(listed, "build.log"))
    }
}
