package iondrive.nop.ui

import iondrive.nop.spell.Typo
import iondrive.nop.spell.findTypos

/**
 * Which parts of a file the spellchecker is allowed to look at.
 *
 * Prose files are checked all over, minus the bits the markdown tokenizer has already identified as
 * not being prose — fenced and inline code, and link targets. Everything else is source code, where
 * only comments and string literals are English and the identifiers between them are not.
 *
 * The answer is derived from the syntax tokens the editor has already computed for highlighting, so
 * spellcheck costs a walk of that list rather than a second parse of the file.
 */

/** Files that are prose from top to bottom. Anything else with a tokenizer is treated as code. */
private val PROSE_EXTENSIONS = setOf("md", "markdown", "txt", "text", "rst", "adoc", "asciidoc")

/**
 * Formats where a string literal is data rather than a sentence. JSON is the whole of this list:
 * every one of its strings is a key or a value, and a config file full of ids, hostnames and enum
 * names would be underlined end to end.
 */
private val DATA_EXTENSIONS = setOf("json")

/**
 * Spellcheckable ranges of [text] (inclusive, ascending, non-overlapping), given the highlighting
 * [tokens] for it. An extension with neither prose nor a tokenizer — `.csv`, `.log`, `.bin` — gets
 * nothing: without a grammar there's no way to tell its words from its data.
 */
fun spellcheckRegions(ext: String?, text: String, tokens: List<Token>): List<IntRange> {
    if (text.isEmpty()) return emptyList()
    val extension = ext?.lowercase()
    if (extension in PROSE_EXTENSIONS) {
        val code = tokens
            .filter { it.kind == TokenKind.STRING || it.kind == TokenKind.LITERAL }
            .map { it.start..(it.endExclusive - 1) }
        return complement(code, text.length)
    }
    if (tokenizerForExtension(extension) == null) return emptyList()
    val strings = extension !in DATA_EXTENSIONS
    return tokens
        .filter { it.kind == TokenKind.COMMENT || (strings && it.kind == TokenKind.STRING) }
        .filter { it.endExclusive > it.start }
        .sortedBy { it.start }
        .map { it.start..(it.endExclusive - 1) }
}

/**
 * Typos across [lines] joined by newlines — the shape a diff shows text in, where a "file" is a
 * handful of lines pulled out of one (or two) revisions and rendered as its own paragraph. Ranges
 * are offsets into that joined text, so a caller that laid the same lines out with `\n` between them
 * can underline them directly.
 *
 * Each line is tokenized on its own, matching how a diff colours its text: a block comment or a
 * fenced code block that opened on a line the diff isn't showing can't be known about, so its lines
 * read as ordinary prose. That is the same approximation the highlighting already makes, and it errs
 * towards checking a little more than it should rather than silently checking nothing.
 */
internal fun typosInLines(
    lines: List<String>,
    ext: String?,
    tokenize: ((String) -> List<Token>)?,
): List<Typo> {
    if (lines.isEmpty()) return emptyList()
    val out = mutableListOf<Typo>()
    var offset = 0
    for (line in lines) {
        if (line.isNotEmpty()) {
            val tokens = tokenize?.invoke(line) ?: emptyList()
            val regions = spellcheckRegions(ext, line, tokens)
            if (regions.isNotEmpty()) {
                for (typo in findTypos(line, regions)) {
                    out += typo.copy(range = (typo.range.first + offset)..(typo.range.last + offset))
                }
            }
        }
        offset += line.length + 1 // + the newline that joins it to the next
    }
    return out
}

/** The ranges of `0 until length` that [excluded] doesn't cover. [excluded] may arrive unsorted or
 *  overlapping — the markdown tokenizer promises neither for the subset we pick out of it. */
private fun complement(excluded: List<IntRange>, length: Int): List<IntRange> {
    if (excluded.isEmpty()) return listOf(0..(length - 1))
    val out = mutableListOf<IntRange>()
    var cursor = 0
    for (range in excluded.sortedBy { it.first }) {
        val start = range.first.coerceIn(0, length)
        val end = (range.last + 1).coerceIn(start, length)
        if (start > cursor) out += cursor..(start - 1)
        if (end > cursor) cursor = end
    }
    if (cursor < length) out += cursor..(length - 1)
    return out
}
