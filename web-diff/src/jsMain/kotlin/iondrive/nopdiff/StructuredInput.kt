package iondrive.nopdiff

/**
 * Adapts an already-parsed structured diff (a plain JS array) into [DiffFile]s, so a host that
 * already has parsed diff data — e.g. chad's `FileDiff[]` from its backend — can feed it straight
 * in without re-serialising to unified-diff text.
 *
 * Expected per-file shape (snake_case, matching chad; missing fields tolerated):
 * ```
 * { old_path, new_path, is_new, is_deleted, is_rename, is_binary,
 *   hunks: [ { old_start, old_count, new_start, new_count,
 *              lines: [ { type: "context"|"add"|"delete", content, old_line, new_line } ] } ] }
 * ```
 * Lines of any other type (e.g. chad's "header") are ignored — hunk headers are synthesised.
 */
object StructuredInput {

    fun fromDynamic(filesJs: dynamic): List<DiffFile> {
        val out = ArrayList<DiffFile>()
        val n = len(filesJs) ?: return out
        for (i in 0 until n) out.add(fileFrom(filesJs[i]))
        return out
    }

    private fun fileFrom(f: dynamic): DiffFile {
        val hunks = ArrayList<Hunk>()
        val hs = f.hunks
        len(hs)?.let { hn -> for (i in 0 until hn) hunks.add(hunkFrom(hs[i])) }
        return DiffFile(
            oldPath = str(f.old_path),
            newPath = str(f.new_path),
            isNew = bool(f.is_new),
            isDeleted = bool(f.is_deleted),
            isRename = bool(f.is_rename),
            isBinary = bool(f.is_binary),
            hunks = hunks,
        )
    }

    private fun hunkFrom(h: dynamic): Hunk {
        val os = int(h.old_start, 1)
        val oc = int(h.old_count, 0)
        val ns = int(h.new_start, 1)
        val nc = int(h.new_count, 0)
        val lines = ArrayList<HunkLine>()
        val ls = h.lines
        len(ls)?.let { ln ->
            for (i in 0 until ln) {
                val l = ls[i]
                val type = when (str(l.type)) {
                    "add" -> LineType.ADD
                    "delete" -> LineType.DELETE
                    "context" -> LineType.CONTEXT
                    else -> null // "header" / unknown — skip
                } ?: continue
                lines.add(HunkLine(type, str(l.content) ?: "", intOrNull(l.old_line), intOrNull(l.new_line)))
            }
        }
        return Hunk(os, oc, ns, nc, "@@ -$os,$oc +$ns,$nc @@", lines)
    }

    // ---- defensive dynamic readers ----

    private fun len(v: dynamic): Int? {
        if (v == null || v == undefined) return null
        val l = v.length
        return if (l == null || l == undefined) null else (l as? Int) ?: (l as? Double)?.toInt()
    }

    private fun str(v: dynamic): String? =
        if (v == null || v == undefined) null else v.toString()

    private fun bool(v: dynamic): Boolean = v == true

    private fun int(v: dynamic, default: Int): Int = intOrNull(v) ?: default

    private fun intOrNull(v: dynamic): Int? {
        if (v == null || v == undefined) return null
        (v as? Int)?.let { return it }
        (v as? Double)?.let { return it.toInt() }
        return null
    }
}
