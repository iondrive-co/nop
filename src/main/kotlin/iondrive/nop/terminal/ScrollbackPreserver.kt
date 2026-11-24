package iondrive.nop.terminal

import com.jediterm.terminal.model.TerminalLine
import com.jediterm.terminal.model.TerminalTextBuffer
import com.jediterm.terminal.util.CharUtils

/**
 * Preserves terminal scrollback when an agent CLI compacts a conversation and either wipes
 * scrollback (`ESC[3J]`) or clears the live viewport in place (`ESC[H` + repeated `ESC[2K ESC[1B]`)
 * before painting a shorter post-compaction view.
 *
 * Full redraws and resize reflows (such as Codex and Antigravity reprinting the whole conversation
 * after `ESC[3J]`, or Ink re-rendering the visible screen) are recognised by content overlap with
 * the pre-clear lines and left alone so scrollback is never duplicated.
 */
internal class ScrollbackPreserver {
    private enum class Kind { HISTORY_CLEAR, VIEWPORT_WIPE }

    private data class Snapshot(
        val kind: Kind,
        val lines: List<TerminalLine>,
        val historyCountAtCapture: Int,
        val confirmed: Boolean,
    )

    @Volatile private var pending: Snapshot? = null

    /** True when a full-viewport wipe has been confirmed and awaits reconciliation. */
    val hasConfirmedViewportWipe: Boolean
        get() = pending?.let { it.kind == Kind.VIEWPORT_WIPE && it.confirmed } == true

    /** Called immediately before `ESC[3J]` clears [buffer]'s history on the primary screen. */
    fun beforeHistoryClear(buffer: TerminalTextBuffer) {
        buffer.lock()
        try {
            if (buffer.isUsingAlternateBuffer) return
            // Do not overwrite an earlier non-empty history snapshot if a CLI emits back-to-back clears.
            if (pending?.kind == Kind.HISTORY_CLEAR) return
            val historyCount = buffer.historyLinesCount
            val captured = ArrayList<TerminalLine>(historyCount + buffer.height)
            val history = buffer.historyLinesStorage
            for (i in 0 until history.size) captured.add(history[i].copy())
            appendTrimmedScreen(buffer, captured)
            trimTrailingBlanks(captured)
            if (captured.isEmpty()) return
            pending = Snapshot(
                kind = Kind.HISTORY_CLEAR,
                lines = captured,
                historyCountAtCapture = historyCount,
                confirmed = true,
            )
        } finally {
            buffer.unlock()
        }
    }

    /** Called right before the first `ESC[2K]` following `ESC[H]` erases row 0 of the primary screen. */
    fun beforeViewportWipe(buffer: TerminalTextBuffer) {
        buffer.lock()
        try {
            if (buffer.isUsingAlternateBuffer) return
            // A history clear already captures both history and screen.
            if (pending?.kind == Kind.HISTORY_CLEAR) return
            if (pending?.kind == Kind.VIEWPORT_WIPE && pending?.confirmed == true) return
            val captured = ArrayList<TerminalLine>(buffer.height)
            appendTrimmedScreen(buffer, captured)
            trimTrailingBlanks(captured)
            if (captured.isEmpty()) return
            pending = Snapshot(
                kind = Kind.VIEWPORT_WIPE,
                lines = captured,
                historyCountAtCapture = buffer.historyLinesCount,
                confirmed = false,
            )
        } finally {
            buffer.unlock()
        }
    }

    /** Records consecutive full-line erases from row 0 downward; confirms once most of the viewport is wiped. */
    fun noteViewportWipeRow(buffer: TerminalTextBuffer, erasedRows: Int): Boolean {
        val threshold = minOf(buffer.height, (buffer.height / 2).coerceAtLeast(3))
        if (erasedRows < threshold) return false
        buffer.lock()
        try {
            val current = pending ?: return false
            if (current.kind != Kind.VIEWPORT_WIPE || current.confirmed) return false
            pending = current.copy(confirmed = true)
            return true
        } finally {
            buffer.unlock()
        }
    }

    fun clear() {
        pending = null
    }

    /**
     * Reconciles any captured snapshot against [buffer] once the frame completes.
     *
     * Returns the number of lines appended to the bottom of history (positive only when a
     * confirmed viewport wipe moved pre-compaction screen rows into scrollback), so a reader
     * scrolled up into older history stays anchored on the same rows.
     */
    fun reconcile(buffer: TerminalTextBuffer): Int {
        buffer.lock()
        try {
            val snapshot = pending ?: return 0
            pending = null
            if (!snapshot.confirmed || buffer.isUsingAlternateBuffer) return 0
            return when (snapshot.kind) {
                Kind.HISTORY_CLEAR -> {
                    reconcileHistoryClear(buffer, snapshot)
                    0
                }
                Kind.VIEWPORT_WIPE -> reconcileViewportWipe(buffer, snapshot)
            }
        } finally {
            buffer.unlock()
        }
    }

    private fun reconcileHistoryClear(buffer: TerminalTextBuffer, snapshot: Snapshot) {
        val afterAll = ArrayList<TerminalLine>(buffer.historyLinesCount + buffer.height)
        val history = buffer.historyLinesStorage
        for (i in 0 until history.size) afterAll.add(history[i])
        appendTrimmedScreen(buffer, afterAll)
        trimTrailingBlanks(afterAll)

        val savedSubstantive = logicalSubstantiveLines(snapshot.lines)
        if (savedSubstantive.isEmpty()) return
        val afterSubstantive = logicalSubstantiveLines(afterAll)
        // A tiny startup banner replaced by a larger transcript (e.g. session resume) is not compaction.
        if (savedSubstantive.size < MIN_SPLASH_GUARD_LINES && afterSubstantive.size >= savedSubstantive.size) return

        val afterCorpus = corpusText(afterAll)
        if (earlierContentPreserved(savedSubstantive, afterCorpus)) return

        val toPrepend = trimOverlappingSuffix(snapshot.lines, afterAll)
        if (toPrepend.isEmpty()) return
        toPrepend.last().isWrapped = false

        val beforeSize = history.size
        for (i in toPrepend.lastIndex downTo 0) {
            history.addToTop(toPrepend[i])
        }
        if (history.size != beforeSize) {
            notifyBufferChanged(buffer)
        }
    }

    private fun reconcileViewportWipe(buffer: TerminalTextBuffer, snapshot: Snapshot): Int {
        val history = buffer.historyLinesStorage
        val historyDelta = (history.size - snapshot.historyCountAtCapture).coerceAtLeast(0)
        val afterNew = ArrayList<TerminalLine>(historyDelta + buffer.height)
        for (i in (history.size - historyDelta).coerceAtLeast(0) until history.size) {
            afterNew.add(history[i])
        }
        appendTrimmedScreen(buffer, afterNew)
        trimTrailingBlanks(afterNew)

        val savedSubstantive = logicalSubstantiveLines(snapshot.lines)
        if (savedSubstantive.size < MIN_VIEWPORT_SUBSTANTIVE_LINES) return 0

        val afterCorpus = corpusText(afterNew)
        if (earlierContentPreserved(savedSubstantive, afterCorpus)) return 0

        val existingHistoryTail = ArrayList<TerminalLine>()
        val existingEnd = (history.size - historyDelta).coerceAtLeast(0)
        val existingStart = (existingEnd - snapshot.lines.size).coerceAtLeast(0)
        for (i in existingStart until existingEnd) existingHistoryTail.add(history[i])

        var toAppend = trimOverlappingPrefix(existingHistoryTail, snapshot.lines)
        toAppend = trimOverlappingSuffix(toAppend, afterNew)
        if (toAppend.isEmpty()) return 0
        toAppend.last().isWrapped = false

        // Insert before any rows the post-wipe frame itself pushed into history so order stays chronological.
        val displaced = ArrayList<TerminalLine>(historyDelta)
        repeat(minOf(historyDelta, history.size)) {
            displaced.add(history.removeFromBottom())
        }
        for (line in toAppend) history.addToBottom(line)
        for (i in displaced.lastIndex downTo 0) history.addToBottom(displaced[i])

        notifyBufferChanged(buffer)
        return toAppend.size
    }

    private fun appendTrimmedScreen(buffer: TerminalTextBuffer, target: MutableList<TerminalLine>) {
        val screen = buffer.screenLinesStorage
        val count = minOf(screen.size, buffer.height)
        for (i in 0 until count) target.add(screen[i].copy())
    }

    private fun trimTrailingBlanks(lines: MutableList<TerminalLine>) {
        while (lines.isNotEmpty() && normalize(lines.last()).isEmpty()) {
            lines.removeAt(lines.lastIndex)
        }
    }

    /**
     * Checks whether the earlier (non-chrome) substantive lines of the snapshot still appear in
     * [afterCorpus]. Sampling the first three quarters skips trailing prompt/status bars that
     * reappear unchanged after compaction, while remaining robust across width-change reflows.
     */
    private fun earlierContentPreserved(savedSubstantive: List<String>, afterCorpus: String): Boolean {
        if (afterCorpus.isEmpty()) return false
        // A redraw can retain most of a conversation while dropping everything at its start.
        if (savedSubstantive.take(3).none { matchesCorpus(it, afterCorpus) }) return false
        val limit = if (savedSubstantive.size > 3) (savedSubstantive.size * 3 + 3) / 4 else savedSubstantive.size
        val sampleCount = minOf(limit, MAX_SAMPLE_LINES)
        var matched = 0
        for (s in 0 until sampleCount) {
            val index = if (sampleCount == 1) 0 else (s.toLong() * (limit - 1) / (sampleCount - 1)).toInt()
            val candidate = savedSubstantive[index]
            if (matchesCorpus(candidate, afterCorpus)) matched++
        }
        return matched * 2 >= sampleCount
    }

    private fun matchesCorpus(line: String, afterCorpus: String): Boolean {
        if (line in afterCorpus) return true
        // When a long line wraps differently after a resize, a distinctive prefix still matches.
        if (line.length > PREFIX_MATCH_LENGTH && line.substring(0, PREFIX_MATCH_LENGTH) in afterCorpus) return true
        return false
    }

    /** Joins wrapped physical rows into logical lines and keeps those carrying real text (not rules/prompts). */
    private fun logicalSubstantiveLines(lines: List<TerminalLine>): List<String> {
        val result = ArrayList<String>()
        val current = StringBuilder()
        var wrapped = false
        for (line in lines) {
            val text = rawText(line)
            if (!wrapped) {
                flushLogical(current, result)
                current.append(text)
            } else {
                current.append(text)
            }
            wrapped = line.isWrapped
        }
        flushLogical(current, result)
        return result
    }

    private fun flushLogical(current: StringBuilder, out: MutableList<String>) {
        if (current.isEmpty()) return
        val normalized = collapseWhitespace(current)
        current.setLength(0)
        if (isSubstantive(normalized)) out.add(normalized)
    }

    private fun corpusText(lines: List<TerminalLine>): String {
        val raw = StringBuilder()
        var wrapped = false
        for (line in lines) {
            if (!wrapped && raw.isNotEmpty()) raw.append(' ')
            raw.append(rawText(line))
            wrapped = line.isWrapped
        }
        return collapseWhitespace(raw)
    }

    /** Trims a suffix of [saved] that duplicates the beginning of [after] (e.g. preserved post-compaction tail). */
    private fun trimOverlappingSuffix(saved: List<TerminalLine>, after: List<TerminalLine>): List<TerminalLine> {
        val savedNorm = saved.map(::normalize)
        val afterNorm = after.map(::normalize)
        val firstAfter = afterNorm.indexOfFirst(::isSubstantive)
        if (firstAfter < 0) return saved
        val maxOverlap = minOf(savedNorm.size, afterNorm.size)
        for (overlap in maxOverlap downTo 1) {
            if (overlap <= firstAfter) break
            var matches = true
            var substantiveMatches = 0
            for (i in 0 until overlap) {
                val left = savedNorm[savedNorm.size - overlap + i]
                val right = afterNorm[i]
                if (left != right) {
                    matches = false
                    break
                }
                if (isSubstantive(left)) substantiveMatches++
            }
            if (matches && substantiveMatches > 0) {
                val trimmed = saved.subList(0, saved.size - overlap).toMutableList()
                trimTrailingBlanks(trimmed)
                return trimmed
            }
        }
        return saved
    }

    /** Trims a prefix of [candidate] that already sits at the bottom of [existingHistoryTail]. */
    private fun trimOverlappingPrefix(existingHistoryTail: List<TerminalLine>, candidate: List<TerminalLine>): List<TerminalLine> {
        if (existingHistoryTail.isEmpty() || candidate.isEmpty()) return candidate
        val tailNorm = existingHistoryTail.map(::normalize)
        val candNorm = candidate.map(::normalize)
        val maxOverlap = minOf(tailNorm.size, candNorm.size)
        for (overlap in maxOverlap downTo 1) {
            var matches = true
            var substantiveMatches = 0
            for (i in 0 until overlap) {
                val left = tailNorm[tailNorm.size - overlap + i]
                val right = candNorm[i]
                if (left != right) {
                    matches = false
                    break
                }
                if (isSubstantive(left)) substantiveMatches++
            }
            if (matches && substantiveMatches > 0) {
                return candidate.subList(overlap, candidate.size)
            }
        }
        return candidate
    }

    private fun rawText(line: TerminalLine): String {
        val text = line.text
        if (text.isEmpty()) return ""
        val out = StringBuilder(text.length)
        for (ch in text) {
            when (ch) {
                CharUtils.DWC -> Unit
                '\u0000', '\u00a0' -> out.append(' ')
                else -> out.append(ch)
            }
        }
        return out.toString()
    }

    private fun normalize(line: TerminalLine): String = collapseWhitespace(rawText(line))

    private fun collapseWhitespace(text: CharSequence): String {
        val out = StringBuilder(text.length)
        var space = false
        for (i in 0 until text.length) {
            val ch = text[i]
            if (ch.isWhitespace()) {
                if (out.isNotEmpty()) space = true
            } else {
                if (space) {
                    out.append(' ')
                    space = false
                }
                out.append(ch)
            }
        }
        return out.toString()
    }

    private fun isSubstantive(normalized: String): Boolean {
        var alnum = 0
        for (ch in normalized) {
            if (ch.isLetterOrDigit()) {
                alnum++
                if (alnum >= 3) return true
            }
        }
        return false
    }

    private fun notifyBufferChanged(buffer: TerminalTextBuffer) {
        fireHistoryChanged?.invoke(buffer)
        fireModelChanged?.invoke(buffer)
    }

    private companion object {
        const val MIN_SPLASH_GUARD_LINES = 4
        const val MIN_VIEWPORT_SUBSTANTIVE_LINES = 2
        const val MAX_SAMPLE_LINES = 24
        const val PREFIX_MATCH_LENGTH = 24

        val fireHistoryChanged = runCatching {
            TerminalTextBuffer::class.java.getDeclaredMethod("fireHistoryBufferLineCountChanged").apply { isAccessible = true }
        }.getOrNull()

        val fireModelChanged = runCatching {
            TerminalTextBuffer::class.java.getDeclaredMethod("fireModelChangeEvent").apply { isAccessible = true }
        }.getOrNull()
    }
}
