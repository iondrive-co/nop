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
    "go" -> ::tokenizeGo
    "json" -> ::tokenizeJson
    "md", "markdown" -> ::tokenizeMarkdown
    "js", "mjs", "cjs", "ts", "tsx", "jsx" -> ::tokenizeJsTs
    "sh", "bash", "zsh" -> ::tokenizeShell
    "yml", "yaml" -> ::tokenizeAnsible
    "j2", "jinja", "jinja2" -> ::tokenizeAnsible
    else -> null
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

// ---------- Ansible / YAML ----------

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
private val YAML_DOUBLE_STRING = Regex(""""(?:\\.|[^"\\\n])*"""")
private val YAML_SINGLE_STRING = Regex("""'(?:''|[^'\n])*'""")
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
    YAML_DOUBLE_STRING.findAll(text).forEach { add(it.range.first, it.range.last + 1, TokenKind.STRING) }
    YAML_SINGLE_STRING.findAll(text).forEach { add(it.range.first, it.range.last + 1, TokenKind.STRING) }
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
// is just a backslash, so (unlike the JS template regex) we match everything up to the next `.
private val GO_RAW_STRING = Regex("""`[^`]*`""")
// A rune literal holds a single character or escape. The reluctant body stops at the first
// closing quote so `'a' + 'b'` is two runes, while `'\n'` and `'ÿ'` stay whole.
private val GO_RUNE = Regex("""'(?:\\[^\n]|[^'\\\n])*?'""")
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
    KOTLIN_STRING.findAll(text).forEach { add(it.range.first, it.range.last + 1, TokenKind.STRING) }
    GO_RUNE.findAll(text).forEach { add(it.range.first, it.range.last + 1, TokenKind.STRING) }
    GO_NUMBER.findAll(text).forEach { add(it.range.first, it.range.last + 1, TokenKind.NUMBER) }
    GO_LITERAL.findAll(text).forEach { add(it.range.first, it.range.last + 1, TokenKind.LITERAL) }
    IDENT.findAll(text).forEach {
        val w = it.value
        if (w in GO_KEYWORDS) add(it.range.first, it.range.last + 1, TokenKind.KEYWORD)
    }
    return out.sortedBy { it.start }
}
