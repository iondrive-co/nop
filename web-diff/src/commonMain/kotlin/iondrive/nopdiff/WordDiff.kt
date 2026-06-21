package iondrive.nopdiff

/**
 * Token-level diff of two single lines, producing the inline highlight spans nop's desktop view
 * gets from java-diff-utils. We tokenise each side into word / whitespace / punctuation runs, take
 * the longest common subsequence, and mark the rest as changed. Output spans cover each line
 * contiguously and in order (matching [InlineSpan]'s contract).
 */
object WordDiff {
    // Guard against pathological O(n*m) on very long lines (e.g. minified JS). Above this product
    // of token counts we skip the alignment and mark the whole line changed.
    private const val MAX_PRODUCT = 40_000

    /** Returns (oldSpans, newSpans). */
    fun spans(old: String, new: String): Pair<List<InlineSpan>, List<InlineSpan>> {
        if (old == new) return wholeLine(old, changed = false) to wholeLine(new, changed = false)
        val o = tokenize(old)
        val n = tokenize(new)
        if (o.isEmpty()) return emptyList<InlineSpan>() to wholeLine(new, changed = true)
        if (n.isEmpty()) return wholeLine(old, changed = true) to emptyList()

        if (o.size.toLong() * n.size.toLong() > MAX_PRODUCT) {
            return wholeLine(old, changed = true) to wholeLine(new, changed = true)
        }

        val (oCommon, nCommon) = lcsCommon(o, n)
        return buildSpans(o, oCommon) to buildSpans(n, nCommon)
    }

    private fun wholeLine(text: String, changed: Boolean): List<InlineSpan> =
        if (text.isEmpty()) emptyList() else listOf(InlineSpan(0, text.length, changed))

    /** Split into runs of [word chars] | [spaces/tabs] | single other char. */
    private fun tokenize(s: String): List<String> {
        val toks = ArrayList<String>()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c.isLetterOrDigit() || c == '_' -> {
                    val start = i
                    while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_')) i++
                    toks.add(s.substring(start, i))
                }
                c == ' ' || c == '\t' -> {
                    val start = i
                    while (i < s.length && (s[i] == ' ' || s[i] == '\t')) i++
                    toks.add(s.substring(start, i))
                }
                else -> {
                    toks.add(c.toString())
                    i++
                }
            }
        }
        return toks
    }

    /** Standard LCS DP + backtrack. Returns per-token "is common (unchanged)" flags for each side. */
    private fun lcsCommon(o: List<String>, n: List<String>): Pair<BooleanArray, BooleanArray> {
        val rows = o.size + 1
        val cols = n.size + 1
        // dp[i][j] = LCS length of o[i:] and n[j:]
        val dp = Array(rows) { IntArray(cols) }
        for (i in o.size - 1 downTo 0) {
            for (j in n.size - 1 downTo 0) {
                dp[i][j] = if (o[i] == n[j]) dp[i + 1][j + 1] + 1
                else maxOf(dp[i + 1][j], dp[i][j + 1])
            }
        }
        val oCommon = BooleanArray(o.size)
        val nCommon = BooleanArray(n.size)
        var i = 0
        var j = 0
        while (i < o.size && j < n.size) {
            when {
                o[i] == n[j] -> { oCommon[i] = true; nCommon[j] = true; i++; j++ }
                dp[i + 1][j] >= dp[i][j + 1] -> i++
                else -> j++
            }
        }
        return oCommon to nCommon
    }

    /** Coalesce consecutive tokens with the same changed-flag into contiguous char-offset spans. */
    private fun buildSpans(tokens: List<String>, common: BooleanArray): List<InlineSpan> {
        val spans = ArrayList<InlineSpan>()
        var offset = 0
        var spanStart = 0
        var spanChanged = tokens.isNotEmpty() && !common[0]
        for (idx in tokens.indices) {
            val changed = !common[idx]
            if (changed != spanChanged) {
                if (offset > spanStart) spans.add(InlineSpan(spanStart, offset, spanChanged))
                spanStart = offset
                spanChanged = changed
            }
            offset += tokens[idx].length
        }
        if (offset > spanStart) spans.add(InlineSpan(spanStart, offset, spanChanged))
        return spans
    }
}
