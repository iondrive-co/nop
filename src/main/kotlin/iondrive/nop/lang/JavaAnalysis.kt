package iondrive.nop.lang

import com.sun.source.util.JavacTask
import iondrive.nop.Log
import java.io.File
import javax.tools.DiagnosticCollector
import javax.tools.ForwardingJavaFileManager
import javax.tools.JavaFileManager.Location
import javax.tools.JavaFileObject
import javax.tools.JavaFileObject.Kind
import javax.tools.SimpleJavaFileObject
import javax.tools.StandardJavaFileManager
import javax.tools.StandardLocation
import javax.tools.ToolProvider

data class JavaAnalysisResult(val problems: List<JavaProblem>, val failure: String? = null)

data class JavaAnalysisProject(val root: File, val files: List<String> = emptyList(), val revision: Int = 0)

/** Compiler checks for an editor buffer. The project-wide index continues to use the cheaper parse. */
object JavaAnalysis {
    fun analyze(
        text: String,
        file: File,
        projectRoot: File = file.absoluteFile.parentFile,
        projectFiles: List<String> = emptyList(),
        openBuffers: Map<File, String> = emptyMap(),
    ): JavaAnalysisResult {
        val compiler = ToolProvider.getSystemJavaCompiler()
            ?: return JavaAnalysisResult(emptyList(), "Java compiler unavailable — Java analysis is off")
        return runCatching {
            val root = projectRoot.absoluteFile.normalize()
            val source = BufferSource(file, text)
            val buffers = openBuffers.filterKeys {
                it.extension.equals("java", ignoreCase = true) &&
                    it.absoluteFile.normalize().toPath().startsWith(root.toPath())
            }
                .mapKeys { it.key.absoluteFile.normalize() } + (file.absoluteFile.normalize() to text)
            val sources = sourceRoots(root, projectFiles, buffers)
            val collector = DiagnosticCollector<JavaFileObject>()
            compiler.getStandardFileManager(collector, null, Charsets.UTF_8).use { standard ->
                // nop's own implementation dependencies must never become project dependencies.
                standard.setLocation(StandardLocation.CLASS_PATH, JavaClasspath.read(root))
                standard.setLocation(StandardLocation.SOURCE_PATH, sources)
                standard.setLocation(StandardLocation.ANNOTATION_PROCESSOR_PATH, emptyList())
                val manager = BufferFileManager(standard, buffers.map { BufferSource(it.key, it.value) })
                val task = compiler.getTask(
                    null, manager, collector,
                    listOf("-proc:none", "-implicit:none", "-nowarn"), null, listOf(source),
                ) as JavacTask
                task.parse().toList()
                task.analyze().toList()
                JavaAnalysisResult(JavaParse.problems(collector, text, source.toUri()))
            }
        }.getOrElse { error ->
            Log.error("Java analysis failed for ${file.name}", error)
            JavaAnalysisResult(emptyList(), "Java analysis failed: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun sourceRoots(root: File, paths: List<String>, buffers: Map<File, String>): List<File> {
        val roots = linkedSetOf(root)
        for ((file, text) in buffers) {
            var directory = file.parentFile
            val pkg = JavaHeader.of(text).packageName
            if (pkg.isNotEmpty()) {
                for (part in pkg.split('.').asReversed()) {
                    directory = directory?.takeIf { it.name == part }?.parentFile
                }
            }
            if (directory != null && directory.toPath().startsWith(root.toPath())) roots += directory
        }
        // Conventional source folders also cover sibling modules and main sources while editing tests.
        val layouts = listOf("src/main/java", "src/test/java")
        for (layout in layouts) {
            File(root, layout).takeIf { it.isDirectory }?.let { roots += it }
        }
        for (path in paths) {
            if (!path.endsWith(".java", ignoreCase = true)) continue
            for (layout in layouts) {
                val index = path.indexOf("/$layout/")
                if (index >= 0) roots += File(root, path.substring(0, index + layout.length + 1))
            }
        }
        return roots.filter { it.isDirectory }
    }

    private class BufferSource(file: File, private val text: String) :
        SimpleJavaFileObject(file.absoluteFile.normalize().toURI(), Kind.SOURCE) {
        val binaryName = JavaHeader.of(text).packageName.let {
            if (it.isEmpty()) file.nameWithoutExtension else "$it.${file.nameWithoutExtension}"
        }
        override fun getCharContent(ignoreEncodingErrors: Boolean): CharSequence = text
    }

    /** Source lookup sees the same unsaved versions as the open editors. */
    private class BufferFileManager(
        standard: StandardJavaFileManager,
        private val buffers: List<BufferSource>,
    ) : ForwardingJavaFileManager<StandardJavaFileManager>(standard) {
        override fun getJavaFileForInput(location: Location, className: String, kind: Kind): JavaFileObject? {
            if (location == StandardLocation.SOURCE_PATH && kind == Kind.SOURCE) {
                buffers.firstOrNull { it.binaryName == className }?.let { return it }
            }
            return super.getJavaFileForInput(location, className, kind)
        }

        override fun list(
            location: Location,
            packageName: String,
            kinds: Set<Kind>,
            recurse: Boolean,
        ): Iterable<JavaFileObject> {
            val listed = super.list(location, packageName, kinds, recurse)
            if (location != StandardLocation.SOURCE_PATH || Kind.SOURCE !in kinds) return listed
            val matching = buffers.filter {
                val pkg = it.binaryName.substringBeforeLast('.', "")
                pkg == packageName || (recurse && (packageName.isEmpty() || pkg.startsWith("$packageName.")))
            }.associateBy { it.toUri() }
            return listed.filter { it.toUri() !in matching } + matching.values
        }

        override fun inferBinaryName(location: Location, file: JavaFileObject): String =
            if (file is BufferSource) file.binaryName else super.inferBinaryName(location, file)
    }
}
