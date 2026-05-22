package iondrive.nop.ui

import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle

enum class TokenKind { KEYWORD, STRING, COMMENT, NUMBER, LITERAL, PUNCT, HEADING, EMPHASIS }

data class Token(val start: Int, val endExclusive: Int, val kind: TokenKind)

/** Palette of token styles. Dark/light variants are picked at render time. */
data class HighlightPalette(
    val keyword: SpanStyle,
    val string: SpanStyle,
    val comment: SpanStyle,
    val number: SpanStyle,
    val literal: SpanStyle,
    val punct: SpanStyle,
    val heading: SpanStyle,
    val emphasis: SpanStyle,
) {
    fun styleFor(kind: TokenKind): SpanStyle = when (kind) {
        TokenKind.KEYWORD -> keyword
        TokenKind.STRING -> string
        TokenKind.COMMENT -> comment
        TokenKind.NUMBER -> number
        TokenKind.LITERAL -> literal
        TokenKind.PUNCT -> punct
        TokenKind.HEADING -> heading
        TokenKind.EMPHASIS -> emphasis
    }

    companion object {
        // Darcula-ish dark palette — matches the editor foreground at #A9B7C6.
        val Dark = HighlightPalette(
            keyword = SpanStyle(color = Color(0xFFCC7832)),
            string = SpanStyle(color = Color(0xFF6A8759)),
            comment = SpanStyle(color = Color(0xFF808080)),
            number = SpanStyle(color = Color(0xFF6897BB)),
            literal = SpanStyle(color = Color(0xFFCC7832)),
            punct = SpanStyle(color = Color(0xFFA9B7C6)),
            heading = SpanStyle(color = Color(0xFFFFC66D)),
            emphasis = SpanStyle(color = Color(0xFF9876AA)),
        )

        // IntelliJ-default light palette — darker hues so they read on a near-white background.
        val Light = HighlightPalette(
            keyword = SpanStyle(color = Color(0xFF0033B3)),
            string = SpanStyle(color = Color(0xFF067D17)),
            comment = SpanStyle(color = Color(0xFF8C8C8C)),
            number = SpanStyle(color = Color(0xFF1750EB)),
            literal = SpanStyle(color = Color(0xFF0033B3)),
            punct = SpanStyle(color = Color(0xFF1F2329)),
            heading = SpanStyle(color = Color(0xFF8A4A00)),
            emphasis = SpanStyle(color = Color(0xFF6F2DA8)),
        )
    }
}

/** Pick a tokenizer from a file extension. `null` means "no highlighting — render plain". */
fun tokenizerForExtension(ext: String?): ((String) -> List<Token>)? = when (ext?.lowercase()) {
    "kt", "kts" -> ::tokenizeKotlin
    "json" -> ::tokenizeJson
    "md", "markdown" -> ::tokenizeMarkdown
    "js", "mjs", "cjs", "ts", "tsx", "jsx" -> ::tokenizeJsTs
    "sh", "bash", "zsh" -> ::tokenizeShell
    else -> null
}

/** Build an [OutputTransformation] that paints [tokens] over the buffer text. */
fun highlightTransformation(
    tokenize: (String) -> List<Token>,
    palette: HighlightPalette,
): OutputTransformation = OutputTransformation {
    applyTokens(this, tokenize(asCharSequence().toString()), palette)
}

private fun applyTokens(buffer: TextFieldBuffer, tokens: List<Token>, palette: HighlightPalette) {
    val len = buffer.length
    for (t in tokens) {
        val s = t.start.coerceIn(0, len)
        val e = t.endExclusive.coerceIn(s, len)
        if (s == e) continue
        buffer.addStyle(palette.styleFor(t.kind), s, e)
    }
}

// ---------- Kotlin ----------

private val KOTLIN_KEYWORDS = setOf(
    "package", "import", "as", "typealias", "class", "this", "super", "val", "var", "fun",
    "for", "null", "true", "false", "is", "in", "throw", "return", "break", "continue", "object",
    "if", "try", "else", "while", "do", "when", "interface", "yield", "typeof", "abstract",
    "actual", "annotation", "by", "catch", "companion", "const", "constructor", "crossinline",
    "data", "dynamic", "enum", "expect", "external", "final", "finally", "get", "import", "infix",
    "init", "inline", "inner", "internal", "lateinit", "noinline", "open", "operator", "out",
    "override", "private", "protected", "public", "reified", "sealed", "set", "suspend", "tailrec",
    "throws", "vararg", "where",
)

private val KOTLIN_LINE_COMMENT = Regex("""//[^\n]*""")
private val KOTLIN_BLOCK_COMMENT = Regex("""/\*[\s\S]*?\*/""")
// Triple-quoted strings have no internal escapes. Plain strings allow \" escapes.
private val KOTLIN_TRIPLE_STRING = Regex("\"\"\"[\\s\\S]*?\"\"\"")
private val KOTLIN_STRING = Regex(""""(?:\\.|[^"\\\n])*"""")
private val KOTLIN_CHAR = Regex("""'(?:\\.|[^'\\\n])'""")
private val IDENT = Regex("""[A-Za-z_][A-Za-z0-9_]*""")
private val NUMBER_RE = Regex("""\b\d[\d_]*(?:\.\d[\d_]*)?(?:[eE][+-]?\d+)?[fFLuU]?\b""")

fun tokenizeKotlin(text: String): List<Token> {
    val out = ArrayList<Token>()
    val taken = BooleanArray(text.length)
    fun add(start: Int, end: Int, kind: TokenKind) {
        if (start >= end) return
        for (i in start until end) taken[i] = true
        out += Token(start, end, kind)
    }
    fun overlap(start: Int, end: Int): Boolean {
        for (i in start until end) if (taken[i]) return true
        return false
    }
    // Comments and strings first — they swallow keywords/numbers inside.
    KOTLIN_BLOCK_COMMENT.findAll(text).forEach { add(it.range.first, it.range.last + 1, TokenKind.COMMENT) }
    KOTLIN_LINE_COMMENT.findAll(text).forEach { if (!overlap(it.range.first, it.range.last + 1)) add(it.range.first, it.range.last + 1, TokenKind.COMMENT) }
    KOTLIN_TRIPLE_STRING.findAll(text).forEach { if (!overlap(it.range.first, it.range.last + 1)) add(it.range.first, it.range.last + 1, TokenKind.STRING) }
    KOTLIN_STRING.findAll(text).forEach { if (!overlap(it.range.first, it.range.last + 1)) add(it.range.first, it.range.last + 1, TokenKind.STRING) }
    KOTLIN_CHAR.findAll(text).forEach { if (!overlap(it.range.first, it.range.last + 1)) add(it.range.first, it.range.last + 1, TokenKind.STRING) }
    NUMBER_RE.findAll(text).forEach { if (!overlap(it.range.first, it.range.last + 1)) add(it.range.first, it.range.last + 1, TokenKind.NUMBER) }
    IDENT.findAll(text).forEach {
        val word = it.value
        if (word in KOTLIN_KEYWORDS && !overlap(it.range.first, it.range.last + 1)) {
            add(it.range.first, it.range.last + 1, TokenKind.KEYWORD)
        }
    }
    return out.sortedBy { it.start }
}

// ---------- JSON ----------

private val JSON_STRING = Regex("""(?<!\\)"(?:\\.|[^"\\])*"""")
private val JSON_NUMBER = Regex("""-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?""")
private val JSON_LITERAL = Regex("""\b(?:true|false|null)\b""")

fun tokenizeJson(text: String): List<Token> {
    val out = ArrayList<Token>()
    val taken = BooleanArray(text.length)
    fun overlap(start: Int, end: Int): Boolean {
        for (i in start until end) if (taken[i]) return true
        return false
    }
    fun add(start: Int, end: Int, kind: TokenKind) {
        if (start >= end || overlap(start, end)) return
        for (i in start until end) taken[i] = true
        out += Token(start, end, kind)
    }
    JSON_STRING.findAll(text).forEach { add(it.range.first, it.range.last + 1, TokenKind.STRING) }
    JSON_NUMBER.findAll(text).forEach { add(it.range.first, it.range.last + 1, TokenKind.NUMBER) }
    JSON_LITERAL.findAll(text).forEach { add(it.range.first, it.range.last + 1, TokenKind.LITERAL) }
    return out.sortedBy { it.start }
}

// ---------- Markdown ----------

private val MD_HEADING = Regex("""(?m)^#{1,6} [^\n]*""")
private val MD_FENCED_CODE = Regex("""(?ms)^```[\s\S]*?^```""")
private val MD_INLINE_CODE = Regex("""`[^`\n]+`""")
private val MD_BOLD = Regex("""\*\*[^*\n]+\*\*""")
private val MD_ITALIC = Regex("""(?<![*_])[*_](?!\s)[^*_\n]+?[*_](?![*_])""")
private val MD_LINK = Regex("""\[[^\]\n]+\]\([^)\n]+\)""")

fun tokenizeMarkdown(text: String): List<Token> {
    val out = ArrayList<Token>()
    val taken = BooleanArray(text.length)
    fun overlap(start: Int, end: Int): Boolean {
        for (i in start until end) if (taken[i]) return true
        return false
    }
    fun add(start: Int, end: Int, kind: TokenKind) {
        if (start >= end || overlap(start, end)) return
        for (i in start until end) taken[i] = true
        out += Token(start, end, kind)
    }
    MD_FENCED_CODE.findAll(text).forEach { add(it.range.first, it.range.last + 1, TokenKind.STRING) }
    MD_HEADING.findAll(text).forEach { add(it.range.first, it.range.last + 1, TokenKind.HEADING) }
    MD_INLINE_CODE.findAll(text).forEach { add(it.range.first, it.range.last + 1, TokenKind.STRING) }
    MD_BOLD.findAll(text).forEach { add(it.range.first, it.range.last + 1, TokenKind.EMPHASIS) }
    MD_ITALIC.findAll(text).forEach { add(it.range.first, it.range.last + 1, TokenKind.EMPHASIS) }
    MD_LINK.findAll(text).forEach { add(it.range.first, it.range.last + 1, TokenKind.LITERAL) }
    return out.sortedBy { it.start }
}

// ---------- JS / TS ----------

private val JSTS_KEYWORDS = setOf(
    "abstract", "any", "as", "async", "await", "boolean", "break", "case", "catch", "class",
    "const", "constructor", "continue", "debugger", "declare", "default", "delete", "do", "else",
    "enum", "export", "extends", "false", "finally", "for", "from", "function", "get", "if",
    "implements", "import", "in", "instanceof", "interface", "is", "keyof", "let", "module", "namespace",
    "never", "new", "null", "number", "object", "of", "package", "private", "protected", "public",
    "readonly", "return", "set", "static", "string", "super", "switch", "symbol", "this", "throw",
    "true", "try", "type", "typeof", "undefined", "var", "void", "while", "with", "yield",
)

private val JS_TEMPLATE = Regex("""`(?:\\.|[^`\\])*`""")

fun tokenizeJsTs(text: String): List<Token> {
    val out = ArrayList<Token>()
    val taken = BooleanArray(text.length)
    fun overlap(start: Int, end: Int): Boolean {
        for (i in start until end) if (taken[i]) return true
        return false
    }
    fun add(start: Int, end: Int, kind: TokenKind) {
        if (start >= end || overlap(start, end)) return
        for (i in start until end) taken[i] = true
        out += Token(start, end, kind)
    }
    KOTLIN_BLOCK_COMMENT.findAll(text).forEach { add(it.range.first, it.range.last + 1, TokenKind.COMMENT) }
    KOTLIN_LINE_COMMENT.findAll(text).forEach { add(it.range.first, it.range.last + 1, TokenKind.COMMENT) }
    JS_TEMPLATE.findAll(text).forEach { add(it.range.first, it.range.last + 1, TokenKind.STRING) }
    KOTLIN_STRING.findAll(text).forEach { add(it.range.first, it.range.last + 1, TokenKind.STRING) }
    Regex("""'(?:\\.|[^'\\\n])*'""").findAll(text).forEach { add(it.range.first, it.range.last + 1, TokenKind.STRING) }
    NUMBER_RE.findAll(text).forEach { add(it.range.first, it.range.last + 1, TokenKind.NUMBER) }
    IDENT.findAll(text).forEach {
        val w = it.value
        if (w in JSTS_KEYWORDS) add(it.range.first, it.range.last + 1, TokenKind.KEYWORD)
    }
    return out.sortedBy { it.start }
}

// ---------- Shell ----------

private val SHELL_KEYWORDS = setOf(
    "if", "then", "else", "elif", "fi", "case", "esac", "for", "while", "until", "do", "done",
    "in", "function", "select", "return", "break", "continue", "local", "export", "readonly",
    "declare", "typeset", "unset", "shift", "trap", "exit",
)

private val SHELL_COMMENT = Regex("""(?m)#[^\n]*""")
private val SHELL_VAR = Regex("""\$\{?[A-Za-z_][A-Za-z0-9_]*\}?""")

fun tokenizeShell(text: String): List<Token> {
    val out = ArrayList<Token>()
    val taken = BooleanArray(text.length)
    fun overlap(start: Int, end: Int): Boolean {
        for (i in start until end) if (taken[i]) return true
        return false
    }
    fun add(start: Int, end: Int, kind: TokenKind) {
        if (start >= end || overlap(start, end)) return
        for (i in start until end) taken[i] = true
        out += Token(start, end, kind)
    }
    SHELL_COMMENT.findAll(text).forEach { add(it.range.first, it.range.last + 1, TokenKind.COMMENT) }
    KOTLIN_STRING.findAll(text).forEach { add(it.range.first, it.range.last + 1, TokenKind.STRING) }
    Regex("""'[^'\n]*'""").findAll(text).forEach { add(it.range.first, it.range.last + 1, TokenKind.STRING) }
    SHELL_VAR.findAll(text).forEach { add(it.range.first, it.range.last + 1, TokenKind.LITERAL) }
    NUMBER_RE.findAll(text).forEach { add(it.range.first, it.range.last + 1, TokenKind.NUMBER) }
    IDENT.findAll(text).forEach {
        val w = it.value
        if (w in SHELL_KEYWORDS) add(it.range.first, it.range.last + 1, TokenKind.KEYWORD)
    }
    return out.sortedBy { it.start }
}
