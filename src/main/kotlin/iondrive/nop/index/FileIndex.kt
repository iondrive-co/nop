package iondrive.nop.index

import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * Flat list of project-relative file paths used to power the double-shift file search dialog.
 * Built from the same filtered walk as [Indexer] so search results stay consistent with what the
 * project tree displays. Persisted alongside the symbol index under the project's data dir.
 */
class FileIndex(val files: List<String> = emptyList()) {

    companion object {
        private val IGNORED_DIR_NAMES = setOf(
            ".git", ".idea", ".gradle", ".vscode",
            "node_modules", "build", "out", "target", "dist", ".next", "__pycache__",
        )

        fun build(projectRoot: Path): FileIndex {
            val rootFile = projectRoot.toAbsolutePath().normalize().toFile()
            if (!rootFile.isDirectory) return FileIndex()
            val out = ArrayList<String>()
            walk(rootFile, rootFile, out)
            out.sortBy { it.lowercase() }
            return FileIndex(out)
        }

        private fun walk(root: File, dir: File, out: MutableList<String>) {
            val children = dir.listFiles() ?: return
            for (f in children) {
                if (f.name in IGNORED_DIR_NAMES || f.isHidden) continue
                if (f.isDirectory) {
                    walk(root, f, out)
                } else if (f.isFile) {
                    out += relPath(root, f)
                }
            }
        }

        private fun relPath(root: File, file: File): String {
            val rp = file.toPath().toAbsolutePath().normalize()
            val rr = root.toPath().toAbsolutePath().normalize()
            return rr.relativize(rp).toString().replace(File.separatorChar, '/')
        }

        fun load(path: Path): FileIndex {
            if (!Files.isRegularFile(path)) return FileIndex()
            val text = runCatching { Files.readString(path) }.getOrNull() ?: return FileIndex()
            val files = text.lineSequence().filter { it.isNotBlank() }.toList()
            return FileIndex(files)
        }

        fun save(path: Path, index: FileIndex) {
            runCatching {
                Files.createDirectories(path.parent)
                Files.writeString(path, index.files.joinToString("\n"))
            }
        }
    }
}
