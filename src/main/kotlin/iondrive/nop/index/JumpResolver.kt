package iondrive.nop.index

import iondrive.nop.lang.JavaHeader
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
     * Extracts the receiver token preceding a word at [wordStart], e.g. "entrySearchMeta" in
     * "entrySearchMeta.initialise(...)". Returns null when there is no dot or no receiver.
     */
    fun receiverAt(text: String, wordStart: Int): String? {
        var idx = wordStart - 1
        while (idx >= 0 && text[idx].isWhitespace()) idx--
        if (idx < 0 || text[idx] != '.') return null
        idx--
        while (idx >= 0 && text[idx].isWhitespace()) idx--
        if (idx < 0 || !isWordChar(text[idx])) return null
        val end = idx + 1
        while (idx > 0 && isWordChar(text[idx - 1])) idx--
        return text.substring(idx, end)
    }

    /**
     * Resolves what to jump to from a Ctrl-click at [offset]: first a symbol definition (via the
     * [index]), then — when [fileIndex] is supplied and the symbol lookup misses — a file referenced
     * by a path or dotted FQN under the cursor. Returns null when nothing matches.
     *
     * Local definitions in [currentFile] take precedence when called or referenced elsewhere in the
     * file. If multiple external candidates share a name, ranking considers explicit imports, receiver
     * match, production vs test, and subproject proximity.
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
        val wordRange = wordRangeAt(text, offset)
        // A `name.ext` reference (e.g. `import_playbook: site.yml`) names a file, not a bare symbol.
        // Resolve it as a path first so it lands on that file rather than a same-named role/symbol.
        val isFilenameRef = wordRange != null &&
            wordRange.last + 1 < text.length && text[wordRange.last + 1] == '.'
        if (isFilenameRef && fileIndex != null) {
            resolveFile(fileIndex, projectRoot, text, offset)?.let { return it }
        }
        if (word != null) {
            val candidates = index.lookup(word)
            if (candidates.isNotEmpty()) {
                val cursorLine = if (offset in 0..text.length) {
                    var count = 1
                    for (i in 0 until offset.coerceAtMost(text.length)) {
                        if (text[i] == '\n') count++
                    }
                    count
                } else null

                val curAbs = currentFile?.toPath()?.toAbsolutePath()?.normalize()
                val curRel = currentFile?.let {
                    runCatching {
                        projectRoot.toPath().toAbsolutePath().normalize().relativize(curAbs).toString()
                    }.getOrNull()?.replace(File.separatorChar, '/')
                }

                // 1. If the symbol is defined in currentFile at a line different from where the cursor
                // is sitting, this is a call or reference to a local definition. Jump to it directly.
                if (curAbs != null) {
                    val localCandidates = candidates.filter {
                        projectRoot.toPath().resolve(it.file).toAbsolutePath().normalize() == curAbs
                    }
                    val localTarget = if (cursorLine != null) {
                        localCandidates.firstOrNull { it.line != cursorLine }
                    } else {
                        localCandidates.firstOrNull()
                    }
                    if (localTarget != null) {
                        return JumpTarget(File(projectRoot, localTarget.file), localTarget.line)
                    }
                }

                // 2. Rank candidates:
                // - Ansible role over playbook in roles list
                // - Receiver match (e.g. entrySearchMeta.initialise matching EntrySearchMeta owner)
                // - Explicit single-type import in current file
                // - Same package / wildcard import in current file
                // - Production code over test code
                // - Same module / directory proximity (shared path prefix segments)
                val receiver = if (wordRange != null) receiverAt(text, wordRange.first) else null
                val javaHeader = if (currentFile?.name?.endsWith(".java") == true) JavaHeader.of(text) else null
                val isCurTest = curRel?.let { it.contains("/test/") || it.contains("Test.") } ?: false

                val scored = candidates.map { c ->
                    var score = 0
                    val isLocalSelf = curAbs != null &&
                        projectRoot.toPath().resolve(c.file).toAbsolutePath().normalize() == curAbs &&
                        cursorLine == c.line
                    if (isLocalSelf) {
                        // Clicking on the definition itself: penalize so external implementations can be reached
                        score -= 2000
                    }

                    if (!isFilenameRef && c.kind == SymbolKind.ANSIBLE_ROLE) {
                        score += 300
                    }

                    val isCandTest = c.file.contains("/test/") || c.file.contains("Test.")
                    if (!isCurTest && isCandTest) {
                        score -= 500
                    }

                    if (receiver != null) {
                        val ownerClass = c.owner.substringAfterLast('.')
                        val fileBase = c.file.substringAfterLast('/').substringBeforeLast('.')
                        if (ownerClass.equals(receiver, ignoreCase = true) || fileBase.equals(receiver, ignoreCase = true)) {
                            score += 600
                        }
                    }

                    if (javaHeader != null) {
                        val candFqn = if (c.kind == SymbolKind.JAVA_TYPE) {
                            if (c.owner.isEmpty()) c.name else "${c.owner}.${c.name}"
                        } else {
                            c.owner
                        }
                        if (javaHeader.imports.contains(candFqn) || javaHeader.imports.any { it == c.owner }) {
                            score += 400
                        } else if (c.owner.isNotEmpty() && c.owner.substringBeforeLast('.', "") == javaHeader.packageName) {
                            score += 250
                        } else if (c.owner.isNotEmpty() && c.owner.substringBeforeLast('.', "") in javaHeader.wildcardPackages) {
                            score += 150
                        }
                    }

                    if (curRel != null) {
                        val sharedSegments = commonPathSegments(curRel, c.file)
                        score += sharedSegments * 20
                    }

                    c to score
                }

                val pick = scored.maxByOrNull { it.second }?.first ?: candidates.first()
                return JumpTarget(File(projectRoot, pick.file), pick.line)
            }
        }
        if (fileIndex != null) {
            resolveFile(fileIndex, projectRoot, text, offset)?.let { return it }
        }
        return null
    }

    private fun commonPathSegments(path1: String, path2: String): Int {
        val segs1 = path1.split('/')
        val segs2 = path2.split('/')
        var count = 0
        for (i in 0 until minOf(segs1.size - 1, segs2.size - 1)) {
            if (segs1[i] == segs2[i]) count++ else break
        }
        return count
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
