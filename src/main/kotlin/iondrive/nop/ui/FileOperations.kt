package iondrive.nop.ui

import java.io.File
import java.io.IOException

/**
 * Filesystem mutations behind the project-tree context menu: new file, new directory, new
 * package, copy-file-to-new, rename, drag-to-move, and clipboard paste. Kept free of Compose so the
 * path-resolution and validation rules can be unit-tested directly.
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

    /**
     * Copy [source] (a file or a whole directory) into [targetDir] — the paste half of the
     * project tree's Ctrl+C / Ctrl+V. Never overwrites: a name already taken at the destination
     * gets a " (copy)" suffix, which is the usual case since pasting into the source's own
     * directory is how you duplicate an entry. Refuses to copy a directory into its own subtree,
     * which would recurse forever.
     */
    fun copyInto(source: File, targetDir: File): File {
        val resolvedSource = source.absoluteFile
        val resolvedTargetDir = targetDir.absoluteFile
        require(resolvedSource.exists()) { "\"${resolvedSource.name}\" no longer exists" }
        require(resolvedTargetDir.isDirectory) { "\"${resolvedTargetDir.name}\" is not a directory" }
        require(!isSelfOrDescendant(resolvedTargetDir, resolvedSource)) {
            "Cannot copy \"${resolvedSource.name}\" into itself"
        }
        val dest = File(resolvedTargetDir, copyName(resolvedSource.name) { File(resolvedTargetDir, it).exists() })
        if (resolvedSource.isDirectory) resolvedSource.copyRecursively(dest) else resolvedSource.copyTo(dest)
        return dest
    }

    /**
     * The name a pasted copy of [name] should take, given [taken] — whether a name is already
     * used at the destination. Free when nothing collides (pasting elsewhere keeps the original
     * name); otherwise " (copy)" goes before the extension ("Main.kt" → "Main (copy).kt"), and
     * repeated pastes number upwards ("Main (copy 2).kt"). A leading dot is part of the name, not
     * an extension, so ".gitignore" becomes ".gitignore (copy)".
     */
    internal fun copyName(name: String, taken: (String) -> Boolean): String {
        if (!taken(name)) return name
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var candidate = "$stem (copy)$ext"
        var n = 2
        while (taken(candidate)) {
            candidate = "$stem (copy $n)$ext"
            n++
        }
        return candidate
    }

    /**
     * Rename [target] in place, keeping it in its own directory — the F2 / "Rename…" action.
     * Unlike the create actions this is a pure rename, so separators are rejected rather than
     * treated as a move. Never overwrites a sibling, but does allow a change of case only
     * ("readme.md" → "README.md"), which on a case-insensitive filesystem looks like a collision
     * with the file itself.
     */
    fun rename(target: File, rawName: String): File {
        val source = target.absoluteFile
        require(source.exists()) { "\"${source.name}\" no longer exists" }
        val parent = source.parentFile ?: throw IOException("\"${source.name}\" has nowhere to be renamed")
        val name = rawName.trim()
        require(name.isNotEmpty()) { "Enter a name" }
        require(name != "." && name != "..") { "\"$name\" is not a valid name" }
        require(!name.contains('/') && !name.contains('\\')) {
            "A name cannot contain a path separator — drag the row to move it instead"
        }
        val dest = File(parent, name)
        if (dest.path == source.path) return source
        // equals-ignoring-case means dest *is* source on a case-insensitive filesystem, which is
        // the one existing entry a rename is allowed to land on.
        if (dest.exists() && !dest.path.equals(source.path, ignoreCase = true)) {
            throw IOException("\"$name\" already exists")
        }
        if (!source.renameTo(dest)) throw IOException("Could not rename \"${source.name}\"")
        return dest
    }

    /**
     * Where [file] ends up when [oldRoot] is renamed to [newRoot] — [newRoot] itself for the
     * renamed entry, the same relative position beneath it for anything nested inside a renamed
     * directory, and null for a path that wasn't affected. Lets the caller carry open tabs across
     * a rename instead of closing them.
     */
    internal fun remapPath(file: File, oldRoot: File, newRoot: File): File? {
        val path = file.absolutePath
        val old = oldRoot.absolutePath
        return when {
            path == old -> newRoot.absoluteFile
            path.startsWith(old + File.separator) ->
                File(newRoot.absoluteFile, path.substring(old.length + 1))
            else -> null
        }
    }

    /**
     * Move [source] into [targetDir], keeping its name — the filesystem side of a project-tree
     * drag-and-drop. Refuses to move a directory into itself or one of its own descendants, and
     * never overwrites an existing entry at the destination. Dropping back onto the item's own
     * current parent is a no-op that returns [source] unchanged.
     */
    fun moveFile(source: File, targetDir: File): File {
        val resolvedSource = source.absoluteFile
        val resolvedTargetDir = targetDir.absoluteFile
        require(resolvedTargetDir.isDirectory) { "\"${resolvedTargetDir.name}\" is not a directory" }
        require(!isSelfOrDescendant(resolvedTargetDir, resolvedSource)) {
            "Cannot move \"${resolvedSource.name}\" into itself"
        }
        val dest = File(resolvedTargetDir, resolvedSource.name)
        if (dest.path == resolvedSource.path) return resolvedSource
        if (dest.exists()) throw IOException("\"${dest.name}\" already exists in ${resolvedTargetDir.name}")
        if (!resolvedSource.renameTo(dest)) {
            // renameTo can refuse a cross-filesystem move; fall back to copy-then-delete.
            if (resolvedSource.isDirectory) resolvedSource.copyRecursively(dest) else resolvedSource.copyTo(dest)
            if (!resolvedSource.deleteRecursively()) throw IOException("Could not move \"${resolvedSource.name}\"")
        }
        return dest
    }

    /**
     * True when [candidate] is [ancestor] itself or nested inside it. Names both the destinations
     * that would move or copy a directory into its own subtree, and the files a rename of
     * [ancestor] carries with it.
     */
    internal fun isSelfOrDescendant(candidate: File, ancestor: File): Boolean {
        var cur: File? = candidate.absoluteFile
        val root = ancestor.absolutePath
        while (cur != null) {
            if (cur.path == root) return true
            cur = cur.parentFile
        }
        return false
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
