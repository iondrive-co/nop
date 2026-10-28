package iondrive.nop.lang

import iondrive.nop.index.JavaLibraryIndex
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GradleClasspathTest {
    private fun write(root: Path, path: String, text: String): Path = root.resolve(path).also {
        Files.createDirectories(it.parent)
        Files.writeString(it, text)
    }

    private fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"

    private fun artifact(root: Path, group: String, name: String, type: String, source: String, dependencies: String = "") {
        val sourceFile = write(root, "artifacts/$name/src/${type.replace('.', '/')}.java", source)
        val classes = Files.createDirectories(root.resolve("artifacts/$name/classes"))
        assertEquals(0, assertNotNull(ToolProvider.getSystemJavaCompiler()).run(null, null, null,
            "-d", classes.toString(), sourceFile.toString()))
        val base = "repo/${group.replace('.', '/')}/$name/1.0/$name-1.0"
        val jar = root.resolve("$base.jar")
        Files.createDirectories(jar.parent)
        JarOutputStream(Files.newOutputStream(jar)).use { output ->
            val entry = type.replace('.', '/') + ".class"
            output.putNextEntry(JarEntry(entry))
            Files.copy(classes.resolve(entry), output)
            output.closeEntry()
        }
        write(root, "$base.pom", """
            <project><modelVersion>4.0.0</modelVersion><groupId>$group</groupId><artifactId>$name</artifactId><version>1.0</version>
            <dependencies>$dependencies</dependencies></project>
        """.trimIndent())
    }

    @Test fun `Gradle resolves scoped transitive dependencies and project sources without compiling`(@TempDir root: Path) = runBlocking {
        artifact(root, "example.text", "helpers", "example.text.TextSink", "package example.text; public class TextSink {}")
        artifact(root, "example.annotations", "qualifiers", "example.annotations.MaybeNull",
            "package example.annotations; public @interface MaybeNull {}",
            "<dependency><groupId>example.text</groupId><artifactId>helpers</artifactId><version>1.0</version></dependency>")
        artifact(root, "example.testing", "checks", "example.testing.TestProbe", "package example.testing; public class TestProbe {}")
        val gradle = Path.of(assertNotNull(System.getProperty("nop.test.gradleHome")), "bin", "gradle")
        write(root, "gradlew", """
            #!/bin/sh
            printf '.\n' >> wrapper-calls
            exec ${quote(gradle.toString())} --offline "${'$'}@"
        """.trimIndent())
        // Exercise the non-executable-wrapper path too.
        write(root, "settings.gradle", "rootProject.name = 'sample'; include 'model', 'app', 'unrelated'")
        write(root, "build.gradle", """
            subprojects {
                apply plugin: 'java'
                repositories { maven { url = uri(rootProject.file('repo')) } }
            }
        """.trimIndent())
        write(root, "app/build.gradle", """
            dependencies {
                implementation project(':model')
                compileOnly 'example.annotations:qualifiers:1.0'
                testImplementation 'example.testing:checks:1.0'
            }
            sourceSets {
                main { java.setSrcDirs(['code']) }
                verification { java.srcDir 'checks' }
            }
            configurations.verificationImplementation.extendsFrom configurations.implementation
        """.trimIndent())
        write(root, "model/build.gradle", "")
        write(root, "unrelated/build.gradle", "")
        write(root, "model/src/main/java/example/model/Widget.java", "package example.model; public class Widget {}")
        write(root, "unrelated/src/main/java/example/other/Hidden.java", "package example.other; public class Hidden {}")
        val main = write(root, "app/code/example/app/Example.java", """
            package example.app;
            import example.annotations.MaybeNull;
            import example.model.Widget;
            import example.text.TextSink;
            class Example { @MaybeNull Widget widget; TextSink sink; }
        """.trimIndent()).toFile()
        val test = write(root, "app/src/test/java/example/app/ExampleTest.java",
            "package example.app; class ExampleTest { example.testing.TestProbe probe; example.model.Widget widget; }").toFile()
        val verification = write(root, "app/checks/example/app/Verify.java",
            "package example.app; class Verify { example.model.Widget widget; }").toFile()
        val files = listOf("build.gradle", "settings.gradle", "app/build.gradle", "model/build.gradle", "unrelated/build.gradle")
        val cache = root.resolve("import-cache")
        val importer = GradleClasspath(root.toFile(), cache)
        val model = assertNotNull(importer.load(files))
        fun analyze(file: java.io.File, text: String = file.readText()) =
            JavaAnalysis.analyze(text, file, root.toFile(), gradle = model)
        for (file in listOf(main, test, verification)) {
            val result = analyze(file)
            assertNull(result.failure)
            assertTrue(result.problems.isEmpty(), "${file.name}: ${result.problems}")
        }
        assertTrue(analyze(main, "package example.app; class Example { example.testing.TestProbe probe; }").problems.isNotEmpty())
        assertTrue(analyze(main, "package example.app; class Example { example.other.Hidden hidden; }").problems.isNotEmpty())
        val unrelated = root.resolve("unrelated/src/main/java/example/other/Hidden.java").toFile()
        assertTrue(JavaAnalysis.analyze("package example.app; class Example { example.other.Hidden hidden; }", main, root.toFile(),
            openBuffers = mapOf(unrelated to unrelated.readText()), gradle = model).problems.isNotEmpty())
        assertTrue(model.classpath(main).any { it.name == "qualifiers-1.0.jar" })
        assertTrue(model.classpath(main).any { it.name == "helpers-1.0.jar" })
        assertFalse(model.classpath(main).any { it.name == "checks-1.0.jar" })
        val index = JavaLibraryIndex.build(root.toFile(), root.resolve("library-cache"), includeJdk = false, gradle = model)
        val type = assertNotNull(index.find("example.annotations.MaybeNull"))
        assertTrue(assertNotNull(index.materialize(index.target(type).file)).file.readText().contains("@interface MaybeNull"))
        assertNotNull(index.find("example.text.TextSink"))
        assertFalse(Files.exists(root.resolve("app/build/classes")), "Import must not compile the application")
        assertFalse(Files.exists(root.resolve("model/build/libs")), "Import must not build project dependency JARs")
        assertFalse(Files.exists(root.resolve(".nop/classpath.txt")), "The manual classpath remains optional")
        fun calls() = Files.readAllLines(root.resolve("wrapper-calls")).size
        assertEquals(1, calls())
        assertEquals(model, importer.load(files + "app/code/New.java"))
        assertEquals(model, GradleClasspath(root.toFile(), cache).load(files))
        assertEquals(1, calls(), "Source edits and a restart reuse the cached model")
        assertNotNull(importer.load(files, refresh = 1))
        assertEquals(2, calls(), "F5 forces another dependency resolution")
        val buildFile = root.resolve("app/build.gradle")
        Files.writeString(buildFile, Files.readString(buildFile).replace("compileOnly 'example.annotations:qualifiers:1.0'", ""))
        val changed = assertNotNull(importer.load(files, refresh = 1))
        assertEquals(3, calls())
        assertFalse(changed.classpath(main).any { it.name == "qualifiers-1.0.jar" })
        val errors = JavaAnalysis.analyze(main.readText(), main, root.toFile(), gradle = changed)
        assertTrue(errors.problems.any { "example.annotations" in it.message }, errors.toString())
        val missing = assertNotNull(changed.classpath(test).firstOrNull { it.name == "checks-1.0.jar" })
        Files.delete(missing.toPath())
        assertFailsWith<java.io.IOException> { importer.load(files, refresh = 1) }
        assertEquals(4, calls(), "A deleted dependency invalidates the in-memory cache")
    }

    @Test fun `Kotlin DSL builds import their compile only dependencies and custom source roots`(@TempDir root: Path) = runBlocking {
        artifact(root, "example.annotations", "qualifiers", "example.annotations.MaybeNull",
            "package example.annotations; public @interface MaybeNull {}")
        val gradle = Path.of(assertNotNull(System.getProperty("nop.test.gradleHome")), "bin", "gradle")
        write(root, "gradlew", "#!/bin/sh\nexec ${quote(gradle.toString())} --offline \"${'$'}@\"\n")
        write(root, "settings.gradle.kts", "rootProject.name = \"sample\"")
        write(root, "build.gradle.kts", """
            plugins { java }
            dependencies { compileOnly(files("repo/example/annotations/qualifiers/1.0/qualifiers-1.0.jar")) }
            sourceSets.named("main") { java.setSrcDirs(listOf("code")) }
        """.trimIndent())
        val file = write(root, "code/Example.java",
            "import example.annotations.MaybeNull; class Example { @MaybeNull String value; }").toFile()
        val model = assertNotNull(GradleClasspath(root.toFile(), root.resolve("cache"))
            .load(listOf("build.gradle.kts", "settings.gradle.kts")))
        assertEquals("main", model.sourceSet(file)?.name)
        val result = JavaAnalysis.analyze(file.readText(), file, root.toFile(), gradle = model)
        assertNull(result.failure)
        assertTrue(result.problems.isEmpty(), result.toString())
        assertFalse(Files.exists(root.resolve("build/classes")))
    }

    @Test fun `failed import is visible and retries only after a build change or explicit refresh`(@TempDir root: Path) = runBlocking {
        write(root, "build.gradle", "")
        write(root, "gradlew", "#!/bin/sh\nprintf '.\\n' >> wrapper-calls\necho 'Fixture dependency resolution failed' >&2\nexit 7\n")
        val importer = GradleClasspath(root.toFile(), root.resolve("cache"))
        repeat(2) {
            val error = assertFailsWith<java.io.IOException> { importer.load(listOf("build.gradle")) }
            assertTrue(error.message.orEmpty().contains("Fixture dependency resolution failed"))
        }
        assertEquals(1, Files.readAllLines(root.resolve("wrapper-calls")).size)
        assertFailsWith<java.io.IOException> { importer.load(listOf("build.gradle"), refresh = 1) }
        assertEquals(2, Files.readAllLines(root.resolve("wrapper-calls")).size)
        write(root, "build.gradle", "// Changed build configuration")
        assertFailsWith<java.io.IOException> { importer.load(listOf("build.gradle"), refresh = 1) }
        assertEquals(3, Files.readAllLines(root.resolve("wrapper-calls")).size)
        assertFalse(Files.exists(root.resolve("cache/model.json")))
    }

    @Test fun `timeout and cancellation terminate the import client and its child`(@TempDir root: Path) = runBlocking {
        for (cancel in listOf(false, true)) {
            val project = Files.createDirectories(root.resolve(if (cancel) "cancel" else "timeout"))
            write(project, "build.gradle", "")
            write(project, "gradlew", "#!/bin/sh\necho ${'$'}${'$'} > client-pid\nsleep 60 &\necho ${'$'}! > child-pid\nwait\n")
            val importer = GradleClasspath(project.toFile(), project.resolve("cache"), timeoutMillis = if (cancel) 60_000 else 500)
            if (cancel) {
                val job = launch { importer.load(emptyList()) }
                withTimeout(5000) { while (!Files.exists(project.resolve("child-pid"))) delay(10) }
                job.cancelAndJoin()
            } else {
                val error = assertFailsWith<java.io.IOException> { importer.load(emptyList()) }
                assertTrue(error.message.orEmpty().contains("timed out"))
            }
            for (name in listOf("client-pid", "child-pid")) {
                val pid = Files.readString(project.resolve(name)).trim().toLong()
                withTimeout(5000) { while (ProcessHandle.of(pid).map { it.isAlive }.orElse(false)) delay(10) }
            }
        }
    }

    @Test fun `non Gradle projects need no external process`(@TempDir root: Path) = runBlocking {
        assertNull(GradleClasspath(root.toFile(), root.resolve("cache")).load(emptyList()))
        assertFalse(Files.exists(root.resolve("cache")))
    }
}
