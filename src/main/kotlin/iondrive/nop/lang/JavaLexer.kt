package iondrive.nop.lang

/**
 * A single lexical pass over Java source that yields the offsets of every identifier token.
 *
 * This is the primitive underneath the exact positions the rest of the Java support needs. The
 * parse tree says what a declaration *is* — a method, a private field, the third nested class — but
 * javac's public API gives positions for whole nodes, not for the identifier inside them: the start
 * of a method declaration is the start of its annotations and modifiers, not of its name. Renaming
 * or underlining a name needs the name's own range, and so does deciding whether an occurrence of a
 * word is a real reference or just the same letters inside a comment.
 *
 * So: the tree supplies structure, this supplies character positions, and everything in
 * [JavaSymbols], [JavaUsages] and [JavaRename] is those two joined. Comments, string and text-block
 * literals, character literals and numbers are skipped, which is exactly the difference between
 * this and grepping for a word.
 */
object JavaLexer {
    /**
     * Ranges (half-open) of every identifier token in [text], in ascending order.
     *
     * Keywords are included: they are lexically identifiers and it costs a caller nothing to ignore
     * them, whereas a caller that *wanted* `this` or `class` would have no way to get them back.
     */
    fun identifiers(text: String): List<IntRange> {
        val out = mutableListOf<IntRange>()
        forEachIdentifier(text) { start, end -> out += start until end }
        return out
    }

    /** Ranges of every identifier token in [text] whose text is exactly [name]. */
    fun occurrencesOf(text: String, name: String): List<IntRange> {
        if (name.isEmpty()) return emptyList()
        val out = mutableListOf<IntRange>()
        forEachIdentifier(text) { start, end ->
            if (end - start == name.length && text.regionMatches(start, name, 0, name.length)) {
                out += start until end
            }
        }
        return out
    }

    /**
     * Calls [onIdentifier] with the half-open range of each identifier token in [text].
     *
     * Written as a scan with a callback rather than a token list because the callers that matter
     * run it over every candidate file in a project: allocating a list of every identifier in a
     * 3000-line file, to keep the four that match a name, is the kind of cost that turns a
     * find-usages into a wait.
     */
    inline fun forEachIdentifier(text: String, onIdentifier: (start: Int, endExclusive: Int) -> Unit) {
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            when {
                // Comments. A '/' that opens neither is just an operator and falls through.
                c == '/' && i + 1 < n && text[i + 1] == '/' -> {
                    i += 2
                    while (i < n && text[i] != '\n') i++
                }
                c == '/' && i + 1 < n && text[i + 1] == '*' -> {
                    i += 2
                    while (i + 1 < n && !(text[i] == '*' && text[i + 1] == '/')) i++
                    i = minOf(n, i + 2)
                }
                // Text blocks before ordinary strings — a text block opens with three quotes, and
                // reading it as an empty string followed by a quote would swallow the file.
                c == '"' && i + 2 < n && text[i + 1] == '"' && text[i + 2] == '"' -> {
                    i += 3
                    while (i < n) {
                        if (text[i] == '\\') { i += 2; continue }
                        if (text[i] == '"' && i + 2 < n && text[i + 1] == '"' && text[i + 2] == '"') {
                            i += 3
                            break
                        }
                        i++
                    }
                    if (i > n) i = n
                }
                c == '"' -> {
                    i++
                    while (i < n && text[i] != '"') {
                        // An unterminated literal ends at the newline rather than eating the rest
                        // of the file — the buffer is being typed into, so this is a normal state.
                        if (text[i] == '\n') break
                        i += if (text[i] == '\\') 2 else 1
                    }
                    if (i < n && text[i] == '"') i++
                }
                c == '\'' -> {
                    i++
                    while (i < n && text[i] != '\'') {
                        if (text[i] == '\n') break
                        i += if (text[i] == '\\') 2 else 1
                    }
                    if (i < n && text[i] == '\'') i++
                }
                // A numeric literal is consumed whole, letters and all, so the `e5` in `1e5`, the
                // `x` in `0x1F` and the `L` in `10L` never look like identifiers.
                c.isDigit() -> {
                    i++
                    while (i < n && (text[i].isLetterOrDigit() || text[i] == '_' || text[i] == '.')) {
                        // A '.' only continues the number when a digit follows: `1.5` is one token,
                        // but `x1.foo` has already ended the number at the dot.
                        if (text[i] == '.' && (i + 1 >= n || !text[i + 1].isDigit())) break
                        i++
                    }
                }
                isIdentifierStart(c) -> {
                    val start = i
                    i++
                    while (i < n && isIdentifierPart(text[i])) i++
                    onIdentifier(start, i)
                }
                else -> i++
            }
        }
    }

    /** Java's rule, minus the Unicode corners: letters, `_` and `$` may start a name. */
    fun isIdentifierStart(c: Char): Boolean = c.isLetter() || c == '_' || c == '$'

    /** As [isIdentifierStart], plus digits. */
    fun isIdentifierPart(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '$'

    /**
     * The identifier token straddling [offset], or null when the caret isn't on one. Used to answer
     * "what did the user just press Find Usages on?" from a caret position alone.
     */
    fun identifierAt(text: String, offset: Int): IntRange? {
        if (offset < 0 || offset > text.length) return null
        var start = offset
        while (start > 0 && isIdentifierPart(text[start - 1])) start--
        var end = offset
        while (end < text.length && isIdentifierPart(text[end])) end++
        if (start == end) return null
        if (!isIdentifierStart(text[start])) return null
        return start until end
    }

    /**
     * The offset of the first non-whitespace, non-comment character at or after [from], or -1.
     * Callers use it to ask what follows a name — a `(` after a method's, a `{` after a class's —
     * which is how a declaration's own name is told apart from the other words around it.
     */
    fun skipTrivia(text: String, from: Int): Int {
        var i = from.coerceAtLeast(0)
        val n = text.length
        while (i < n) {
            val c = text[i]
            when {
                c.isWhitespace() -> i++
                c == '/' && i + 1 < n && text[i + 1] == '/' -> {
                    while (i < n && text[i] != '\n') i++
                }
                c == '/' && i + 1 < n && text[i + 1] == '*' -> {
                    i += 2
                    while (i + 1 < n && !(text[i] == '*' && text[i + 1] == '/')) i++
                    i = minOf(n, i + 2)
                }
                else -> return i
            }
        }
        return -1
    }

    /** Java's reserved words, which a rename must never produce. */
    val KEYWORDS: Set<String> = setOf(
        "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
        "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally",
        "float", "for", "goto", "if", "implements", "import", "instanceof", "int", "interface",
        "long", "native", "new", "package", "private", "protected", "public", "return", "short",
        "static", "strictfp", "super", "switch", "synchronized", "this", "throw", "throws",
        "transient", "try", "void", "volatile", "while",
        // Not reserved, but a declaration named any of these is never what the user meant.
        "true", "false", "null",
    )

    /** True when [name] is a legal Java identifier that isn't a reserved word. */
    fun isValidIdentifier(name: String): Boolean =
        name.isNotEmpty() &&
            isIdentifierStart(name[0]) &&
            name.all { isIdentifierPart(it) } &&
            name !in KEYWORDS
}
