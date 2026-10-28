package iondrive.nop.index

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import javax.tools.ToolProvider
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class JavaLibraryIndexTest {
    private val source = """
        package example.library;

        public class GadgetWriter<T extends Number> implements java.io.Closeable {
            static { System.setProperty("nop.test.library.initialized", "yes"); }
            private int hidden;
            public T value;
            public <R> R echo(R value) { return value; }
            public void close() {}
            public static class Inner {}
            public Runnable task() { return new Runnable() { public void run() {} }; }
        }
        class CompanionType {}
    """.trimIndent()

    private fun write(path: Path, text: String): Path {
        Files.createDirectories(path.parent)
        Files.writeString(path, text)
        return path
    }

    private fun compile(root: Path, text: String = source): Path {
        val file = write(root.resolve("src/example/library/GadgetWriter.java"), text)
        val classes = Files.createDirectories(root.resolve("classes"))
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-g", "-d", classes.toString(), file.toString()))
        return classes
    }

    private fun jar(path: Path, entries: Map<String, ByteArray>, multiRelease: Boolean = false): Path {
        Files.createDirectories(path.parent)
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            if (multiRelease) mainAttributes.putValue("Multi-Release", "true")
        }
        JarOutputStream(Files.newOutputStream(path), manifest).use { output ->
            for ((name, bytes) in entries) {
                output.putNextEntry(JarEntry(name))
                output.write(bytes)
                output.closeEntry()
            }
        }
        return path
    }

    private fun library(root: Path, path: String = "lib/toolkit-1.0.jar"): Path {
        val classes = compile(root.resolve("fixture"))
        val entries = Files.walk(classes).use { files ->
            files.filter { Files.isRegularFile(it) }.toList().associate {
                classes.relativize(it).toString().replace(File.separatorChar, '/') to Files.readAllBytes(it)
            }
        }
        return jar(root.resolve(path), entries)
    }

    private fun index(root: Path, previous: JavaLibraryIndex? = null) =
        JavaLibraryIndex.build(root.toFile(), root.resolve("cache"), previous, includeJdk = false)

    @Test fun `dependency classes are indexed without sources or class initialization`(@TempDir root: Path) {
        val library = library(root)
        write(root.resolve(".nop/classpath.txt"), library.toString())
        val index = index(root)
        assertNotNull(index.find("example.library.GadgetWriter"))
        assertNotNull(index.find("example.library.GadgetWriter.Inner"))
        assertFalse(index.classes.any { "$1" in it.binaryName })
        assertNull(System.getProperty("nop.test.library.initialized"))
        val type = assertNotNull(index.find("example.library.GadgetWriter"))
        val target = index.target(type)
        assertFalse(target.file.exists(), "Looking up a target does not extract or render it")
        val document = assertNotNull(index.materialize(target.file))
        val text = document.file.readText()
        assertFalse(document.hasSource)
        assertTrue(text.contains("class GadgetWriter<T extends java.lang.Number>"), text)
        assertTrue(text.contains("echo("), text)
        assertTrue(text.contains("close("), text)
        assertFalse(text.contains("hidden"), text)
        assertNull(System.getProperty("nop.test.library.initialized"))
        assertTrue(document.label.endsWith("GadgetWriter.class"))
        val nested = assertNotNull(index.materialize(index.target(assertNotNull(index.find("example.library.GadgetWriter.Inner"))).file))
        val parsed = assertNotNull(iondrive.nop.lang.JavaParse.parse(nested.file.readText(), nested.file.name))
        assertEquals(iondrive.nop.lang.UsageTarget.Type("Inner", "example.library.GadgetWriter.Inner", "example.library"),
            iondrive.nop.lang.JavaUsages.targetForReferences(parsed, nested.file.name, 0, 0))
    }

    @Test fun `attached source opens at the declaration without modifying the archive`(@TempDir root: Path) {
        val binary = library(root)
        val archive = jar(binary.resolveSibling("toolkit-1.0-sources.jar"), mapOf("example/library/GadgetWriter.java" to source.toByteArray()))
        val original = Files.readAllBytes(archive)
        write(root.resolve(".nop/classpath.txt"), binary.toString())
        val index = index(root)
        val document = assertNotNull(index.materialize(index.target(assertNotNull(index.find("example.library.GadgetWriter"))).file))
        assertTrue(document.hasSource)
        assertEquals(source, document.file.readText())
        assertEquals(3, document.line)
        assertTrue(original.contentEquals(Files.readAllBytes(archive)))
        val nested = assertNotNull(index.materialize(index.target(assertNotNull(index.find("example.library.GadgetWriter.Inner"))).file))
        assertTrue(nested.file.readText().lines()[nested.line - 1].contains("class Inner"))
        val companion = assertNotNull(index.materialize(index.target(assertNotNull(index.find("example.library.CompanionType"))).file))
        assertTrue(companion.hasSource, "SourceFile finds a secondary class in GadgetWriter.java")
        assertTrue(companion.file.readText().lines()[companion.line - 1].contains("class CompanionType"))
    }

    @Test fun `Gradle source artifact hash directory is discovered`(@TempDir root: Path) {
        val binary = library(root, "artifacts/toolkit/1.0/aaaaaaaaaaaaaaaa/toolkit-1.0.jar")
        jar(binary.parent.parent.resolve("bbbbbbbbbbbbbbbb/toolkit-1.0-sources.jar"),
            mapOf("example/library/GadgetWriter.java" to source.toByteArray()))
        write(root.resolve(".nop/classpath.txt"), binary.toString())
        val index = index(root)
        val document = assertNotNull(index.materialize(index.target(assertNotNull(index.find("example.library.GadgetWriter"))).file))
        assertTrue(document.hasSource)
    }

    @Test fun `compiled class directories support lookup and declarations`(@TempDir root: Path) {
        val classes = compile(root.resolve("fixture"))
        write(root.resolve(".nop/classpath.txt"), classes.toString())
        val index = index(root)
        val type = assertNotNull(index.find("example.library.GadgetWriter"))
        assertTrue(assertNotNull(index.materialize(index.target(type).file)).file.readText().contains("echo("))
    }

    @Test fun `imports and fully qualified names resolve a dependency despite a conflicting project class`(@TempDir root: Path) {
        val binary = library(root)
        write(root.resolve(".nop/classpath.txt"), binary.toString())
        val index = index(root)
        val text = "import example.library.GadgetWriter; class Example { GadgetWriter writer; }"
        val offset = text.lastIndexOf("GadgetWriter") + 2
        val project = SymbolIndex(listOf(IndexEntry("GadgetWriter", "Other.java", 1, SymbolKind.JAVA_TYPE, "other")))
        val target = assertNotNull(JumpResolver.resolve(project, root.toFile(), root.resolve("Example.java").toFile(), text, offset, libraries = index))
        assertTrue(assertNotNull(index.materialize(target.file)).label.startsWith("toolkit-1.0.jar!/"))
        val qualified = "class Example { example.library.GadgetWriter writer; }"
        assertEquals("example.library.GadgetWriter", index.resolve(qualified, qualified.indexOf("GadgetWriter") + 2)?.fqn)
        val nested = "import example.library.GadgetWriter; class Example { GadgetWriter.Inner inner; }"
        assertEquals("example.library.GadgetWriter.Inner", index.resolve(nested, nested.indexOf("Inner") + 1)?.fqn)
    }

    @Test fun `matching project source takes precedence over a dependency copy`(@TempDir root: Path) {
        val binary = library(root)
        write(root.resolve(".nop/classpath.txt"), binary.toString())
        val index = index(root)
        val projectFile = write(root.resolve("src/GadgetWriter.java"), source)
        val project = SymbolIndex(listOf(IndexEntry("GadgetWriter", "src/GadgetWriter.java", 3, SymbolKind.JAVA_TYPE, "example.library")))
        val text = "import example.library.GadgetWriter; class Example { GadgetWriter writer; }"
        val target = assertNotNull(JumpResolver.resolve(project, root.toFile(), root.resolve("Example.java").toFile(), text,
            text.lastIndexOf("GadgetWriter") + 1, libraries = index))
        assertEquals(projectFile.toFile(), target.file)
    }

    @Test fun `unchanged environments reuse the index and source attachment changes invalidate it`(@TempDir root: Path) {
        val binary = library(root)
        write(root.resolve(".nop/classpath.txt"), binary.toString())
        val first = index(root)
        assertSame(first, index(root, first))
        jar(binary.resolveSibling("toolkit-1.0-sources.jar"), mapOf("example/library/GadgetWriter.java" to source.toByteArray()))
        val attached = index(root, first)
        assertFalse(first === attached)
        write(root.resolve(".nop/classpath.txt"), "")
        assertTrue(index(root, attached).classes.isEmpty())
    }

    @Test fun `multi release jars use the runtime version without exposing metadata paths`(@TempDir root: Path) {
        val base = compile(root.resolve("base"), "package example.library; public class GadgetWriter { public void baseMethod() {} }")
        val current = compile(root.resolve("current"), "package example.library; public class GadgetWriter { public void currentMethod() {} }")
        val binary = jar(root.resolve("lib/versioned.jar"), mapOf(
            "example/library/GadgetWriter.class" to Files.readAllBytes(base.resolve("example/library/GadgetWriter.class")),
            "META-INF/versions/9/example/library/GadgetWriter.class" to Files.readAllBytes(current.resolve("example/library/GadgetWriter.class")),
        ), multiRelease = true)
        write(root.resolve(".nop/classpath.txt"), binary.toString())
        val index = index(root)
        assertEquals(listOf("example.library.GadgetWriter"), index.classes.map { it.fqn })
        val document = assertNotNull(index.materialize(index.target(index.classes.single()).file))
        assertTrue(document.file.readText().contains("currentMethod"))
    }

    @Test fun `JDK classes resolve through java lang and explicit imports without project dependencies`(@TempDir root: Path) {
        val index = JavaLibraryIndex.build(root.toFile(), root.resolve("cache"), jdkSourceArchives = emptyList())
        assertNotNull(index.find("java.lang.String"))
        assertNotNull(index.find("java.sql.Connection"))
        val text = "class Example { String name; }"
        assertEquals("java.lang.String", index.resolve(text, text.indexOf("String") + 1)?.fqn)
        val imported = "import java.util.Map.Entry; class Example { Entry entry; }"
        assertEquals("java.util.Map.Entry", index.resolve(imported, imported.lastIndexOf("Entry") + 1)?.fqn)
        val document = assertNotNull(index.materialize(index.target(assertNotNull(index.find("java.lang.String"))).file))
        assertFalse(document.hasSource)
        assertTrue(document.file.readText().contains("charAt("))
    }

    @Test fun `JDK source zip opens matching source at its class declaration`(@TempDir root: Path) {
        val source = "package java.lang;\n\npublic final class String { }"
        val archive = jar(root.resolve("platform/src.zip"), mapOf("java.base/java/lang/String.java" to source.toByteArray()))
        val index = JavaLibraryIndex.build(root.toFile(), root.resolve("cache"), jdkSourceArchives = listOf(archive.toFile()))
        val document = assertNotNull(index.materialize(index.target(assertNotNull(index.find("java.lang.String"))).file))
        assertTrue(document.hasSource)
        assertEquals(source, document.file.readText())
        assertEquals(3, document.line)
    }
}
