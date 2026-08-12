package iondrive.nop.spell

/**
 * Word-level spellcheck over the prose parts of a file — comments and string literals in code,
 * everything but the code spans in markdown and plain text. Callers decide which ranges count as
 * prose (see spellcheckRegions in the ui package) and pass them in; this file is only concerned
 * with finding the words inside them and looking each one up.
 *
 * The bar for reporting a word is deliberately high. An editor that underlines a handful of real
 * typos is useful; one that underlines every abbreviation, identifier and file path in a comment
 * gets switched off within a minute, so anything that isn't clearly a word of English is left
 * alone rather than guessed at.
 */

/** A word that isn't in the dictionary. [range] is inclusive at both ends, as the editor's other
 *  text ranges are (find matches, the Ctrl-hover underline). */
data class Typo(val range: IntRange, val word: String)

/** Words shorter than this are never reported: at three letters and under, English words, jargon
 *  and abbreviations (`arg`, `dst`, `cfg`, `nop`) are impossible to tell apart, and abbreviations
 *  win by volume in source code. */
const val MIN_TYPO_LENGTH = 4

/** Ceiling on reported typos, so a file of prose in a language the dictionary doesn't cover costs
 *  a bounded list and a bounded amount of drawing rather than one entry per word. */
private const val MAX_TYPOS = 5000

/**
 * Finds the misspelled words of [text] within [regions] (inclusive ranges, in ascending order).
 *
 * [knows] is the dictionary lookup, injected so tests can spell out a tiny vocabulary instead of
 * depending on the bundled 90k-word list.
 */
fun findTypos(
    text: String,
    regions: List<IntRange>,
    knows: (String) -> Boolean = Dictionary::knows,
): List<Typo> {
    val out = mutableListOf<Typo>()
    for (region in regions) {
        val start = region.first.coerceIn(0, text.length)
        val end = (region.last + 1).coerceIn(start, text.length)
        var i = start
        while (i < end) {
            // Chunk = a run of non-whitespace. Skipping is decided per chunk, because what makes a
            // URL or a qualified name unspellcheckable is the punctuation *around* its words.
            if (text[i].isWhitespace()) {
                i++
                continue
            }
            var chunkEnd = i
            while (chunkEnd < end && !text[chunkEnd].isWhitespace()) chunkEnd++
            if (!isCodeLike(text, i, chunkEnd)) {
                scanChunk(text, i, chunkEnd, knows, out)
                if (out.size >= MAX_TYPOS) return out
            }
            i = chunkEnd
        }
    }
    return out
}

/**
 * Whether the chunk at [start], [endExclusive] is machinery rather than prose: a URL, an email
 * address, a path, or a qualified name like `kotlinx.coroutines`. All four are made of real words
 * glued to fragments that aren't, and none of them is something the user wants to be told about.
 *
 * A `.` only disqualifies the chunk when a letter or digit follows it, so a sentence's full stop —
 * and a trailing "e.g." — behave differently on purpose: `e.g.` is abbreviation punctuation and is
 * skipped, `end.` is a sentence ending and is checked.
 */
private fun isCodeLike(text: String, start: Int, endExclusive: Int): Boolean {
    for (i in start until endExclusive) {
        when (text[i]) {
            '/', '\\', '@' -> return true
            '.' -> if (i + 1 < endExclusive && text[i + 1].isLetterOrDigit()) return true
            else -> {}
        }
    }
    return false
}

/** Reports the misspellings in one chunk, which is known to be prose-ish by this point. */
private fun scanChunk(
    text: String,
    start: Int,
    endExclusive: Int,
    knows: (String) -> Boolean,
    out: MutableList<Typo>,
) {
    var i = start
    while (i < endExclusive) {
        if (!isWordStart(text[i])) {
            i++
            continue
        }
        var wordEnd = i
        while (wordEnd < endExclusive && isWordChar(text, wordEnd, endExclusive)) wordEnd++
        // A word run in the middle of digits is part of a token, not a word: `utf8`, `sha256`,
        // `h1`. Checking the letters alone would report the half the author never wrote as prose.
        val digitBefore = i > start && text[i - 1].isDigit()
        val digitAfter = wordEnd < endExclusive && text[wordEnd].isDigit()
        if (!digitBefore && !digitAfter) {
            for (part in splitIdentifier(text, i, wordEnd)) {
                if (out.size >= MAX_TYPOS) return
                val word = text.substring(part.first, part.last + 1)
                if (isCheckable(word) && !knows(word)) out += Typo(part, word)
            }
        }
        i = wordEnd
    }
}

private fun isWordStart(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z' || (c.isLetter() && c.code > 127)

/** An apostrophe continues a word ("doesn't") only when it sits between two letters — a quoted
 *  'word' should not swallow its quotes. */
private fun isWordChar(text: String, i: Int, endExclusive: Int): Boolean {
    val c = text[i]
    if (isWordStart(c)) return true
    if (c != '\'' && c != '’') return false
    return i + 1 < endExclusive && isWordStart(text[i + 1])
}

/**
 * Splits a word run into the pieces a reader would check separately, so a typo inside an
 * identifier is still found: `getUsreName` splits as `get` + `Usre` + `Name` and reports the
 * middle one. An all-uppercase run gives its last capital back to the word that follows, so
 * `HTTPServer` splits as `HTTP` + `Server` rather than `HTTPS` + `erver`.
 *
 * Returns inclusive ranges into [text], in order.
 */
private fun splitIdentifier(text: String, start: Int, endExclusive: Int): List<IntRange> {
    val parts = mutableListOf<IntRange>()
    var i = start
    while (i < endExclusive) {
        val partStart = i
        i++
        if (text[partStart].isUpperCase() && i < endExclusive && text[i].isUpperCase()) {
            while (i < endExclusive && text[i].isUpperCase()) i++
            // The uppercase run ran into a lowercase tail: that last capital starts the next word.
            if (i < endExclusive && i - partStart > 1) i--
        } else {
            while (i < endExclusive && !text[i].isUpperCase()) i++
        }
        parts += partStart..(i - 1)
    }
    return parts
}

/**
 * Whether a word is worth looking up at all. Beyond the length floor this drops two classes that
 * are never English: acronyms (`JSON`, `TODO` — all-caps, and the dictionary has no way to tell a
 * misspelled one from a real one), and anything outside ASCII, since the bundled list is English
 * and squiggling every word of a Japanese or Greek comment would be pure noise.
 */
internal fun isCheckable(word: String): Boolean {
    if (word.length < MIN_TYPO_LENGTH) return false
    var hasLower = false
    for (c in word) {
        when {
            c in 'a'..'z' -> hasLower = true
            c in 'A'..'Z' -> {}
            c == '\'' || c == '’' -> {}
            else -> return false
        }
    }
    return hasLower
}
