package iondrive.nop.lang

/**
 * A Java file's package declaration and imports, read without a full parse.
 *
 * This is what makes "find usages of a type" exact rather than a word search. Two projects' `Widget`
 * classes are different types, and the only evidence available without a classpath is right here: a
 * file that imports `com.other.Widget` is not talking about `com.ours.Widget`, however many times the
 * word appears in it.
 *
 * Read lexically rather than from a parse tree because it runs over every candidate file in the
 * project, and a file's header is the cheapest possible thing to establish — imports must precede the
 * first type declaration, so the scan stops as soon as one begins.
 */
data class JavaHeader(
    val packageName: String,
    /** Fully-qualified single-type imports, e.g. `com.example.Widget`. */
    val imports: List<String>,
    /** On-demand imports without their `.*`, e.g. `com.example` for `import com.example.*;`. */
    val wildcardPackages: List<String>,
) {
    /**
     * The fully-qualified type a bare [simpleName] resolves to via an import, or null when no import
     * names it. A wildcard import can't answer this — it says a package might contain the name, not
     * that it does — so only single-type imports count.
     */
    fun importedFqnFor(simpleName: String): String? =
        imports.firstOrNull { it.substringAfterLast('.') == simpleName }

    /**
     * Whether a file with this header could be referring to [target] when it writes the type's simple
     * name.
     *
     * True when the type is imported by name, when the file is in the type's own package, or when a
     * wildcard import covers that package. A single-type import of a *different* type with the same
     * simple name is decisive the other way: that file's `Widget` is provably someone else's.
     */
    fun canSee(target: UsageTarget.Type): Boolean {
        val other = importedFqnFor(target.name)
        if (other != null) return other == target.fqn || other == target.outermostFqn
        if (packageName == target.packageName) return true
        if (target.packageName in wildcardPackages) return true
        // A nested type is normally reached through its outer one — `import com.x.Outer;` then
        // `Outer.Inner` — so the outer type's visibility is the nested type's visibility.
        val outer = target.outermostFqn
        if (outer != target.fqn && imports.any { it == outer }) return true
        return false
    }

    companion object {
        /** Reads the header of [text]. Stops at the first type declaration. */
        fun of(text: String): JavaHeader {
            var packageName = ""
            val imports = mutableListOf<String>()
            val wildcards = mutableListOf<String>()
            var index = 0
            JavaLexer.forEachIdentifier(text) { start, end ->
                if (index < 0) return@forEachIdentifier
                when (text.substring(start, end)) {
                    "package" -> packageName = statementAfter(text, end)?.takeIf { it.isNotEmpty() } ?: ""
                    "import" -> {
                        val body = statementAfter(text, end) ?: return@forEachIdentifier
                        // `import static com.x.Y.member;` names a member, not a type; the type it
                        // came from is the part before the last dot.
                        val stripped = body.removePrefix("static").trim()
                        when {
                            stripped.endsWith(".*") -> wildcards += stripped.removeSuffix(".*")
                            stripped.isNotEmpty() -> imports += stripped
                        }
                    }
                    // The header is over the moment a type opens; nothing after this can be an import.
                    "class", "interface", "enum", "record" -> index = -1
                }
            }
            return JavaHeader(packageName, imports, wildcards)
        }

        /**
         * The text between [from] and the next `;`, with whitespace and comments squeezed out, or
         * null when there is no terminator. `com . example . Widget` and a name split over two lines
         * both come back as `com.example.Widget`.
         */
        private fun statementAfter(text: String, from: Int): String? {
            val end = text.indexOf(';', from)
            if (end < 0) return null
            val body = StringBuilder()
            var i = from
            while (i < end) {
                val c = text[i]
                when {
                    c == '/' && i + 1 < end && text[i + 1] == '/' -> {
                        while (i < end && text[i] != '\n') i++
                    }
                    c == '/' && i + 1 < end && text[i + 1] == '*' -> {
                        i += 2
                        while (i + 1 < end && !(text[i] == '*' && text[i + 1] == '/')) i++
                        i += 2
                    }
                    c.isWhitespace() -> {
                        // Keep one space so `static com.x.Y` doesn't collapse into `staticcom.x.Y`.
                        if (body.isNotEmpty() && body.last() != ' ' && body.last() != '.') body.append(' ')
                        i++
                    }
                    else -> {
                        // A space before a dot is noise: `com . example` is one dotted name.
                        if (c == '.' && body.isNotEmpty() && body.last() == ' ') body.setLength(body.length - 1)
                        body.append(c)
                        i++
                    }
                }
            }
            return body.toString().trim()
        }
    }
}

/** The outermost enclosing type's FQN — for `com.x.Outer.Inner`, `com.x.Outer`. */
val UsageTarget.Type.outermostFqn: String
    get() {
        if (packageName.isEmpty()) return fqn.substringBefore('.')
        val withoutPackage = fqn.removePrefix("$packageName.")
        return "$packageName.${withoutPackage.substringBefore('.')}"
    }
