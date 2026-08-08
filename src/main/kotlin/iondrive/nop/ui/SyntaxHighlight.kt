package iondrive.nop.ui

import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import iondrive.nop.Log

enum class TokenKind { KEYWORD, STRING, COMMENT, NUMBER, LITERAL, PUNCT, HEADING, EMPHASIS, ERROR }

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
    val error: SpanStyle,
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
        TokenKind.ERROR -> error
    }

    companion object {
        // Dark palette — hues picked to sit against the editor foreground at #BCBEC4.
        val Dark = HighlightPalette(
            keyword = SpanStyle(color = Color(0xFFCF8E6D)),  // Orange keywords
            string = SpanStyle(color = Color(0xFF6AAB73)),   // Green strings
            // IntelliJ renders comments in italics; mirror that so they read as prose, not code.
            comment = SpanStyle(color = Color(0xFF7A7E85), fontStyle = FontStyle.Italic),
            number = SpanStyle(color = Color(0xFF2AACB8)),   // Cyan numbers
            literal = SpanStyle(color = Color(0xFFCF8E6D)),  // Orange literals like keywords
            punct = SpanStyle(color = Color(0xFFBCBEC4)),    // Light grey punctuation
            heading = SpanStyle(color = Color(0xFFC77DBB)),  // Pink/magenta for markdown headings
            emphasis = SpanStyle(color = Color(0xFFB389C5)), // Purple for annotations/emphasis
            // Errors are tinted red; the editor draws a red wavy underline under the range on top
            // of this (see ErrorSquiggles) for the IntelliJ-style "this is wrong" squiggle.
            error = SpanStyle(color = Color(0xFFFF6B68)),
        )

        // IntelliJ-default light palette — darker hues so they read on a near-white background.
        val Light = HighlightPalette(
            keyword = SpanStyle(color = Color(0xFF0033B3)),  // Dark blue keywords
            string = SpanStyle(color = Color(0xFF067D17)),   // Green strings
            comment = SpanStyle(color = Color(0xFF8C8C8C), fontStyle = FontStyle.Italic),
            number = SpanStyle(color = Color(0xFF1750EB)),   // Blue numbers
            literal = SpanStyle(color = Color(0xFF0033B3)),  // Dark blue literals
            punct = SpanStyle(color = Color(0xFF000000)),    // Black punctuation
            heading = SpanStyle(color = Color(0xFF871094)),  // Purple headings
            emphasis = SpanStyle(color = Color(0xFF9E4585)), // Magenta emphasis
            error = SpanStyle(color = Color(0xFFFF0000)),    // Red errors
        )

        // Diff variants. A diff row paints a colour tint behind its text (see DiffRendering's
        // INSERT_BG/INLINE_WORD_BG), and green is the luminous one: the editor's comment grey lands
        // at 2.9:1 over the dark insert tint and 2.5:1 over the light one, dropping to 2.3:1 / 1.9:1
        // over the stronger inline word-change green — far below the rest of the palette, which
        // clears ~4:1 there, and the reason a changed comment reads as a smudge. Only the comment
        // colour moves; every other token already sits in that ~4:1 band over the tints, and pushing
        // comments past it would make them the *highest*-contrast token on the row, which is
        // backwards. The editor keeps IntelliJ's greys — it has no tint to fight.
        val DarkDiff = Dark.copy(comment = Dark.comment.copy(color = Color(0xFFA0A3AB)))
        val LightDiff = Light.copy(comment = Light.comment.copy(color = Color(0xFF4A4D52)))
    }
}

/** Pick a tokenizer from a file extension. `null` means "no highlighting — render plain". */
fun tokenizerForExtension(ext: String?): ((String) -> List<Token>)? {
    val tokenize: (String) -> List<Token> = when (ext?.lowercase()) {
        "kt", "kts" -> ::tokenizeKotlin
        "java" -> ::tokenizeJava
        "go" -> ::tokenizeGo
        "json" -> ::tokenizeJson
        "md", "markdown" -> ::tokenizeMarkdown
        "js", "mjs", "cjs", "ts", "tsx", "jsx" -> ::tokenizeJsTs
        "py", "pyw", "pyi" -> ::tokenizePython
        "sh", "bash", "zsh" -> ::tokenizeShell
        "yml", "yaml" -> ::tokenizeYaml
        "j2", "jinja", "jinja2" -> ::tokenizeAnsible
        else -> return null
    }
    // A tokenizer bug must not be fatal. Callers run these inside a derivedStateOf that Compose
    // evaluates *during composition*, so a throwable escapes the composition and kills the AWT
    // thread — taking the window down with every project and terminal in it. Errors are caught as
    // well as exceptions on purpose: the failure this guards against was a StackOverflowError from
    // a runaway regex, and the stack has already unwound by the time we get here. Falling back to
    // unhighlighted text loses colour on one file; the alternative loses the session.
    return { text ->
        runCatching { tokenize(text) }.getOrElse { t ->
            Log.error("highlighting failed for .$ext (${text.length} chars) — rendering plain", t)
            emptyList()
        }
    }
}

/** Build an [OutputTransformation] that paints [tokens] over the buffer text. */
fun highlightTransformation(
    tokenize: (String) -> List<Token>,
    palette: HighlightPalette,
): OutputTransformation = OutputTransformation {
    applyTokens(this, tokenize(asCharSequence().toString()), palette)
}

/** Apply [tokens] to [buffer] with [palette]'s styles. Exposed so the editor can chain it with
 *  ad-hoc spans (hover underline, etc.) inside a single [OutputTransformation]. */
internal fun applyTokens(buffer: TextFieldBuffer, tokens: List<Token>, palette: HighlightPalette) {
    val len = buffer.length
    for (t in tokens) {
        val s = t.start.coerceIn(0, len)
        val e = t.endExclusive.coerceIn(s, len)
        if (s == e) continue
        buffer.addStyle(palette.styleFor(t.kind), s, e)
    }
}

// ---------- Quoted-string scanning ----------
//
// Quoted literals are scanned by hand instead of with the obvious regex, `"(?:\\.|[^"\\])*"`.
// Java compiles a starred *group* containing an alternation into a recursive matcher — roughly six
// stack frames per character consumed — so a long literal blows the stack rather than failing to
// match. An 11KB template literal in a .ts file used to take the whole editor down with a
// StackOverflowError thrown mid-composition. This loop uses O(1) stack and is faster besides.
//
// Returns the ranges of each literal, inclusive of both delimiters. An unterminated literal yields
// nothing, so a stray quote colours no text rather than painting the rest of the file.
//
//  [escape]        a backslash escapes the next character (so `\"` does not close the literal).
//  [doubledQuote]  a doubled delimiter escapes it instead — YAML's '' inside a single-quoted scalar.
//  [multiline]     the literal may span lines; most may not, JS template literals may.
private fun delimitedRanges(
    text: String,
    quote: Char,
    escape: Boolean = true,
    doubledQuote: Boolean = false,
    multiline: Boolean = false,
): List<IntRange> {
    val out = ArrayList<IntRange>()
    var i = 0
    while (i < text.length) {
        if (text[i] != quote) {
            i++
            continue
        }
        var j = i + 1
        var closed = false
        while (j < text.length) {
            val c = text[j]
            if (!multiline && (c == '\n' || c == '\r')) break
            if (escape && c == '\\') {
                // A trailing backslash at end of input leaves the literal unterminated.
                if (j + 1 >= text.length) break
                j += 2
                continue
            }
            if (c == quote) {
                if (doubledQuote && j + 1 < text.length && text[j + 1] == quote) {
                    j += 2
                    continue
                }
                closed = true
                break
            }
            j++
        }
        if (closed) {
            out += i..j
            i = j + 1
        } else {
            // Not a literal after all — resume scanning just past the opening quote so a later,
            // properly closed literal on the same line is still found.
            i++
        }
    }
    return out
}

/** Ranges of `"…"` literals — the shape every language here shares. */
private fun doubleQuoted(text: String): List<IntRange> = delimitedRanges(text, '"')

/** Ranges of `'…'` literals with backslash escapes — JS/TS strings and Go rune literals. */
private fun singleQuoted(text: String): List<IntRange> = delimitedRanges(text, '\'')

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
// Triple-quoted strings have no internal escapes. Plain strings allow \" escapes and are scanned
// by [doubleQuoted] rather than matched by a regex — see the note there.
private val KOTLIN_TRIPLE_STRING = Regex("\"\"\"[\\s\\S]*?\"\"\"")
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
    doubleQuoted(text).forEach { if (!overlap(it.first, it.last + 1)) add(it.first, it.last + 1, TokenKind.STRING) }
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

// ---------- Java ----------

// The reserved words plus the contextual keywords (var, record, sealed, permits, yield) that read
// as keywords in modern sources. `non-sealed` is left out — the hyphen keeps it from being a
// single identifier, and its `sealed` half still lights up. true/false/null are constants —
// coloured as literals (like Go's nil) rather than keywords.
private val JAVA_KEYWORDS = setOf(
    "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
    "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float",
    "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long",
    "native", "new", "package", "private", "protected", "public", "return", "short", "static",
    "strictfp", "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try",
    "void", "volatile", "while",
    "var", "record", "sealed", "permits", "yield",
)

private val JAVA_LITERAL = Regex("""\b(?:true|false|null)\b""")
// An annotation: `@Name`, optionally dotted (`@jakarta.inject.Inject`). Coloured as emphasis like
// Python decorators. Javadoc tags (`@param`) sit inside comments, which are lexed first, so the
// overlap check keeps them out. `@interface` declarations fall through to the keyword pass.
private val JAVA_ANNOTATION = Regex("""@[A-Za-z_][A-Za-z0-9_.]*""")
// Hex / binary / decimal / float / scientific, each with `_` digit separators and an optional
// f/d/l suffix. (Bare-leading-zero octal is covered by the decimal branch.)
private val JAVA_NUMBER = Regex(
    """\b(?:0[xX][0-9a-fA-F_]+|0[bB][01_]+|\d[\d_]*(?:\.\d[\d_]*)?(?:[eE][+-]?\d+)?)[fFdDlL]?\b""",
)

fun tokenizeJava(text: String): List<Token> {
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
    // Comments and strings first — they swallow keywords/numbers inside. Text blocks (Java 15's
    // triple quotes) share Kotlin's shape and must run before plain strings.
    KOTLIN_BLOCK_COMMENT.findAll(text).forEach { add(it.range.first, it.range.last + 1, TokenKind.COMMENT) }
    KOTLIN_LINE_COMMENT.findAll(text).forEach { add(it.range.first, it.range.last + 1, TokenKind.COMMENT) }
    KOTLIN_TRIPLE_STRING.findAll(text).forEach { add(it.range.first, it.range.last + 1, TokenKind.STRING) }
    doubleQuoted(text).forEach { add(it.first, it.last + 1, TokenKind.STRING) }
    KOTLIN_CHAR.findAll(text).forEach { add(it.range.first, it.range.last + 1, TokenKind.STRING) }
    JAVA_ANNOTATION.findAll(text).forEach { add(it.range.first, it.range.last + 1, TokenKind.EMPHASIS) }
    JAVA_NUMBER.findAll(text).forEach { add(it.range.first, it.range.last + 1, TokenKind.NUMBER) }
    JAVA_LITERAL.findAll(text).forEach { add(it.range.first, it.range.last + 1, TokenKind.LITERAL) }
    IDENT.findAll(text).forEach {
        if (it.value in JAVA_KEYWORDS) add(it.range.first, it.range.last + 1, TokenKind.KEYWORD)
    }
    return out.sortedBy { it.start }
}

// ---------- JSON ----------

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
    // JSON strings never contain a raw newline, so an unterminated one stops at end of line
    // instead of colouring the rest of the document.
    doubleQuoted(text).forEach { add(it.first, it.last + 1, TokenKind.STRING) }
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
    // Template literals are the one string form that may span lines.
    delimitedRanges(text, '`', multiline = true).forEach { add(it.first, it.last + 1, TokenKind.STRING) }
    doubleQuoted(text).forEach { add(it.first, it.last + 1, TokenKind.STRING) }
    singleQuoted(text).forEach { add(it.first, it.last + 1, TokenKind.STRING) }
    NUMBER_RE.findAll(text).forEach { add(it.range.first, it.range.last + 1, TokenKind.NUMBER) }
    IDENT.findAll(text).forEach {
        val w = it.value
        if (w in JSTS_KEYWORDS) add(it.range.first, it.range.last + 1, TokenKind.KEYWORD)
    }
    return out.sortedBy { it.start }
}

// ---------- Ansible / Jinja templates ----------
// Used for .j2/.jinja files. Plain .yml/.yaml go through the native YAML lexer below; this one
// stays for templates because it understands Jinja blocks and highlights Ansible's vocabulary.

// Module + directive names that show up at the start of a task. Highlighting these gives the
// "what does this play do" scan; the list isn't exhaustive but covers the modules that crop
// up across most playbooks. Project-shaped vocabulary (custom modules, roles) is intentionally
// left unhighlighted so the file stays readable.
private val ANSIBLE_KEYWORDS = setOf(
    // Play / task directives
    "name", "hosts", "tasks", "handlers", "vars", "vars_files", "vars_prompt", "roles",
    "pre_tasks", "post_tasks", "tags", "when", "register", "become", "become_user",
    "become_method", "delegate_to", "delegate_facts", "run_once", "no_log", "ignore_errors",
    "changed_when", "failed_when", "until", "retries", "delay", "loop", "with_items",
    "with_dict", "with_fileglob", "with_lines", "with_subelements", "with_together",
    "notify", "listen", "block", "rescue", "always", "any_errors_fatal", "max_fail_percentage",
    "serial", "strategy", "gather_facts", "environment", "check_mode", "diff", "remote_user",
    // Common modules
    "command", "shell", "raw", "script", "copy", "template", "file", "lineinfile", "blockinfile",
    "stat", "fetch", "synchronize", "unarchive", "git", "uri", "get_url", "package",
    "apt", "apt_repository", "apt_key", "yum", "yum_repository", "dnf", "pip",
    "service", "systemd", "user", "group", "mount", "cron", "find", "replace",
    "set_fact", "debug", "fail", "assert", "wait_for", "wait_for_connection",
    "include", "include_tasks", "import_tasks", "include_role", "import_role",
    "include_playbook", "import_playbook", "include_vars",
    "meta", "pause", "ping", "setup",
    // Authentication / connection
    "connection", "port", "ansible_host", "ansible_user", "ansible_port",
)

private val YAML_LINE_COMMENT = Regex("""(?m)#[^\n]*""")
// Jinja2 substitution / statement / comment forms. Highlighted as a unit so module args like
// `path: "{{ var }}"` show the templated portion distinctly from the surrounding string.
private val JINJA_BLOCK = Regex("""\{\{[\s\S]*?\}\}|\{%[\s\S]*?%\}|\{#[\s\S]*?#\}""")
// "key:" at line start, optionally after indentation and/or a list dash. The colon must be
// followed by whitespace or end-of-line so bare colons inside values (URLs, etc.) don't form
// keys. Indentation and dash live in non-capturing groups; group 1 is just the key name.
private val YAML_KEY = Regex("""(?m)^\s*(?:-\s+)?([A-Za-z_][\w.-]*)(?=\s*:(?:\s|$))""")
private val YAML_NUMBER = Regex("""(?<![\w.])-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?(?![\w.])""")
private val YAML_LITERAL_WORD = Regex("""(?<![\w])(?:true|false|yes|no|on|off|null|True|False|Yes|No|TRUE|FALSE|YES|NO|ON|OFF|NULL|Null|~)(?![\w])""")

fun tokenizeAnsible(text: String): List<Token> {
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
    // Order matters: comments and strings first, then Jinja inside what's left (so a `{{`
    // inside a comment is part of the comment, but a `{{ var }}` in a double-quoted value
    // would normally be swallowed by the string — we accept that to keep the lexer simple.
    YAML_LINE_COMMENT.findAll(text).forEach { add(it.range.first, it.range.last + 1, TokenKind.COMMENT) }
    doubleQuoted(text).forEach { add(it.first, it.last + 1, TokenKind.STRING) }
    // A single-quoted YAML scalar has no backslash escapes; '' is a literal quote.
    delimitedRanges(text, '\'', escape = false, doubledQuote = true)
        .forEach { add(it.first, it.last + 1, TokenKind.STRING) }
    JINJA_BLOCK.findAll(text).forEach { add(it.range.first, it.range.last + 1, TokenKind.EMPHASIS) }

    // Highlight the key portion when it names a known Ansible directive/module; the rest of the
    // line is rendered as plain text so user-defined vars stay neutral.
    YAML_KEY.findAll(text).forEach { m ->
        val keyGroup = m.groups[1] ?: return@forEach
        val name = keyGroup.value
        if (name in ANSIBLE_KEYWORDS) {
            add(keyGroup.range.first, keyGroup.range.last + 1, TokenKind.KEYWORD)
        }
    }
    YAML_LITERAL_WORD.findAll(text).forEach { add(it.range.first, it.range.last + 1, TokenKind.LITERAL) }
    YAML_NUMBER.findAll(text).forEach { add(it.range.first, it.range.last + 1, TokenKind.NUMBER) }
    return out.sortedBy { it.start }
}

// ---------- YAML (native) ----------

// Plain-scalar words YAML reads as booleans / null rather than strings. YAML 1.1 (still emitted by
// a lot of tooling) also accepts yes/no/on/off and their case variants, so we accept those too.
private val YAML_LITERALS = setOf(
    "true", "false", "null", "yes", "no", "on", "off", "~",
    "True", "False", "Null", "Yes", "No", "On", "Off",
    "TRUE", "FALSE", "NULL", "YES", "NO", "ON", "OFF",
)

// A value that is *entirely* a number: optional sign, then hex / octal / decimal-or-float (with an
// optional exponent), or the YAML infinity / not-a-number forms. Matched against the whole trimmed
// value (matches(), not find()) so we never colour a digit that is only part of a larger scalar.
private val YAML_NUMBER_VALUE = Regex(
    """[-+]?(?:0x[0-9a-fA-F]+|0o[0-7]+|(?:\d[\d_]*)?\.?\d[\d_]*(?:[eE][+-]?\d+)?|\.(?:inf|Inf|INF)|\.(?:nan|NaN|NAN))""",
)

// Scan from the char *after* an opening quote to the matching close, honouring \" escapes in
// double quotes and '' escapes in single quotes. Returns the index past the close, or -1 if the
// quote isn't closed before [lineEnd] (the scalar continues onto the next line, or is malformed).
private fun readQuoteBody(text: String, from: Int, lineEnd: Int, quote: Char): Int {
    var p = from
    while (p < lineEnd) {
        val c = text[p]
        if (quote == '"' && c == '\\') { p += 2; continue }
        if (c == quote) {
            if (quote == '\'' && p + 1 < lineEnd && text[p + 1] == '\'') { p += 2; continue }
            return p + 1
        }
        p++
    }
    return -1
}

// True when `|` / `>` at [p] opens a block scalar: only chomping/indent indicators and an optional
// comment may follow it on the line. `a > b` (a `>` mid-value) returns false and stays plain text.
private fun isBlockScalarIndicator(text: String, p: Int, lineEnd: Int): Boolean {
    var q = p + 1
    while (q < lineEnd && (text[q] == '+' || text[q] == '-' || text[q].isDigit())) q++
    while (q < lineEnd && text[q] == ' ') q++
    return q >= lineEnd || text[q] == '#'
}

/**
 * Native YAML lexer + lightweight structural validator. Beyond colouring keys, scalars, comments,
 * anchors/aliases/tags and flow collections, it flags the syntax errors that a single forward pass
 * can detect with no false positives: tab characters used for indentation, dedents that don't line
 * up with any open block, stray flow closers, and quotes / flow collections left open at EOF. Those
 * ERROR tokens render red + underlined via [HighlightPalette.error].
 */
fun tokenizeYaml(text: String): List<Token> {
    val out = ArrayList<Token>()
    val n = text.length
    fun add(start: Int, end: Int, kind: TokenKind) {
        if (start in 0 until end && end <= n) out += Token(start, end, kind)
    }

    // Cross-line state.
    val indentStack = ArrayList<Int>()   // indentation columns of the currently-open blocks, ascending
    var flowDepth = 0                     // nesting depth inside [ ] / { } (which span lines freely)
    var flowOpenOffset = -1              // offset of the outermost flow bracket still open
    var blockScalarParent = -1          // indent of the key owning an active | / > scalar; -1 = none
    var pendingQuote = ' '         // a quote opened on an earlier line and not yet closed
    var pendingQuoteOffset = -1
    var blockScalarOpenIndent = -1      // set by lexValue when it opens a block scalar on this line

    // A Jinja block ({{ }}, {% %}, {# #}) opening at [p]? These pervade Ansible YAML and contain
    // quotes/brackets that aren't YAML syntax, so we treat them as opaque and never error inside.
    fun isJinjaStart(p: Int, lineEnd: Int): Boolean =
        p + 1 < lineEnd && text[p] == '{' && (text[p + 1] == '{' || text[p + 1] == '%' || text[p + 1] == '#')

    // End (exclusive) of the Jinja block opening at [p]; falls back to lineEnd for an unclosed or
    // multi-line block (Jinja is opaque, never a YAML error).
    fun jinjaEnd(p: Int, lineEnd: Int): Int {
        val close = when (text[p + 1]) { '{' -> "}}"; '%' -> "%}"; else -> "#}" }
        val idx = text.indexOf(close, p + 2)
        return if (idx in 0 until lineEnd) idx + 2 else lineEnd
    }

    // After a quoted/flow value, only whitespace and a trailing `# comment` may follow.
    fun lexTrailingComment(from: Int, lineEnd: Int) {
        var q = from
        while (q < lineEnd && (text[q] == ' ' || text[q] == '\t')) q++
        if (q < lineEnd && text[q] == '#') add(q, lineEnd, TokenKind.COMMENT)
    }

    // A block-context plain scalar runs from [start] to end-of-line (or a ` #` comment). Crucially a
    // plain scalar *contains spaces*, so we colour the whole span as one unit: Jinja inside is shown
    // as emphasis, and the scalar is only tinted as a number/literal when it is entirely one (and
    // free of Jinja). Everything else stays neutral so prose reads plainly.
    fun lexPlainScalar(start: Int, lineEnd: Int) {
        var commentAt = -1
        var i = start
        while (i < lineEnd) {
            if (text[i] == '#' && (i == 0 || text[i - 1] == ' ' || text[i - 1] == '\t')) { commentAt = i; break }
            i++
        }
        val scalarEnd = if (commentAt >= 0) commentAt else lineEnd
        var te = scalarEnd
        while (te > start && (text[te - 1] == ' ' || text[te - 1] == '\t')) te--
        var hasJinja = false
        var j = start
        while (j < te) {
            if (isJinjaStart(j, lineEnd)) { val e = jinjaEnd(j, lineEnd).coerceAtMost(te); add(j, e, TokenKind.EMPHASIS); hasJinja = true; j = maxOf(e, j + 1) } else j++
        }
        if (!hasJinja) {
            val run = text.substring(start, te)
            when {
                run in YAML_LITERALS -> add(start, te, TokenKind.LITERAL)
                YAML_NUMBER_VALUE.matches(run) -> add(start, te, TokenKind.NUMBER)
            }
        }
        if (commentAt >= 0) add(commentAt, lineEnd, TokenKind.COMMENT)
    }

    // Token scanner for flow collections ([ ], { }) — and only those. Flow spans lines freely, so
    // this also runs for continuation lines while [flowDepth] > 0. Commas/colons/brackets are
    // punctuation here; strings, Jinja and nested flow are handled inline.
    fun lexFlow(from: Int, lineEnd: Int) {
        var p = from
        while (p < lineEnd) {
            val c = text[p]
            when {
                c == ' ' || c == '\t' -> p++
                c == '#' && (p == from || text[p - 1] == ' ' || text[p - 1] == '\t') -> { add(p, lineEnd, TokenKind.COMMENT); p = lineEnd }
                isJinjaStart(p, lineEnd) -> { val e = jinjaEnd(p, lineEnd); add(p, e, TokenKind.EMPHASIS); p = maxOf(e, p + 1) }
                c == '"' || c == '\'' -> {
                    val end = readQuoteBody(text, p + 1, lineEnd, c)
                    if (end < 0) { add(p, lineEnd, TokenKind.STRING); pendingQuote = c; pendingQuoteOffset = p; p = lineEnd }
                    else { add(p, end, TokenKind.STRING); p = end }
                }
                c == '&' || c == '*' || c == '!' -> {
                    var q = p + 1
                    while (q < lineEnd && !text[q].isWhitespace() && text[q] !in ",[]{}") q++
                    add(p, maxOf(q, p + 1), TokenKind.EMPHASIS); p = maxOf(q, p + 1)
                }
                c == '[' || c == '{' -> { if (flowDepth == 0) flowOpenOffset = p; flowDepth++; add(p, p + 1, TokenKind.PUNCT); p++ }
                c == ']' || c == '}' -> { if (flowDepth > 0) { flowDepth--; add(p, p + 1, TokenKind.PUNCT) } else add(p, p + 1, TokenKind.ERROR); p++ }
                c == ',' -> { add(p, p + 1, TokenKind.PUNCT); p++ }
                c == ':' && (p + 1 >= lineEnd || text[p + 1] == ' ' || flowDepth > 0) -> { add(p, p + 1, TokenKind.PUNCT); p++ }
                else -> {
                    val start = p
                    while (p < lineEnd) {
                        val ch = text[p]
                        if (ch == ' ' || ch == '\t' || ch == ',' || ch == ']' || ch == '}' || ch == '"' || ch == '\'') break
                        if (isJinjaStart(p, lineEnd)) break
                        if (ch == ':' && (p + 1 >= lineEnd || text[p + 1] == ' ' || flowDepth > 0)) break
                        p++
                    }
                    val run = text.substring(start, p)
                    when {
                        run in YAML_LITERALS -> add(start, p, TokenKind.LITERAL)
                        YAML_NUMBER_VALUE.matches(run) -> add(start, p, TokenKind.NUMBER)
                    }
                }
            }
        }
    }

    // Lex a value region [from, lineEnd). In block context a value is a single scalar (plain,
    // quoted, or block) or a flow collection; only flow is scanned token-by-token. Updates the
    // cross-line state (an open quote/flow/block-scalar the next lines must continue).
    fun lexValue(from: Int, lineEnd: Int, lineIndent: Int) {
        var p = from
        while (p < lineEnd && (text[p] == ' ' || text[p] == '\t')) p++
        if (p >= lineEnd) return
        if (flowDepth > 0) { lexFlow(p, lineEnd); return }
        when {
            text[p] == '#' -> add(p, lineEnd, TokenKind.COMMENT)
            text[p] == '"' || text[p] == '\'' -> {
                val c = text[p]
                val end = readQuoteBody(text, p + 1, lineEnd, c)
                if (end < 0) { add(p, lineEnd, TokenKind.STRING); pendingQuote = c; pendingQuoteOffset = p }
                else { add(p, end, TokenKind.STRING); lexTrailingComment(end, lineEnd) }
            }
            isJinjaStart(p, lineEnd) -> lexPlainScalar(p, lineEnd)
            text[p] == '[' || text[p] == '{' -> lexFlow(p, lineEnd)
            (text[p] == '|' || text[p] == '>') && isBlockScalarIndicator(text, p, lineEnd) -> {
                add(p, p + 1, TokenKind.PUNCT); blockScalarOpenIndent = lineIndent
            }
            // Anchors (&a), aliases (*a) and tags (!!str / !foo) prefix the actual value.
            text[p] == '&' || text[p] == '*' || text[p] == '!' -> {
                var q = p + 1
                while (q < lineEnd && !text[q].isWhitespace() && text[q] !in ",[]{}") q++
                add(p, maxOf(q, p + 1), TokenKind.EMPHASIS)
                lexValue(maxOf(q, p + 1), lineEnd, lineIndent)
            }
            else -> lexPlainScalar(p, lineEnd)
        }
    }

    var lineStart = 0
    while (lineStart <= n) {
        var lineEnd = text.indexOf('\n', lineStart)
        val isLast = lineEnd < 0
        if (isLast) lineEnd = n
        blockScalarOpenIndent = -1

        run line@{
            // 1) Continuation of a quoted scalar opened on an earlier line.
            if (pendingQuote != ' ') {
                val end = readQuoteBody(text, lineStart, lineEnd, pendingQuote)
                if (end < 0) {
                    add(lineStart, lineEnd, TokenKind.STRING)
                } else {
                    add(lineStart, end, TokenKind.STRING)
                    pendingQuote = ' '; pendingQuoteOffset = -1
                    lexValue(end, lineEnd, 0)
                }
                return@line
            }

            // Measure indentation (emit nothing yet — block-scalar bodies allow tabs).
            var idx = lineStart
            while (idx < lineEnd && (text[idx] == ' ' || text[idx] == '\t')) idx++
            val indent = idx - lineStart
            val contentStart = idx
            val blank = contentStart == lineEnd

            // 2) Body of an active block scalar (| or >): blank lines and anything indented past the
            //    owning key is literal text.
            if (blockScalarParent >= 0) {
                if (blank || indent > blockScalarParent) {
                    if (!blank) add(contentStart, lineEnd, TokenKind.STRING)
                    return@line
                }
                blockScalarParent = -1 // dedented out — fall through and process this line normally
            }
            if (blank) return@line

            // 3) Inside a multi-line flow collection: no block structure, just lex the content.
            if (flowDepth > 0) { lexValue(contentStart, lineEnd, indent); return@line }

            // Tabs in indentation are illegal in block context — flag each one.
            var t = lineStart
            while (t < contentStart) { if (text[t] == '\t') add(t, t + 1, TokenKind.ERROR); t++ }

            if (text[contentStart] == '#') { add(contentStart, lineEnd, TokenKind.COMMENT); return@line }

            // Document start / end markers reset the block structure.
            if (contentStart + 3 <= lineEnd && (text.substring(contentStart, contentStart + 3).let { it == "---" || it == "..." }) &&
                (contentStart + 3 == lineEnd || text[contentStart + 3] == ' ')
            ) {
                add(contentStart, contentStart + 3, TokenKind.PUNCT)
                indentStack.clear()
                lexValue(contentStart + 3, lineEnd, indent)
                return@line
            }

            // 4) Indentation validation. We only flag a dedent that lands between two open levels —
            //    the unambiguous YAML indent error — and recover by adopting it so we don't cascade.
            val prevTop = indentStack.lastOrNull() ?: -1
            if (indent > prevTop) {
                indentStack.add(indent)
            } else if (indent < prevTop) {
                while (indentStack.isNotEmpty() && indentStack.last() > indent) indentStack.removeAt(indentStack.size - 1)
                if ((indentStack.lastOrNull() ?: -1) != indent) {
                    add(lineStart, contentStart, TokenKind.ERROR)
                    indentStack.add(indent)
                }
            }

            // Parse the line: leading block-sequence dashes, then an optional `key:`, then the value.
            var p = contentStart
            while (p < lineEnd && text[p] == '-' && (p + 1 >= lineEnd || text[p + 1] == ' ')) {
                add(p, p + 1, TokenKind.PUNCT); p++
                while (p < lineEnd && text[p] == ' ') p++
                // A "- " sequence dash shifts the block inwards: the item's content — including any
                // mapping keys written on this same line (`- key: val`) — sits at the column after
                // the dash, which is a deeper level than the dash itself. Register that column so a
                // later sibling key (`serial:` under `- hosts:`) lines up with it instead of looking
                // like a dedent into open air. Skip when the dash ends the line (the content is on
                // the following, more-indented lines and will push its own level).
                if (p < lineEnd && p - lineStart > (indentStack.lastOrNull() ?: -1)) {
                    indentStack.add(p - lineStart)
                }
            }
            if (p >= lineEnd) return@line
            if (text[p] == '#') { add(p, lineEnd, TokenKind.COMMENT); return@line }

            val keyStart = p
            var keyEnd = -1
            var colonPos = -1
            if (text[p] == '"' || text[p] == '\'') {
                val qe = readQuoteBody(text, p + 1, lineEnd, text[p])
                if (qe > 0) {
                    var q = qe
                    while (q < lineEnd && text[q] == ' ') q++
                    if (q < lineEnd && text[q] == ':' && (q + 1 >= lineEnd || text[q + 1] == ' ')) {
                        keyEnd = qe; colonPos = q
                    }
                }
            } else {
                var q = p
                while (q < lineEnd) {
                    val ch = text[q]
                    if (ch == ':' && (q + 1 >= lineEnd || text[q + 1] == ' ')) { colonPos = q; break }
                    if (ch == '#' && q > p && text[q - 1] == ' ') break
                    if (ch == '{' || ch == '[') break // a flow value, not a key
                    q++
                }
                if (colonPos >= 0) {
                    keyEnd = colonPos
                    while (keyEnd > keyStart && text[keyEnd - 1] == ' ') keyEnd--
                }
            }

            if (colonPos >= 0) {
                if (text[keyStart] == '"' || text[keyStart] == '\'') add(keyStart, keyEnd, TokenKind.STRING)
                else add(keyStart, keyEnd, TokenKind.KEYWORD)
                add(colonPos, colonPos + 1, TokenKind.PUNCT)
                lexValue(colonPos + 1, lineEnd, indent)
            } else {
                lexValue(keyStart, lineEnd, indent)
            }
        }

        if (blockScalarOpenIndent >= 0) blockScalarParent = blockScalarOpenIndent

        if (isLast) break
        lineStart = lineEnd + 1
    }

    // Anything left open at EOF is a real error: an unterminated quote or flow collection.
    if (pendingQuote != ' ' && pendingQuoteOffset >= 0) {
        add(pendingQuoteOffset, pendingQuoteOffset + 1, TokenKind.ERROR)
    }
    if (flowDepth > 0 && flowOpenOffset >= 0) add(flowOpenOffset, flowOpenOffset + 1, TokenKind.ERROR)

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
    doubleQuoted(text).forEach { add(it.first, it.last + 1, TokenKind.STRING) }
    Regex("""'[^'\n]*'""").findAll(text).forEach { add(it.range.first, it.range.last + 1, TokenKind.STRING) }
    SHELL_VAR.findAll(text).forEach { add(it.range.first, it.range.last + 1, TokenKind.LITERAL) }
    NUMBER_RE.findAll(text).forEach { add(it.range.first, it.range.last + 1, TokenKind.NUMBER) }
    IDENT.findAll(text).forEach {
        val w = it.value
        if (w in SHELL_KEYWORDS) add(it.range.first, it.range.last + 1, TokenKind.KEYWORD)
    }
    return out.sortedBy { it.start }
}

// ---------- Go ----------

// The 25 reserved words, plus the predeclared types and builtin functions. Like the JS/TS
// lexer (which colours `boolean`/`number`/etc.), folding the predeclared vocabulary into the
// keyword set gives the at-a-glance "what's a type, what's a call" read without a full parser.
private val GO_KEYWORDS = setOf(
    // Reserved words
    "break", "case", "chan", "const", "continue", "default", "defer", "else", "fallthrough",
    "for", "func", "go", "goto", "if", "import", "interface", "map", "package", "range",
    "return", "select", "struct", "switch", "type", "var",
    // Predeclared types
    "bool", "byte", "complex64", "complex128", "error", "float32", "float64", "int", "int8",
    "int16", "int32", "int64", "rune", "string", "uint", "uint8", "uint16", "uint32", "uint64",
    "uintptr", "any", "comparable",
    // Builtin functions
    "append", "cap", "clear", "close", "complex", "copy", "delete", "imag", "len", "make",
    "max", "min", "new", "panic", "print", "println", "real", "recover",
)

// Predeclared constants. Coloured as literals (like JSON's true/false/null) rather than keywords.
private val GO_LITERAL = Regex("""\b(?:true|false|nil|iota)\b""")
// Raw string literals are backtick-delimited, span lines, and contain no escapes — a backslash
// is just a backslash, so (unlike a JS template literal) we match everything up to the next `.
// A plain starred character class is safe to do with a regex; it compiles to a flat loop.
private val GO_RAW_STRING = Regex("""`[^`]*`""")
// A rune literal holds a single character or escape. [singleQuoted] stops at the first unescaped
// closing quote, so `'a' + 'b'` is two runes, while `'\n'` and `'ÿ'` stay whole.
// Hex (incl. p-exponent hex floats), binary, octal (0o), and decimal/float forms, each with an
// optional imaginary `i` suffix and `_` digit separators.
private val GO_NUMBER = Regex(
    """\b(?:0[xX][0-9a-fA-F_]+(?:\.[0-9a-fA-F_]*)?(?:[pP][+-]?\d+)?|0[bB][01_]+|0[oO][0-7_]+|\d[\d_]*(?:\.\d[\d_]*)?(?:[eE][+-]?\d+)?)i?\b""",
)

fun tokenizeGo(text: String): List<Token> {
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
    // Comments and strings first — they swallow keywords/numbers inside.
    KOTLIN_BLOCK_COMMENT.findAll(text).forEach { add(it.range.first, it.range.last + 1, TokenKind.COMMENT) }
    KOTLIN_LINE_COMMENT.findAll(text).forEach { add(it.range.first, it.range.last + 1, TokenKind.COMMENT) }
    GO_RAW_STRING.findAll(text).forEach { add(it.range.first, it.range.last + 1, TokenKind.STRING) }
    doubleQuoted(text).forEach { add(it.first, it.last + 1, TokenKind.STRING) }
    singleQuoted(text).forEach { add(it.first, it.last + 1, TokenKind.STRING) }
    GO_NUMBER.findAll(text).forEach { add(it.range.first, it.range.last + 1, TokenKind.NUMBER) }
    GO_LITERAL.findAll(text).forEach { add(it.range.first, it.range.last + 1, TokenKind.LITERAL) }
    IDENT.findAll(text).forEach {
        val w = it.value
        if (w in GO_KEYWORDS) add(it.range.first, it.range.last + 1, TokenKind.KEYWORD)
    }
    return out.sortedBy { it.start }
}

// ---------- Python ----------

// The hard keywords of Python 3. `match`/`case` are soft keywords — outside a match statement they
// are ordinary identifiers (`re.match(...)`, a `case` variable), and a regex lexer can't tell the
// two apart, so highlighting them always would mis-colour common code. They're left out on purpose.
private val PYTHON_KEYWORDS = setOf(
    "and", "as", "assert", "async", "await", "break", "class", "continue", "def", "del",
    "elif", "else", "except", "finally", "for", "from", "global", "if", "import", "in",
    "is", "lambda", "nonlocal", "not", "or", "pass", "raise", "return", "try", "while",
    "with", "yield",
)

// True / False / None are the constants — coloured as literals (like Go's nil, JSON's null) rather
// than keywords.
private val PYTHON_LITERALS = setOf("True", "False", "None")

// Comments and strings in a single pass so the *leftmost* delimiter on a line wins: a `#` inside a
// string stays part of the string, and a quote inside a `# comment` stays part of the comment (a
// regex-overlap approach can only get one of those directions right). String prefixes (r/b/u/f and
// their two-letter combos) are consumed as part of the literal; the lookbehind stops us mistaking
// the tail of an identifier for a prefix. Triple-quoted forms are matched before the single-line
// forms so `"""..."""` isn't read as an empty `""` followed by more.
private val PYTHON_COMMENT_OR_STRING = Regex(
    "#[^\\n]*" +
        "|(?<![A-Za-z0-9_])[rRbBuUfF]{0,2}(?:" +
        "\"\"\"[\\s\\S]*?\"\"\"|'''[\\s\\S]*?'''|" +
        "\"(?:\\\\.|[^\"\\\\\\n])*\"|'(?:\\\\.|[^'\\\\\\n])*'" +
        ")",
)

// Hex / octal / binary / decimal / float / scientific, each with `_` digit separators and an
// optional imaginary `j` suffix.
private val PYTHON_NUMBER = Regex(
    """\b(?:0[xX][0-9a-fA-F_]+|0[oO][0-7_]+|0[bB][01_]+|\d[\d_]*(?:\.\d[\d_]*)?(?:[eE][+-]?\d+)?|\.\d[\d_]*(?:[eE][+-]?\d+)?)[jJ]?\b""",
)

// A decorator: `@name` (dotted, optionally under a module path) at the start of a line. Anchoring to
// line start keeps the matrix-multiply operator (`a @ b`, always mid-expression) out of it.
private val PYTHON_DECORATOR = Regex("""(?m)^[ \t]*(@[A-Za-z_][A-Za-z0-9_.]*)""")

fun tokenizePython(text: String): List<Token> {
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
    // Comments and strings first — they swallow keywords/numbers inside. The kind is disambiguated
    // by the match's first char: a comment always opens with `#`, a string with a prefix or quote.
    PYTHON_COMMENT_OR_STRING.findAll(text).forEach {
        val kind = if (text[it.range.first] == '#') TokenKind.COMMENT else TokenKind.STRING
        add(it.range.first, it.range.last + 1, kind)
    }
    PYTHON_DECORATOR.findAll(text).forEach {
        val g = it.groups[1] ?: return@forEach
        add(g.range.first, g.range.last + 1, TokenKind.EMPHASIS)
    }
    PYTHON_NUMBER.findAll(text).forEach { add(it.range.first, it.range.last + 1, TokenKind.NUMBER) }
    IDENT.findAll(text).forEach {
        when (it.value) {
            in PYTHON_LITERALS -> add(it.range.first, it.range.last + 1, TokenKind.LITERAL)
            in PYTHON_KEYWORDS -> add(it.range.first, it.range.last + 1, TokenKind.KEYWORD)
        }
    }
    return out.sortedBy { it.start }
}
