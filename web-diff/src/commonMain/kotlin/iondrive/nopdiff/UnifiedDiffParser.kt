package iondrive.nopdiff

/**
 * Parses unified diff text (the output of `git diff`, `git show`, `diff -u`, etc.) into the
 * structured [DiffFile] model. Handles `diff --git` multi-file streams, plain `---`/`+++` unified
 * diffs, new / deleted / renamed / binary files, and hunk headers with omitted counts.
 *
 * Best-effort and defensive: malformed sections are skipped rather than throwing, so a hostile or
 * truncated diff never crashes the renderer.
 */
object UnifiedDiffParser {

    private val HUNK_HEADER = Regex("""^@@+ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@@*(.*)$""")

    fun parse(text: String): List<DiffFile> {
        val lines = text.split("\n")
        val files = ArrayList<DiffFile>()
        var cur: Builder? = null
        fun flush() { cur?.let { files.add(it.build()) }; cur = null }

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            when {
                line.startsWith("diff --git ") -> {
                    flush()
                    cur = Builder().also { it.applyGitHeader(line) }
                }
                line.startsWith("diff ") && !line.startsWith("diff --git ") -> {
                    // `diff -u a b` style without the git header
                    flush()
                    cur = Builder()
                }
                line.startsWith("--- ") -> {
                    // In a plain (non-`diff --git`) stream, a "--- " after a hunk begins the next
                    // file. With a git header it just restates the path on the fresh builder.
                    if (cur == null || cur!!.hunks.isNotEmpty()) { flush(); cur = Builder() }
                    cur!!.oldPath = stripDiffPath(line.substring(4))
                }
                line.startsWith("+++ ") -> {
                    if (cur == null) cur = Builder()
                    cur!!.newPath = stripDiffPath(line.substring(4))
                }
                line.startsWith("new file") -> cur?.isNew = true
                line.startsWith("deleted file") -> cur?.isDeleted = true
                line.startsWith("rename from ") -> { cur?.isRename = true; cur?.oldPath = line.removePrefix("rename from ") }
                line.startsWith("rename to ") -> { cur?.isRename = true; cur?.newPath = line.removePrefix("rename to ") }
                line.startsWith("copy from ") -> cur?.oldPath = line.removePrefix("copy from ")
                line.startsWith("copy to ") -> cur?.newPath = line.removePrefix("copy to ")
                line.startsWith("Binary files ") || line.startsWith("GIT binary patch") -> cur?.isBinary = true
                HUNK_HEADER.matches(line) -> {
                    if (cur == null) cur = Builder()
                    i = parseHunk(lines, i, cur!!)
                    continue // parseHunk advanced i past the hunk body
                }
                // index lines, mode lines, similarity index, blank separators — ignored
                else -> {}
            }
            i++
        }
        flush()
        return files
    }

    /** Parses one hunk starting at [start] (the `@@` line); returns the index just past its body. */
    private fun parseHunk(lines: List<String>, start: Int, file: Builder): Int {
        val m = HUNK_HEADER.find(lines[start]) ?: return start + 1
        val oldStart = m.groupValues[1].toIntOrNull() ?: 1
        val oldCount = m.groupValues[2].toIntOrNull() ?: 1
        val newStart = m.groupValues[3].toIntOrNull() ?: 1
        val newCount = m.groupValues[4].toIntOrNull() ?: 1

        val body = ArrayList<HunkLine>()
        var oldNo = oldStart
        var newNo = newStart
        var consumedOld = 0
        var consumedNew = 0
        var i = start + 1
        while (i < lines.size) {
            // A well-formed hunk has exactly oldCount/newCount lines. Stop once both are satisfied so
            // we never swallow the following file's "--- " header as a deletion (the classic unified-
            // diff ambiguity, which git resolves with the counts).
            if (consumedOld >= oldCount && consumedNew >= newCount) break
            val l = lines[i]
            if (l.isEmpty()) {
                // git prefixes blank context lines with a space; some tools / hand-pasted diffs drop
                // it. The counts say there's more to come, so treat a bare empty line as blank context.
                body.add(HunkLine(LineType.CONTEXT, "", oldNo, newNo)); oldNo++; newNo++; consumedOld++; consumedNew++
                i++
                continue
            }
            when (l[0]) {
                ' ' -> {
                    body.add(HunkLine(LineType.CONTEXT, l.substring(1), oldNo, newNo)); oldNo++; newNo++; consumedOld++; consumedNew++
                }
                '+' -> {
                    body.add(HunkLine(LineType.ADD, l.substring(1), null, newNo)); newNo++; consumedNew++
                }
                '-' -> {
                    body.add(HunkLine(LineType.DELETE, l.substring(1), oldNo, null)); oldNo++; consumedOld++
                }
                '\\' -> {} // "\ No newline at end of file" — belongs to the previous line; doesn't count
                else -> break // @@, diff --, +++, ---, or anything else ends the hunk
            }
            i++
        }
        file.hunks.add(Hunk(oldStart, oldCount, newStart, newCount, lines[start], body))
        return i
    }

    /** Strip the `a/` or `b/` prefix git adds, drop a trailing tab+timestamp, and unquote. */
    private fun stripDiffPath(raw: String): String {
        var p = raw.substringBefore('\t').trim()
        if (p.startsWith("\"") && p.endsWith("\"") && p.length >= 2) p = p.substring(1, p.length - 1)
        if (p == "/dev/null") return p
        if (p.startsWith("a/") || p.startsWith("b/")) p = p.substring(2)
        return p
    }

    private class Builder {
        var oldPath: String? = null
        var newPath: String? = null
        var isNew = false
        var isDeleted = false
        var isRename = false
        var isBinary = false
        val hunks = ArrayList<Hunk>()

        fun applyGitHeader(line: String) {
            // diff --git a/foo b/foo  →  recover both paths (best effort; quoted/spaced paths vary)
            val rest = line.removePrefix("diff --git ").trim()
            val aIdx = rest.indexOf("a/")
            val bIdx = rest.lastIndexOf(" b/")
            if (aIdx == 0 && bIdx > 0) {
                oldPath = rest.substring(2, bIdx)
                newPath = rest.substring(bIdx + 3)
            }
        }

        fun build() = DiffFile(
            oldPath = oldPath?.takeIf { it != "/dev/null" },
            newPath = newPath?.takeIf { it != "/dev/null" },
            isNew = isNew || oldPath == "/dev/null",
            isDeleted = isDeleted || newPath == "/dev/null",
            isRename = isRename,
            isBinary = isBinary,
            hunks = hunks,
        )
    }
}
