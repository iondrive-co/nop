package iondrive.nop.lang

import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JavaAnalysisTest {
    private fun write(root: Path, path: String, text: String) = root.resolve(path).toFile().apply {
        parentFile.mkdirs()
        writeText(text)
    }

    @Test fun `missing import is diagnosed and adding it clears the error`(@TempDir root: Path) {
        val file = write(root, "Example.java", "class Example { List<String> values; }")
        val broken = JavaAnalysis.analyze(file.readText(), file)
        assertNull(broken.failure)
        assertTrue(broken.problems.any { "cannot find symbol" in it.message })
        assertEquals("List", file.readText().let { it.substring(broken.problems.first().start, broken.problems.first().endExclusive) })
        val corrected = JavaAnalysis.analyze("import java.util.List; class Example { List<String> values; }", file)
        assertNull(corrected.failure)
        assertTrue(corrected.problems.isEmpty(), corrected.problems.toString())
    }

    @Test fun `compiler reports type errors unknown methods and missing returns`(@TempDir root: Path) {
        val file = write(root, "Example.java", "class Example {}")
        for ((text, message) in listOf(
            "class Example { int value = \"text\"; }" to "incompatible types",
            "class Example { void run() { missing(); } }" to "cannot find symbol",
            "class Example { int value() { } }" to "missing return statement",
        )) {
            val result = JavaAnalysis.analyze(text, file)
            assertNull(result.failure)
            assertTrue(result.problems.any { message in it.message }, result.problems.toString())
            assertTrue(result.problems.all { it.start >= 0 && it.endExclusive <= text.length })
        }
    }

    @Test fun `project sources resolve across packages and sibling modules`(@TempDir root: Path) {
        val model = write(root, "model/src/main/java/example/model/Widget.java",
            "package example.model; public class Widget { public int value() { return 1; } }")
        val file = write(root, "app/src/test/java/example/app/Example.java",
            "package example.app; import example.model.Widget; public class Example { int n = new Widget().value(); }")
        val paths = listOf(model, file).map { it.relativeTo(root.toFile()).invariantSeparatorsPath }
        val result = JavaAnalysis.analyze(file.readText(), file, root.toFile(), paths)
        assertNull(result.failure)
        assertTrue(result.problems.isEmpty(), result.problems.toString())
        assertTrue(Files.walk(root).use { paths -> paths.noneMatch { it.toString().endsWith(".class") } })
    }

    @Test fun `analysis sees unsaved changes in another open source file`(@TempDir root: Path) {
        val sibling = write(root, "src/example/Widget.java",
            "package example; public class Widget { public String value() { return \"text\"; } }")
        val file = write(root, "src/example/Example.java",
            "package example; class Example { int n = new Widget().value(); }")
        val disk = JavaAnalysis.analyze(file.readText(), file, root.toFile())
        assertTrue(disk.problems.any { "incompatible types" in it.message })
        val unsaved = "package example; public class Widget { public int value() { return 1; } }"
        val result = JavaAnalysis.analyze(file.readText(), file, root.toFile(), openBuffers = mapOf(sibling to unsaved))
        assertNull(result.failure)
        assertTrue(result.problems.isEmpty(), result.problems.toString())
        assertTrue("String value()" in sibling.readText())
    }

    @Test fun `diagnostics in a dependency are not assigned to the editor buffer`(@TempDir root: Path) {
        write(root, "src/example/Widget.java", "package example; public class Widget { int n = 1 }")
        val file = write(root, "src/example/Example.java", "package example; class Example { Widget widget; }")
        val result = JavaAnalysis.analyze(file.readText(), file, root.toFile())
        assertNull(result.failure)
        assertTrue(result.problems.none { "';' expected" in it.message }, result.problems.toString())
    }

    @Test fun `explicit classpath resolves external dependencies`(@TempDir root: Path) {
        val source = write(root, "library/example/lib/Gadget.java", "package example.lib; public class Gadget {}")
        val classes = Files.createDirectories(root.resolve("library-classes"))
        val compiler = assertNotNull(ToolProvider.getSystemJavaCompiler())
        assertEquals(0, compiler.run(null, null, null, "-d", classes.toString(), source.path))
        val jar = root.resolve("lib/library.jar")
        Files.createDirectories(jar.parent)
        JarOutputStream(Files.newOutputStream(jar)).use {
            it.putNextEntry(JarEntry("example/lib/Gadget.class"))
            Files.copy(classes.resolve("example/lib/Gadget.class"), it)
            it.closeEntry()
        }
        val file = write(root, "Example.java", "import example.lib.Gadget; class Example { Gadget gadget; }")
        assertTrue(JavaAnalysis.analyze(file.readText(), file).problems.isNotEmpty())
        for (entry in listOf("lib/library.jar", jar.toString(), "lib/*", "library-classes")) {
            write(root, ".nop/classpath.txt", "# Dependencies\n\n$entry\n")
            val result = JavaAnalysis.analyze(file.readText(), file)
            assertNull(result.failure)
            assertTrue(result.problems.isEmpty(), "$entry: ${result.problems}")
        }
    }

    @Test fun `invalid classpath is reported instead of claiming analysis succeeded`(@TempDir root: Path) {
        val file = write(root, "Example.java", "class Example {}")
        write(root, ".nop/classpath.txt", "missing.jar\n")
        val result = JavaAnalysis.analyze(file.readText(), file)
        assertTrue(assertNotNull(result.failure).contains("missing.jar"))
    }

    @Test fun `application dependencies are not visible to project analysis`(@TempDir root: Path) {
        val file = write(root, "Example.java", "class Example { org.eclipse.jgit.api.Git git; }")
        val result = JavaAnalysis.analyze(file.readText(), file)
        assertNull(result.failure)
        assertTrue(result.problems.isNotEmpty())
    }
}
