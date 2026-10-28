package iondrive.nop.index

import iondrive.nop.lang.JavaClassDeclaration
import iondrive.nop.lang.JavaClasspath
import iondrive.nop.lang.JavaHeader
import java.io.DataInputStream
import java.io.File
import java.lang.module.ModuleFinder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.jar.JarFile
import java.util.zip.ZipFile

data class JavaLibraryClass(val binaryName: String, val container: File? = null, val module: String? = null) {
    val fqn: String get() = binaryName.replace('$', '.')
    val name: String get() = fqn.substringAfterLast('.')
    val origin: String get() = container?.name ?: "JDK $module"
    val entry: String get() = binaryName.replace('.', '/') + ".class"
    val searchPath: String get() = "Libraries/$origin/${binaryName.replace('.', '/').replace('$', '.')}.java"
}

data class JavaLibraryDocument(val file: File, val label: String, val line: Int, val hasSource: Boolean)

/** Immutable class names for lookup; source extraction and signature rendering happen only on open. */
class JavaLibraryIndex private constructor(
    val classes: List<JavaLibraryClass>,
    private val classpath: List<File>,
    private val sources: Map<File, List<File>>,
    private val jdkSources: List<File>,
    private val cacheRoot: Path,
    private val fingerprint: String,
) {
    private val byFqn = classes.associateBy { it.fqn }
    private val byName = classes.groupBy { it.name }
    private val bySearchPath = classes.associateBy { it.searchPath }
    private val targets = ConcurrentHashMap<Path, JavaLibraryClass>()
    private val documents = ConcurrentHashMap<Path, JavaLibraryDocument>()
    val searchPaths: List<String> = bySearchPath.keys.sortedWith(String.CASE_INSENSITIVE_ORDER)

    fun find(fqn: String): JavaLibraryClass? = byFqn[fqn.replace('$', '.')]

    fun searchTarget(path: String): JumpTarget? = bySearchPath[path]?.let(::target)

    fun target(type: JavaLibraryClass): JumpTarget {
        val key = digest("$fingerprint\n${type.container?.absolutePath}\n${type.module}\n${type.binaryName}")
        val file = cacheRoot.toAbsolutePath().normalize().resolve(key).resolve("${type.name}.java")
        targets[file] = type
        return JumpTarget(file.toFile(), 1)
    }

    /** Imports, qualified names and java.lang establish a class before a unique-name fallback. */
    fun resolve(text: String, offset: Int): JavaLibraryClass? {
        val wordRange = JumpResolver.wordRangeAt(text, offset) ?: return null
        val word = text.substring(wordRange.first, wordRange.last + 1)
        val header = JavaHeader.of(text)
        val qualified = JumpResolver.pathRangeAt(text, offset)?.let {
            text.substring(it.first, wordRange.last + 1)
        }
        qualified?.let { name ->
            find(name)?.let { return it }
            if ('.' in name) {
                val outer = name.substringBefore('.')
                header.importedFqnFor(outer)?.let { imported ->
                    find(imported + name.removePrefix(outer))?.let { return it }
                }
            }
        }
        header.importedFqnFor(word)?.let { return find(it) }
        find(if (header.packageName.isEmpty()) word else "${header.packageName}.$word")?.let { return it }
        val wildcard = header.wildcardPackages.mapNotNull { find("$it.$word") }.distinct()
        val javaLang = find("java.lang.$word")
        val visible = (wildcard + listOfNotNull(javaLang)).distinct()
        if (visible.isNotEmpty()) return visible.singleOrNull()
        return byName[word]?.singleOrNull()
    }

    fun isExplicit(text: String, offset: Int, type: JavaLibraryClass): Boolean {
        if (type.fqn in JavaHeader.of(text).imports) return true
        val range = JumpResolver.pathRangeAt(text, offset) ?: return false
        val word = JumpResolver.wordRangeAt(text, offset) ?: return false
        return text.substring(range.first, word.last + 1) == type.fqn
    }

    /** Null means an ordinary project file. Called on an IO dispatcher before opening a library tab. */
    fun materialize(file: File): JavaLibraryDocument? {
        val path = file.toPath().toAbsolutePath().normalize()
        val type = targets[path] ?: return null
        return documents.computeIfAbsent(path) {
            val attached = attachedSource(type)
            val text = attached?.second ?: JavaClassDeclaration.render(type.binaryName, classpath)
            Files.createDirectories(path.parent)
            if (!Files.isRegularFile(path) || Files.readString(path) != text) Files.writeString(path, text)
            val line = declarationLine(text, type.name)
            JavaLibraryDocument(path.toFile(), "${type.origin}!/${attached?.first ?: type.entry}", line, attached != null)
        }
    }

    private fun attachedSource(type: JavaLibraryClass): Pair<String, String>? {
        val sourceName = classSourceFile(type) ?: type.binaryName.substringAfterLast('.').substringBefore('$') + ".java"
        if ('/' in sourceName || '\\' in sourceName || !sourceName.endsWith(".java")) return null
        val pkg = type.binaryName.substringBeforeLast('.', "").replace('.', '/')
        val path = if (pkg.isEmpty()) sourceName else "$pkg/$sourceName"
        val archives = type.container?.let { sources[it].orEmpty() } ?: jdkSources
        for (archive in archives) {
            val found = runCatching {
                ZipFile(archive).use { zip ->
                    val candidates = listOfNotNull(type.module?.let { "$it/$path" }, path, "src/main/java/$path")
                    val entry = candidates.firstNotNullOfOrNull { zip.getEntry(it) } ?: return@use null
                    val bytes = zip.getInputStream(entry).use { it.readNBytes(MAX_SOURCE_BYTES + 1) }
                    if (bytes.size > MAX_SOURCE_BYTES) return@use null
                    entry.name to bytes.toString(Charsets.UTF_8)
                }
            }.getOrNull()
            if (found != null) return found
        }
        return null
    }

    private fun classSourceFile(type: JavaLibraryClass): String? = runCatching {
        val bytes = when {
            type.container == null -> Files.readAllBytes(Path.of(java.net.URI.create("jrt:/modules/${type.module}/${type.entry}")))
            type.container.isDirectory -> File(type.container, type.entry).readBytes()
            else -> versionedJar(type.container).use { jar ->
                val entry = jar.getJarEntry(type.entry) ?: return@use null
                jar.getInputStream(entry).use { it.readNBytes(MAX_SOURCE_BYTES) }
            }
        } ?: return@runCatching null
        sourceFileAttribute(bytes)
    }.getOrNull()

    companion object {
        private const val MAX_SOURCE_BYTES = 8 * 1024 * 1024
        private val platformClasses: List<JavaLibraryClass> by lazy {
            ModuleFinder.ofSystem().findAll().sortedBy { it.descriptor().name() }.flatMap { reference ->
                val exported = reference.descriptor().exports().filterNot { it.isQualified }.map { it.source() }.toSet()
                reference.open().use { reader ->
                    reader.list().use { entries ->
                        entries.filter { it.endsWith(".class") && it.substringBeforeLast('/', "").replace('/', '.') in exported }
                            .map { it.removeSuffix(".class").replace('/', '.') }
                            .filter(::className)
                            .map { JavaLibraryClass(it, module = reference.descriptor().name()) }
                            .toList()
                    }
                }
            }
        }

        fun empty(cacheRoot: Path): JavaLibraryIndex = JavaLibraryIndex(emptyList(), emptyList(), emptyMap(), emptyList(), cacheRoot, "")

        fun build(
            projectRoot: File,
            cacheRoot: Path,
            previous: JavaLibraryIndex? = null,
            includeJdk: Boolean = true,
            jdkSourceArchives: List<File> = defaultJdkSources(),
        ): JavaLibraryIndex {
            val classpath = JavaClasspath.read(projectRoot)
            val sourceArchives = classpath.filter { it.isFile }.associateWith(::sourceArchives)
            val directoryClasses = classpath.filter { it.isDirectory }.associateWith { directory ->
                Files.walk(directory.toPath()).use { files ->
                    files.filter { Files.isRegularFile(it) && it.toString().endsWith(".class") }.sorted().toList()
                }
            }
            val fingerprint = buildString {
                append("${Runtime.version()}|$includeJdk|${System.getProperty("java.home")}\n")
                for (entry in classpath + sourceArchives.values.flatten() + jdkSourceArchives) append(stamp(entry))
                for ((_, files) in directoryClasses) for (file in files) append(stamp(file.toFile()))
            }.let(::digest)
            if (previous != null && previous.fingerprint == fingerprint && previous.cacheRoot == cacheRoot) return previous
            val classes = linkedMapOf<String, JavaLibraryClass>()
            if (includeJdk) for (type in platformClasses) classes[type.binaryName] = type
            for (entry in classpath) {
                val names = if (entry.isDirectory) {
                    directoryClasses[entry].orEmpty().map {
                        entry.toPath().relativize(it).toString().replace(File.separatorChar, '/').removeSuffix(".class").replace('/', '.')
                    }
                } else {
                    versionedJar(entry).use { jar ->
                        jar.versionedStream().use { entries ->
                            entries.filter { !it.isDirectory && it.name.endsWith(".class") && !it.name.startsWith("META-INF/") }
                                .map { it.name.removeSuffix(".class").replace('/', '.') }.toList()
                        }
                    }
                }
                for (name in names) if (className(name)) classes.putIfAbsent(name, JavaLibraryClass(name, entry))
            }
            return JavaLibraryIndex(classes.values.toList(), classpath, sourceArchives, jdkSourceArchives, cacheRoot, fingerprint)
        }

        private fun className(name: String): Boolean = name.split('.', '$').all { part ->
            part.isNotEmpty() && Character.isJavaIdentifierStart(part[0]) && part.all(Character::isJavaIdentifierPart)
        } && !name.endsWith("package-info") && !name.endsWith("module-info")

        private fun sourceArchives(binary: File): List<File> {
            if (!binary.name.endsWith(".jar", ignoreCase = true)) return emptyList()
            val name = binary.name.dropLast(4) + "-sources.jar"
            val candidates = mutableListOf(File(binary.parentFile, name))
            // Gradle keeps a version's binary and source artifacts in separate hash directories.
            if (binary.parentFile.name.matches(Regex("[a-fA-F0-9]{16,64}"))) {
                binary.parentFile.parentFile.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }
                    ?.forEach { candidates += File(it, name) }
            }
            return candidates.filter { it.isFile }.distinct()
        }

        private fun defaultJdkSources(): List<File> {
            val homes = listOfNotNull(System.getProperty("java.home"), System.getenv("JAVA_HOME")).map(::File).distinct()
            return homes.flatMap { home ->
                val version = runCatching { File(home, "release").readLines().first { it.startsWith("JAVA_VERSION=") } }
                    .getOrNull()?.substringAfter('"')?.substringBefore('.')?.substringBefore('"')?.toIntOrNull()
                if (version != Runtime.version().feature()) emptyList()
                else listOf(File(home, "lib/src.zip"), File(home, "src.zip")).filter { it.isFile }
            }.distinct()
        }

        private fun versionedJar(file: File) = JarFile(file, false, ZipFile.OPEN_READ, Runtime.version())
        private fun stamp(file: File) = "${file.absolutePath}|${file.lastModified()}|${file.length()}\n"
        private fun digest(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }

        private fun declarationLine(text: String, name: String): Int {
            val match = Regex("\\b(?:class|interface|enum|record)\\s+${Regex.escape(name)}\\b").find(text) ?: return 1
            return text.take(match.range.first).count { it == '\n' } + 1
        }

        /** SourceFile also locates secondary top-level classes whose names differ from their source file. */
        private fun sourceFileAttribute(bytes: ByteArray): String? = DataInputStream(bytes.inputStream()).use { input ->
            if (input.readInt() != 0xCAFEBABE.toInt()) return@use null
            input.skipNBytes(4)
            val constants = arrayOfNulls<String>(input.readUnsignedShort())
            var index = 1
            while (index < constants.size) {
                when (input.readUnsignedByte()) {
                    1 -> constants[index] = input.readUTF()
                    3, 4, 9, 10, 11, 12, 17, 18 -> input.skipNBytes(4)
                    5, 6 -> { input.skipNBytes(8); index++ }
                    7, 8, 16, 19, 20 -> input.skipNBytes(2)
                    15 -> input.skipNBytes(3)
                    else -> return@use null
                }
                index++
            }
            input.skipNBytes(6)
            input.skipNBytes(input.readUnsignedShort().toLong() * 2)
            repeat(2) {
                repeat(input.readUnsignedShort()) {
                    input.skipNBytes(6)
                    repeat(input.readUnsignedShort()) {
                        input.readUnsignedShort()
                        input.skipNBytes(Integer.toUnsignedLong(input.readInt()))
                    }
                }
            }
            repeat(input.readUnsignedShort()) {
                val attribute = constants[input.readUnsignedShort()]
                val length = Integer.toUnsignedLong(input.readInt())
                if (attribute == "SourceFile" && length == 2L) return@use constants[input.readUnsignedShort()]
                input.skipNBytes(length)
            }
            null
        }
    }
}
