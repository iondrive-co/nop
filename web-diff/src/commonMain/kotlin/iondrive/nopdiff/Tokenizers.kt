package iondrive.nopdiff

// ---------------------------------------------------------------------------
// Syntax tokenizers — ported from nop's iondrive.nop.ui.SyntaxHighlight, with
// the Compose styling stripped (TokenKind → CSS class in the renderer) and the
// inline regex flags `(?m)`/`(?ms)` rewritten as RegexOption, since the JS
// RegExp engine rejects inline flag groups.
//
// These run per displayed line. Single-line constructs (keywords, strings,
// numbers, line comments) highlight correctly; multi-line constructs (block
// comments, fenced code, triple-quoted strings) are not detected line-by-line.
// ---------------------------------------------------------------------------

enum class TokenKind { KEYWORD, STRING, COMMENT, NUMBER, LITERAL, PUNCT, HEADING, EMPHASIS }

data class Token(val start: Int, val endExclusive: Int, val kind: TokenKind)

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

// ---------- shared regex ----------

private val KOTLIN_LINE_COMMENT = Regex("""//[^\n]*""")
private val KOTLIN_BLOCK_COMMENT = Regex("""/\*[\s\S]*?\*/""")
private val KOTLIN_TRIPLE_STRING = Regex("\"\"\"[\\s\\S]*?\"\"\"")
private val KOTLIN_STRING = Regex(""""(?:\\.|[^"\\\n])*"""")
private val KOTLIN_CHAR = Regex("""'(?:\\.|[^'\\\n])'""")
private val IDENT = Regex("""[A-Za-z_][A-Za-z0-9_]*""")
private val NUMBER_RE = Regex("""\b\d[\d_]*(?:\.\d[\d_]*)?(?:[eE][+-]?\d+)?[fFLuU]?\b""")

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

private val MD_HEADING = Regex("""^#{1,6} [^\n]*""", RegexOption.MULTILINE)
private val MD_FENCED_CODE = Regex("""^```[\s\S]*?^```""", RegexOption.MULTILINE)
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

private val ANSIBLE_KEYWORDS = setOf(
    "name", "hosts", "tasks", "handlers", "vars", "vars_files", "vars_prompt", "roles",
    "pre_tasks", "post_tasks", "tags", "when", "register", "become", "become_user",
    "become_method", "delegate_to", "delegate_facts", "run_once", "no_log", "ignore_errors",
    "changed_when", "failed_when", "until", "retries", "delay", "loop", "with_items",
    "with_dict", "with_fileglob", "with_lines", "with_subelements", "with_together",
    "notify", "listen", "block", "rescue", "always", "any_errors_fatal", "max_fail_percentage",
    "serial", "strategy", "gather_facts", "environment", "check_mode", "diff", "remote_user",
    "command", "shell", "raw", "script", "copy", "template", "file", "lineinfile", "blockinfile",
    "stat", "fetch", "synchronize", "unarchive", "git", "uri", "get_url", "package",
    "apt", "apt_repository", "apt_key", "yum", "yum_repository", "dnf", "pip",
    "service", "systemd", "user", "group", "mount", "cron", "find", "replace",
    "set_fact", "debug", "fail", "assert", "wait_for", "wait_for_connection",
    "include", "include_tasks", "import_tasks", "include_role", "import_role",
    "include_playbook", "import_playbook", "include_vars",
    "meta", "pause", "ping", "setup",
    "connection", "port", "ansible_host", "ansible_user", "ansible_port",
)

private val YAML_LINE_COMMENT = Regex("""#[^\n]*""", RegexOption.MULTILINE)
private val YAML_DOUBLE_STRING = Regex(""""(?:\\.|[^"\\\n])*"""")
private val YAML_SINGLE_STRING = Regex("""'(?:''|[^'\n])*'""")
private val JINJA_BLOCK = Regex("""\{\{[\s\S]*?\}\}|\{%[\s\S]*?%\}|\{#[\s\S]*?#\}""")
private val YAML_KEY = Regex("""^\s*(?:-\s+)?([A-Za-z_][\w.-]*)(?=\s*:(?:\s|$))""", RegexOption.MULTILINE)
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
    YAML_LINE_COMMENT.findAll(text).forEach { add(it.range.first, it.range.last + 1, TokenKind.COMMENT) }
    YAML_DOUBLE_STRING.findAll(text).forEach { add(it.range.first, it.range.last + 1, TokenKind.STRING) }
    YAML_SINGLE_STRING.findAll(text).forEach { add(it.range.first, it.range.last + 1, TokenKind.STRING) }
    JINJA_BLOCK.findAll(text).forEach { add(it.range.first, it.range.last + 1, TokenKind.EMPHASIS) }
    YAML_KEY.findAll(text).forEach { m ->
        // MatchGroup.range is JVM-only; locate the captured key within the whole match instead.
        val name = m.groupValues.getOrNull(1).orEmpty()
        if (name.isNotEmpty() && name in ANSIBLE_KEYWORDS) {
            val rel = m.value.indexOf(name)
            if (rel >= 0) {
                val start = m.range.first + rel
                add(start, start + name.length, TokenKind.KEYWORD)
            }
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

private val SHELL_COMMENT = Regex("""#[^\n]*""", RegexOption.MULTILINE)
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

private val GO_KEYWORDS = setOf(
    "break", "case", "chan", "const", "continue", "default", "defer", "else", "fallthrough",
    "for", "func", "go", "goto", "if", "import", "interface", "map", "package", "range",
    "return", "select", "struct", "switch", "type", "var",
    "bool", "byte", "complex64", "complex128", "error", "float32", "float64", "int", "int8",
    "int16", "int32", "int64", "rune", "string", "uint", "uint8", "uint16", "uint32", "uint64",
    "uintptr", "any", "comparable",
    "append", "cap", "clear", "close", "complex", "copy", "delete", "imag", "len", "make",
    "max", "min", "new", "panic", "print", "println", "real", "recover",
)

private val GO_LITERAL = Regex("""\b(?:true|false|nil|iota)\b""")
private val GO_RAW_STRING = Regex("""`[^`]*`""")
private val GO_RUNE = Regex("""'(?:\\[^\n]|[^'\\\n])*?'""")
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
