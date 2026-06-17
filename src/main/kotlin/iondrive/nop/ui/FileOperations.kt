package iondrive.nop.ui

import java.io.File
import java.io.IOException

/**
 * Filesystem mutations behind the project-tree context menu: new file, new directory, new
 * package, and copy-file-to-new. Kept free of Compose so the path-resolution and validation
 * rules can be unit-tested directly.
 *
 * Each call returns the File it created — for the nested cases (a/b/c, com.example.app) that's
 * the deepest entry, so the caller can reveal or open it. Failures surface as
 * IllegalArgumentException (bad name) or IOException (already exists / I/O error) carrying a
 * message suitable for showing in the dialog.
 */
object FileOperations {
    /**
     * Where a "new entry next to [target]" action should create things: inside [target] when it
     * is a directory, otherwise alongside it (in its parent). Mirrors the IDE convention where
     * acting on a file creates a sibling.
     */
    fun parentDirFor(target: File): File =
        if (target.isDirectory) target else (target.parentFile ?: target.absoluteFile.parentFile)

    /**
     * Create an empty file under [parentDir]. [rawName] may contain '/' to create intermediate
     * directories first (e.g. "sub/dir/Main.kt").
     */
    fun createFile(parentDir: File, rawName: String): File {
        val target = resolve(parentDir, splitPath(rawName, "file name"))
        if (target.exists()) throw IOException("\"${target.name}\" already exists")
        target.parentFile?.mkdirs()
        if (!target.createNewFile()) throw IOException("Could not create \"${target.name}\"")
        return target
    }

    /** Create a directory under [parentDir]. '/' nests, so "a/b/c" makes three levels. */
    fun createDirectory(parentDir: File, rawName: String): File =
        makeDirs(parentDir, splitPath(rawName, "directory name"))

    /**
     * Create a package under [parentDir]: dots (and slashes) separate levels, so
     * "com.example.app" becomes com/example/app — matching the IDE "New Package" convention.
     */
    fun createPackage(parentDir: File, rawName: String): File =
        makeDirs(parentDir, splitPackage(rawName))

    /**
     * Copy [source] to a new file. [rawName] is resolved relative to the source's own directory
     * and may contain '/' to place the copy in a subdirectory. Never overwrites an existing file.
     */
    fun copyFile(source: File, rawName: String): File {
        require(source.isFile) { "Only files can be copied" }
        val base = source.parentFile ?: source.absoluteFile.parentFile
        val target = resolve(base, splitPath(rawName, "file name"))
        if (target.exists()) throw IOException("\"${target.name}\" already exists")
        target.parentFile?.mkdirs()
        source.copyTo(target, overwrite = false)
        return target
    }

    private fun makeDirs(parentDir: File, segments: List<String>): File {
        val target = resolve(parentDir, segments)
        if (target.exists()) throw IOException("\"${target.name}\" already exists")
        if (!target.mkdirs()) throw IOException("Could not create \"${target.name}\"")
        return target
    }

    private fun splitPath(rawName: String, what: String): List<String> {
        val name = rawName.trim()
        require(name.isNotEmpty()) { "Enter a $what" }
        return name.split('/')
    }

    private fun splitPackage(rawName: String): List<String> {
        val name = rawName.trim()
        require(name.isNotEmpty()) { "Enter a package name" }
        return name.split('.', '/')
    }

    // Build the target File one segment at a time, rejecting anything that would escape the base
    // directory or produce a degenerate path (empty segments from leading/trailing/doubled
    // separators, "." / ".." traversal, or a Windows separator slipped into a single segment).
    private fun resolve(base: File, segments: List<String>): File {
        require(segments.isNotEmpty()) { "Enter a name" }
        var cur = base
        for (raw in segments) {
            val seg = raw.trim()
            require(seg.isNotEmpty()) { "Name has an empty path segment" }
            require(seg != "." && seg != "..") { "\"$seg\" is not a valid name" }
            require(!seg.contains('\\')) { "\"$seg\" is not a valid name" }
            cur = File(cur, seg)
        }
        return cur
    }
}
