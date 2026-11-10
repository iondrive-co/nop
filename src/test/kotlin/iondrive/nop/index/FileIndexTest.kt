package iondrive.nop.index

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class FileIndexTest {

    @Test
    fun `build walks files and returns project-relative posix paths`(@TempDir tmp: Path) {
        Files.createDirectories(tmp.resolve("src/main"))
        Files.writeString(tmp.resolve("src/main/Foo.kt"), "")
        Files.writeString(tmp.resolve("README.md"), "")

        val idx = FileIndex.build(tmp)
        assertTrue("README.md" in idx.files)
        assertTrue("src/main/Foo.kt" in idx.files)
        assertEquals(2, idx.files.size)
    }

    @Test
    fun `build skips ignored directories`(@TempDir tmp: Path) {
        Files.createDirectories(tmp.resolve(".git"))
        Files.writeString(tmp.resolve(".git/HEAD"), "")
        Files.createDirectories(tmp.resolve("node_modules"))
        Files.writeString(tmp.resolve("node_modules/index.js"), "")
        Files.writeString(tmp.resolve("kept.txt"), "")

        val idx = FileIndex.build(tmp)
        assertEquals(listOf("kept.txt"), idx.files)
    }

    @Test
    fun `build includes dotfiles and files under dot-directories`(@TempDir tmp: Path) {
        Files.writeString(tmp.resolve("visible.txt"), "")
        Files.writeString(tmp.resolve(".hidden"), "")
        Files.createDirectories(tmp.resolve(".claude"))
        Files.writeString(tmp.resolve(".claude/settings.json"), "")

        val idx = FileIndex.build(tmp)
        assertTrue("visible.txt" in idx.files)
        // Dotfiles and dot-directories are searchable now (only IGNORED_DIR_NAMES are pruned).
        assertTrue(".hidden" in idx.files)
        assertTrue(".claude/settings.json" in idx.files)
    }

    @Test
    fun `build still skips ignored dot-directories like git`(@TempDir tmp: Path) {
        Files.createDirectories(tmp.resolve(".git"))
        Files.writeString(tmp.resolve(".git/config"), "")
        Files.writeString(tmp.resolve("kept.txt"), "")

        val idx = FileIndex.build(tmp)
        assertEquals(listOf("kept.txt"), idx.files)
    }

    @Test
    fun `save then load round-trips`(@TempDir tmp: Path) {
        val target = tmp.resolve("files.txt")
        val original = FileIndex(listOf("a/b.kt", "c.md"))
        FileIndex.save(target, original)
        val loaded = FileIndex.load(target)
        assertEquals(original.files, loaded.files)
    }

    @Test
    fun `save then load round-trips names outside ascii and keeps no trailing blank`(@TempDir tmp: Path) {
        val target = tmp.resolve("files.txt")
        val original = FileIndex(listOf("docs/naïve café.md", "src/日本.kt", "z.txt"))
        FileIndex.save(target, original)
        assertEquals("docs/naïve café.md\nsrc/日本.kt\nz.txt", target.toFile().readText(Charsets.UTF_8))
        assertEquals(original.files, FileIndex.load(target).files)
    }

    @Test
    fun `load returns empty when the cache file is missing`(@TempDir tmp: Path) {
        val loaded = FileIndex.load(tmp.resolve("does-not-exist.txt"))
        assertEquals(emptyList<String>(), loaded.files)
    }

    /**
     * A link out to bulk data on a network share would cost a round trip per file on every refresh,
     * and one to a directory inside the project names files listed already where they live.
     */
    @Test
    fun `build follows no link to a directory, in the project or out of it`(@TempDir tmp: Path) {
        val project = tmp.resolve("project")
        val elsewhere = tmp.resolve("share")
        Files.createDirectories(elsewhere.resolve("bulk"))
        Files.writeString(elsewhere.resolve("bulk/big.json"), "")
        Files.createDirectories(project.resolve("src"))
        Files.writeString(project.resolve("src/Main.kt"), "")
        Files.createSymbolicLink(project.resolve("data"), elsewhere.resolve("bulk"))
        Files.createSymbolicLink(project.resolve("alias"), project.resolve("src"))

        val idx = FileIndex.build(project)
        assertEquals(listOf("src/Main.kt"), idx.files)
    }

    /**
     * A stage directory linking back to the tree that holds it. Followed, the walk goes round until
     * the kernel gives up at 40 links in a path, listing everything under `var` forty times; on a
     * tree of a hundred thousand files that is four million paths, and a 2 GB heap full of them.
     */
    @Test
    fun `build does not go round a link back to a directory that holds it`(@TempDir tmp: Path) {
        Files.createDirectories(tmp.resolve("var/dev/job/stage"))
        Files.writeString(tmp.resolve("var/data.json"), "")
        Files.createSymbolicLink(tmp.resolve("var/dev/job/stage/var"), tmp.resolve("var"))
        Files.createSymbolicLink(tmp.resolve("var/dev/job/stage/relative"), Path.of("../../.."))

        assertEquals(listOf("var/data.json"), FileIndex.build(tmp).files)
    }

    @Test
    fun `build does not go round a link back to the project root`(@TempDir tmp: Path) {
        Files.createDirectories(tmp.resolve("sub"))
        Files.writeString(tmp.resolve("sub/kept.txt"), "")
        Files.createSymbolicLink(tmp.resolve("sub/top"), tmp)

        assertEquals(listOf("sub/kept.txt"), FileIndex.build(tmp).files)
    }

    /** Neither link points at a directory holding it; the loop would only show going through both. */
    @Test
    fun `build does not go round two links that lead to each other`(@TempDir tmp: Path) {
        Files.createDirectories(tmp.resolve("x"))
        Files.createDirectories(tmp.resolve("y"))
        Files.writeString(tmp.resolve("x/f.txt"), "")
        Files.writeString(tmp.resolve("y/g.txt"), "")
        Files.createSymbolicLink(tmp.resolve("x/to-y"), tmp.resolve("y"))
        Files.createSymbolicLink(tmp.resolve("y/to-x"), tmp.resolve("x"))

        assertEquals(listOf("x/f.txt", "y/g.txt"), FileIndex.build(tmp).files)
    }

    /**
     * Stage directories that link back to the tree's own directories, one stage per job: walked, each
     * stage would list the linked trees again, and a few dozen stages make millions of paths.
     */
    @Test
    fun `build lists what links name once, where it lives`(@TempDir tmp: Path) {
        Files.createDirectories(tmp.resolve("a"))
        Files.createDirectories(tmp.resolve("shared"))
        Files.writeString(tmp.resolve("shared/s.txt"), "")
        Files.createSymbolicLink(tmp.resolve("a/one"), tmp.resolve("shared"))
        Files.createSymbolicLink(tmp.resolve("a/two"), tmp.resolve("shared"))

        assertEquals(listOf("shared/s.txt"), FileIndex.build(tmp).files)
    }

    @Test
    fun `the symbol walk agrees with the file index about a link loop`(@TempDir tmp: Path) {
        Files.createDirectories(tmp.resolve("var/stage"))
        Files.writeString(tmp.resolve("var/data.json"), "")
        Files.createSymbolicLink(tmp.resolve("var/stage/var"), tmp.resolve("var"))

        val walked = mutableListOf<String>()
        Indexer.build(tmp, files = walked)

        assertEquals(listOf("var/data.json"), walked)
    }

    @Test
    fun `files are listed case-insensitively`() {
        val idx = FileIndex.of(listOf("b.txt", "a/c.txt", "B/d.txt", "A.txt"))
        assertEquals(listOf("A.txt", "a/c.txt", "b.txt", "B/d.txt"), idx.files)
    }

    /** The index rebuild takes its file list from the symbol walk, so the two must agree exactly. */
    @Test
    fun `the symbol walk lists the same files as the file index`(@TempDir tmp: Path) {
        val project = tmp.resolve("project")
        val elsewhere = tmp.resolve("share")
        Files.createDirectories(elsewhere.resolve("bulk"))
        Files.writeString(elsewhere.resolve("bulk/big.json"), "")
        Files.createDirectories(project.resolve("src/main/kotlin/app"))
        Files.writeString(project.resolve("src/main/kotlin/app/Main.kt"), "fun main() {}\n")
        Files.createDirectories(project.resolve("src/main/java/app"))
        Files.writeString(project.resolve("src/main/java/app/Tool.java"), "class Tool {}\n")
        Files.createDirectories(project.resolve("roles/web/tasks"))
        Files.writeString(project.resolve("roles/web/tasks/main.yml"), "- name: hello\n")
        Files.createDirectories(project.resolve(".claude"))
        Files.writeString(project.resolve(".claude/settings.json"), "{}")
        Files.createDirectories(project.resolve("node_modules/dep"))
        Files.writeString(project.resolve("node_modules/dep/index.js"), "")
        Files.writeString(project.resolve("README.md"), "")
        Files.createSymbolicLink(project.resolve("data"), elsewhere.resolve("bulk"))
        Files.createSymbolicLink(project.resolve("alias"), project.resolve("src/main/kotlin"))

        val walked = mutableListOf<String>()
        Indexer.build(project, files = walked)

        assertEquals(FileIndex.build(project).files, FileIndex.of(walked).files)
        assertEquals(
            listOf(
                ".claude/settings.json", "README.md", "roles/web/tasks/main.yml",
                "src/main/java/app/Tool.java", "src/main/kotlin/app/Main.kt",
            ),
            FileIndex.of(walked).files,
        )
    }
}
