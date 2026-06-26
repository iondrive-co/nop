package iondrive.nop.index

import java.io.File

data class JumpTarget(val file: File, val line: Int)

object JumpResolver {
    private fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '_' || c == '-'
    // Path tokens add '.' and '/' so "src/demo/App.kt" and a dotted FQN "iondrive.nop.ui.App"
    // round-trip as a single clickable token.
    private fun isPathChar(c: Char) = isWordChar(c) || c == '.' || c == '/'

    /**
     * Returns the word straddling [offset] in [text], or null when the cursor isn't on a word.
     * Word chars are letters, digits, underscore, and hyphen — hyphens appear in Ansible role
     * names and template filenames (`adn-deploy-tool`) and we want those to round-trip.
     */
    fun wordAt(text: String, offset: Int): String? {
        val range = wordRangeAt(text, offset) ?: return null
        return text.substring(range.first, range.last + 1)
    }

    /** Half-open-style range covering the word under [offset], or null when on no word. */
    fun wordRangeAt(text: String, offset: Int): IntRange? {
        if (offset < 0 || offset > text.length) return null
        var start = offset
        while (start > 0 && isWordChar(text[start - 1])) start--
        var end = offset
        while (end < text.length && isWordChar(text[end])) end++
        if (start == end) return null
        return start..(end - 1)
    }

    /** Inclusive range of the path-like token under [offset] (word chars plus '.' and '/'), or null. */
    fun pathRangeAt(text: String, offset: Int): IntRange? {
        if (offset < 0 || offset > text.length) return null
        var start = offset
        while (start > 0 && isPathChar(text[start - 1])) start--
        var end = offset
        while (end < text.length && isPathChar(text[end])) end++
        if (start == end) return null
        return start..(end - 1)
    }

    /**
     * Resolves what to jump to from a Ctrl-click at [offset]: first a symbol definition (via the
     * [index]), then — when [fileIndex] is supplied and the symbol lookup misses — a file referenced
     * by a path or dotted FQN under the cursor. Returns null when nothing matches.
     *
     * If multiple symbol entries share a name, an entry that isn't [currentFile] wins so
     * Ctrl-clicking a self-reference doesn't no-op.
     */
    fun resolve(
        index: SymbolIndex,
        projectRoot: File,
        currentFile: File?,
        text: String,
        offset: Int,
        fileIndex: FileIndex? = null,
    ): JumpTarget? {
        val word = wordAt(text, offset)
        if (word != null) {
            val candidates = index.lookup(word)
            if (candidates.isNotEmpty()) {
                val curAbs = currentFile?.toPath()?.toAbsolutePath()?.normalize()
                val pick = candidates.firstOrNull {
                    val abs = projectRoot.toPath().resolve(it.file).toAbsolutePath().normalize()
                    curAbs == null || abs != curAbs
                } ?: candidates.first()
                return JumpTarget(File(projectRoot, pick.file), pick.line)
            }
        }
        if (fileIndex != null) {
            resolveFile(fileIndex, projectRoot, text, offset)?.let { return it }
        }
        return null
    }

    /**
     * Resolves a path/filename reference under [offset] to a project file (line 1). Only attempts a
     * match when the token actually looks path-like (contains '/' or '.'), so an ordinary identifier
     * that happens to share a filename's stem doesn't false-jump. Tries, in order: exact relative
     * path, path-suffix on a segment boundary, basename, then a dotted FQN mapped to a path.
     */
    fun resolveFile(
        fileIndex: FileIndex,
        projectRoot: File,
        text: String,
        offset: Int,
    ): JumpTarget? {
        val range = pathRangeAt(text, offset) ?: return null
        // Strip a leading "./" and any trailing sentence dot; keep a leading dot so dotfiles
        // (.gitignore) still resolve.
        val token = text.substring(range.first, range.last + 1).removePrefix("./").trimEnd('.')
        if (token.isEmpty()) return null
        if (!token.contains('/') && !token.contains('.')) return null

        val files = fileIndex.files
        fun hit(path: String) = JumpTarget(File(projectRoot, path), 1)

        files.firstOrNull { it == token }?.let { return hit(it) }
        if (token.contains('/')) {
            files.firstOrNull { it.endsWith("/$token") }?.let { return hit(it) }
        }
        if (!token.contains('/') && token.contains('.')) {
            files.firstOrNull { it.substringAfterLast('/') == token }?.let { return hit(it) }
        }
        // Dotted FQN (iondrive.nop.ui.App) → a path, matched ignoring the file's extension.
        if (!token.contains('/')) {
            val asPath = token.replace('.', '/')
            files.firstOrNull {
                val noExt = it.substringBeforeLast('.')
                noExt == asPath || noExt.endsWith("/$asPath")
            }?.let { return hit(it) }
        }
        return null
    }
}
