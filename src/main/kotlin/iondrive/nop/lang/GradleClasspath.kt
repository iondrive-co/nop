package iondrive.nop.lang

import iondrive.nop.terminal.InheritedEnvironment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

@Serializable
data class GradleJavaSourceSet(
    val projectDir: String,
    val name: String,
    val sourceRoots: List<String>,
    val classpath: List<String>,
    val dependencySources: List<String> = emptyList(),
) {
    fun sources(): List<File> = (sourceRoots + dependencySources).map(::File).filter { it.isDirectory }.distinct()
}

@Serializable
data class GradleJavaModel(val sourceSets: List<GradleJavaSourceSet> = emptyList(), val inputs: List<String> = emptyList()) {
    fun sourceSet(file: File): GradleJavaSourceSet? {
        val path = file.toPath().toAbsolutePath().normalize()
        return sourceSets.flatMap { set -> set.sourceRoots.map { Path.of(it).toAbsolutePath().normalize() to set } }
            .filter { path.startsWith(it.first) }.maxByOrNull { it.first.nameCount }?.second
            ?: sourceSets.filter { path.startsWith(Path.of(it.projectDir).toAbsolutePath().normalize()) }
                .sortedWith(compareByDescending<GradleJavaSourceSet> { Path.of(it.projectDir).nameCount }.thenBy { it.name != "main" })
                .firstOrNull()
    }

    fun classpath(file: File? = null): List<File> =
        (if (file == null) sourceSets.flatMap { it.classpath } else sourceSet(file)?.classpath.orEmpty())
            .map { File(it).absoluteFile.normalize() }.filter { it.exists() }.distinct()
}

/** Imports Gradle's resolved compile classpaths without compiling project sources or running processors. */
class GradleClasspath(
    private val root: File,
    private val cacheDir: Path,
    private val timeoutMillis: Long = TimeUnit.MINUTES.toMillis(3),
) {
    private val mutex = Mutex()
    private var cached: Snapshot? = null
    private var attempted: Pair<String, Int>? = null
    private var failure: Exception? = null
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable private data class Snapshot(val version: Int, val fingerprint: String, val model: GradleJavaModel, val artifacts: List<String>)

    /** [refresh] changes only on an explicit refresh; normal source edits reuse the imported model. */
    suspend fun load(files: List<String>, refresh: Int = 0): GradleJavaModel? = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!isGradleProject(root)) return@withLock null
            if (cached == null) cached = runCatching {
                json.decodeFromString<Snapshot>(Files.readString(cacheDir.resolve("model.json"))).takeIf { it.version == VERSION }
            }.getOrNull()
            val inputHash = fingerprint(files, cached?.model?.inputs.orEmpty())
            val request = inputHash to refresh
            if (attempted == request) {
                failure?.let { throw it }
                if (cached?.artifacts?.all { File(it).isFile } == true) return@withLock cached?.model
            }
            val force = if (attempted == null) refresh > 0 else attempted?.second != refresh
            if (!force && cached?.fingerprint == inputHash && cached!!.artifacts.all { File(it).isFile }) {
                attempted = request
                failure = null
                return@withLock cached!!.model
            }
            try {
                val model = importModel()
                val snapshot = Snapshot(VERSION, fingerprint(files, model.inputs), model, model.classpath().filter { it.isFile }.map { it.path })
                Files.createDirectories(cacheDir)
                val temporary = Files.createTempFile(cacheDir, "model-", ".json")
                try {
                    Files.writeString(temporary, json.encodeToString(snapshot))
                    Files.move(temporary, cacheDir.resolve("model.json"), StandardCopyOption.REPLACE_EXISTING)
                } finally { Files.deleteIfExists(temporary) }
                cached = snapshot
                attempted = snapshot.fingerprint to refresh
                failure = null
                model
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                attempted = request
                failure = error
                throw error
            }
        }
    }

    private suspend fun importModel(): GradleJavaModel {
        Files.createDirectories(cacheDir)
        val script = Files.createTempFile(cacheDir, "classpath-", ".init.gradle")
        val output = Files.createTempFile(cacheDir, "classpath-", ".json")
        val log = cacheDir.resolve("import.log").toFile()
        var process: Process? = null
        try {
            val resource = checkNotNull(javaClass.getResourceAsStream("/iondrive/nop/lang/gradle-classpath.init.gradle"))
            resource.use { Files.copy(it, script, StandardCopyOption.REPLACE_EXISTING) }
            val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
            val wrapper = File(root, if (windows) "gradlew.bat" else "gradlew")
            val command = when {
                wrapper.isFile && windows -> listOf("cmd", "/c", wrapper.absolutePath)
                wrapper.isFile && wrapper.canExecute() -> listOf(wrapper.absolutePath)
                wrapper.isFile -> listOf("sh", wrapper.absolutePath)
                windows -> listOf("cmd", "/c", "gradle")
                else -> listOf("gradle")
            } + listOf(
                "--console=plain", "--no-configuration-cache", "--no-configure-on-demand", "--init-script", script.toString(),
                "-Dnop.gradle.root=${root.canonicalPath}", "-Dnop.gradle.output=$output", ":nopExportJavaModel",
            )
            val builder = ProcessBuilder(command).directory(root).redirectErrorStream(true).redirectOutput(log)
            val environment = InheritedEnvironment.of(builder.environment()).apply {
                remove("_JPACKAGE_LAUNCHER")
                remove("LD_LIBRARY_PATH")
            }
            builder.environment().apply { clear(); putAll(environment) }
            process = builder.start()
            process.outputStream.close()
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
            while (process.isAlive) {
                if (System.nanoTime() >= deadline) throw IOException("Gradle import timed out; see ${log.absolutePath}")
                delay(100)
            }
            if (process.exitValue() != 0) {
                val tail = log.inputStream().use { stream ->
                    stream.skip((log.length() - 6000).coerceAtLeast(0))
                    stream.readBytes().toString(Charsets.UTF_8).trim()
                }
                val detail = tail.substringAfter("* What went wrong:", tail).substringBefore("* Try:").trim().take(1200)
                throw IOException("Gradle import failed (exit ${process.exitValue()}). $detail\nSee ${log.absolutePath}")
            }
            return json.decodeFromString<GradleJavaModel>(Files.readString(output))
        } finally {
            process?.takeIf { it.isAlive }?.let { running ->
                val descendants = running.descendants().toList()
                descendants.asReversed().forEach { it.destroyForcibly() }
                if (!running.waitFor(1, TimeUnit.SECONDS)) running.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
            }
            Files.deleteIfExists(script)
            Files.deleteIfExists(output)
        }
    }

    private fun fingerprint(files: List<String>, importedInputs: List<String>): String {
        val known = listOf("settings.gradle", "settings.gradle.kts", "build.gradle", "build.gradle.kts", "gradle.properties",
            "gradlew", "gradlew.bat", "gradle/wrapper/gradle-wrapper.properties", "gradle/wrapper/gradle-wrapper.jar",
            "gradle/verification-metadata.xml", "gradle/libs.versions.toml", "gradle.lockfile")
        val inputs = (known + files.filter { name ->
            name.endsWith(".gradle") || name.endsWith(".gradle.kts") || name.endsWith(".toml") || name.endsWith(".lockfile") ||
                name.substringAfterLast('/') == "gradle.properties" || name.startsWith("buildSrc/") || name.startsWith("build-logic/")
        }).map { File(root, it) } + importedInputs.map(::File) +
            File(System.getenv("GRADLE_USER_HOME") ?: File(System.getProperty("user.home"), ".gradle").path, "gradle.properties")
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("$VERSION|${root.canonicalPath}|${System.getenv("JAVA_HOME")}|${System.getenv("GRADLE_USER_HOME")}".toByteArray())
        for (file in inputs.distinctBy { it.absolutePath }.sortedBy { it.absolutePath }) {
            digest.update(file.absolutePath.toByteArray())
            digest.update(if (file.isFile) file.readBytes() else byteArrayOf(0))
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val VERSION = 1
        fun isGradleProject(root: File): Boolean = listOf("settings.gradle", "settings.gradle.kts", "build.gradle", "build.gradle.kts")
            .any { File(root, it).isFile }
    }
}
