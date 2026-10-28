package iondrive.nop.lang

import java.io.File
import java.io.IOException

/** The dependency paths shared by compiler diagnostics and library navigation. */
object JavaClasspath {
    fun read(root: File): List<File> {
        val config = File(root, ".nop/classpath.txt")
        if (!config.exists()) return emptyList()
        return config.readLines().flatMap { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith('#')) return@flatMap emptyList()
            val wildcard = line.endsWith("/*") || line.endsWith("\\*")
            val path = if (wildcard) line.dropLast(2) else line
            val entry = File(path).let { if (it.isAbsolute) it else File(root, path) }.absoluteFile.normalize()
            if (wildcard) {
                if (!entry.isDirectory) throw IOException("Classpath directory does not exist: $line")
                entry.listFiles()?.filter { it.isFile && it.extension.equals("jar", ignoreCase = true) }
                    ?.sortedBy { it.name } ?: throw IOException("Cannot read classpath directory: $line")
            } else {
                if (!entry.exists()) throw IOException("Classpath entry does not exist: $line")
                listOf(entry)
            }
        }.distinct()
    }
}
