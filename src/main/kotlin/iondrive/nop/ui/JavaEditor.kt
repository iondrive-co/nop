package iondrive.nop.ui

import java.io.File
import java.nio.file.Path

/** The working Java buffer behind either a source tab or a diff of that file. */
internal fun javaEditorFor(tab: Tab?, projectRoot: Path, store: FileEditStore): Triple<File, String, FileEdit>? {
    val file = (tab as? Tab.FileView)?.file ?: jumpToSourceTarget(tab) ?: return null
    if (!file.extension.equals("java", ignoreCase = true)) return null
    val root = projectRoot.toAbsolutePath().normalize()
    val path = file.toPath().toAbsolutePath().normalize()
    if (!path.startsWith(root) || path == root) return null
    val relative = root.relativize(path).toString().replace(File.separatorChar, '/')
    return Triple(file, relative, store.edit(Tab.FileView(file)))
}
