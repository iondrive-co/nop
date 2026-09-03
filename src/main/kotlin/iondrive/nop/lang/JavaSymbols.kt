package iondrive.nop.lang

import com.sun.source.tree.ClassTree
import com.sun.source.tree.MethodTree
import com.sun.source.tree.Tree
import com.sun.source.tree.VariableTree
import javax.lang.model.element.Modifier

/** What a [JavaDecl] declares. */
enum class JavaDeclKind { TYPE, METHOD, FIELD }

/**
 * One declaration in a Java file, with the exact offsets of the declared *name*.
 *
 * [owner] is the fully-qualified name of the enclosing type, or the package for a top-level type,
 * so a nested class reads as `com.example.Outer` and its methods as `com.example.Outer.Inner`. That
 * makes [fqn] unique per type and lets a member be attributed to the type it belongs to — which is
 * what stops "find usages of `size`" returning every `size` in the project.
 */
data class JavaDecl(
    val name: String,
    val kind: JavaDeclKind,
    val owner: String,
    val line: Int,
    /** Half-open offsets of the declared name itself — what a rename rewrites. */
    val nameStart: Int,
    val nameEnd: Int,
    val isPrivate: Boolean,
    /** Parameter count for a [JavaDeclKind.METHOD]; -1 for anything else. */
    val arity: Int = -1,
) {
    /** Dotted name: the type's FQN, or the owner-qualified name of a member. */
    val fqn: String get() = if (owner.isEmpty()) name else "$owner.$name"
}

/**
 * Turns a parse tree into the declarations worth indexing: types, methods and fields.
 *
 * Method bodies are deliberately not descended into. A local variable is not a cross-file symbol
 * and has no business in a project-wide index — [JavaUsages] finds locals by scanning the one method
 * that can see them, which is both cheaper and more accurate than indexing them would be.
 *
 * Structure comes from the tree; the character positions of each name come from [JavaLexer], because
 * javac's public positions cover a whole declaration (annotations, modifiers, return type and all)
 * rather than the identifier inside it. A declaration whose name can't be located in the source —
 * anything javac synthesised, which has no source position at all — is skipped rather than guessed
 * at, so nothing downstream ever rewrites a range that isn't really there.
 */
object JavaSymbols {
    /** Every indexable declaration in [parsed], in source order. */
    fun declarations(parsed: ParsedJava): List<JavaDecl> {
        val text = parsed.text
        val identifiers = JavaLexer.identifiers(text)
        val out = mutableListOf<JavaDecl>()
        for (decl in parsed.unit.typeDecls) {
            if (decl is ClassTree) visitType(parsed, identifiers, decl, parsed.packageName, out)
        }
        return out
    }

    private fun visitType(
        parsed: ParsedJava,
        identifiers: List<IntRange>,
        tree: ClassTree,
        owner: String,
        out: MutableList<JavaDecl>,
    ) {
        val name = tree.simpleName.toString()
        // An anonymous class has an empty name and nothing to index; its members belong to nobody
        // a caller could name, so the whole subtree is skipped.
        if (name.isEmpty()) return
        val body = bodyStart(parsed, tree)
        val range = findName(
            parsed, identifiers, name,
            from = searchStart(parsed, tree, tree.modifiers),
            limit = if (body >= 0) body + 1 else parsed.endOf(tree),
            follows = TYPE_FOLLOWERS,
        )
        val fqn = if (owner.isEmpty()) name else "$owner.$name"
        if (range != null) {
            out += JavaDecl(
                name = name,
                kind = JavaDeclKind.TYPE,
                owner = owner,
                line = parsed.lineAt(range.first),
                nameStart = range.first,
                nameEnd = range.last + 1,
                isPrivate = tree.modifiers.flags.contains(Modifier.PRIVATE),
            )
        }
        // Members are visited whether or not the type's own name was locatable: a type nop couldn't
        // position is still a type whose methods are worth finding.
        for (member in tree.members) {
            when (member) {
                is ClassTree -> visitType(parsed, identifiers, member, fqn, out)
                is MethodTree -> visitMethod(parsed, identifiers, member, tree, fqn, out)
                is VariableTree -> visitField(parsed, identifiers, member, fqn, out)
                else -> Unit
            }
        }
    }

    private fun visitMethod(
        parsed: ParsedJava,
        identifiers: List<IntRange>,
        tree: MethodTree,
        enclosing: ClassTree,
        owner: String,
        out: MutableList<JavaDecl>,
    ) {
        // javac names every constructor `<init>`; in source it carries the class's simple name.
        val declared = tree.name.toString()
        val name = if (declared == "<init>") enclosing.simpleName.toString() else declared
        if (name.isEmpty()) return
        val bodyStart = tree.body?.let { parsed.startOf(it) } ?: -1
        val range = findName(
            parsed, identifiers, name,
            from = searchStart(parsed, tree, tree.modifiers),
            limit = if (bodyStart >= 0) bodyStart else parsed.endOf(tree),
            follows = METHOD_FOLLOWERS,
        ) ?: return
        out += JavaDecl(
            name = name,
            kind = JavaDeclKind.METHOD,
            owner = owner,
            line = parsed.lineAt(range.first),
            nameStart = range.first,
            nameEnd = range.last + 1,
            isPrivate = tree.modifiers.flags.contains(Modifier.PRIVATE),
            arity = tree.parameters.size,
        )
    }

    private fun visitField(
        parsed: ParsedJava,
        identifiers: List<IntRange>,
        tree: VariableTree,
        owner: String,
        out: MutableList<JavaDecl>,
    ) {
        val name = tree.name.toString()
        if (name.isEmpty()) return
        val range = findName(
            parsed, identifiers, name,
            from = searchStart(parsed, tree, tree.modifiers),
            limit = parsed.endOf(tree),
            follows = FIELD_FOLLOWERS,
        ) ?: return
        out += JavaDecl(
            name = name,
            kind = JavaDeclKind.FIELD,
            owner = owner,
            line = parsed.lineAt(range.first),
            nameStart = range.first,
            nameEnd = range.last + 1,
            isPrivate = tree.modifiers.flags.contains(Modifier.PRIVATE),
        )
    }

    /**
     * Where to start looking for a declaration's name: just past its modifiers and annotations.
     *
     * This is what keeps `@Deprecated class Deprecated` honest — starting at the declaration's own
     * offset would find the annotation's name first and rename the wrong four characters. When javac
     * kept no position for the modifiers (there were none) the declaration's start is as good.
     */
    private fun searchStart(parsed: ParsedJava, tree: Tree, modifiers: Tree?): Int {
        val afterModifiers = modifiers?.let { parsed.endOf(it) } ?: -1
        if (afterModifiers >= 0) return afterModifiers
        return parsed.startOf(tree).coerceAtLeast(0)
    }

    /** Offset of the `{` opening [tree]'s body, or -1. */
    private fun bodyStart(parsed: ParsedJava, tree: ClassTree): Int {
        val first = tree.members.firstOrNull()?.let { parsed.startOf(it) } ?: -1
        if (first >= 0) return first
        return parsed.endOf(tree)
    }

    /**
     * The declared name's own range: the first identifier token in `[from, limit)` that reads as
     * [name] *and* is followed by punctuation consistent with the kind of declaration being read.
     *
     * The follower check is what makes this exact rather than a guess. A method's name is the one
     * followed by `(`; a field's is followed by `=`, `;`, `,`, `[` or (for an enum constant) `(`; a
     * type's is followed by its body, its type parameters, its record header or one of the clauses
     * that can precede the body. In `Foo Foo = new Foo()` only the second `Foo` is followed by `=`,
     * so the field's name is found and its type is not — which is the case that a plain "first
     * occurrence of the name" search gets wrong every time.
     */
    private fun findName(
        parsed: ParsedJava,
        identifiers: List<IntRange>,
        name: String,
        from: Int,
        limit: Int,
        follows: Set<Char>,
    ): IntRange? {
        if (from < 0) return null
        val text = parsed.text
        val end = if (limit in 0..text.length) limit else text.length
        var index = identifiers.binarySearchFirstAtOrAfter(from)
        while (index < identifiers.size) {
            val range = identifiers[index]
            if (range.first >= end) return null
            index++
            if (range.last + 1 - range.first != name.length) continue
            if (!text.regionMatches(range.first, name, 0, name.length)) continue
            val next = JavaLexer.skipTrivia(text, range.last + 1)
            if (next < 0) continue
            if (text[next] in follows) return range
            // A type's name can also be followed by a word — `extends`, `implements`, `permits` —
            // rather than punctuation, which is the one case a single character can't answer.
            if (follows === TYPE_FOLLOWERS) {
                val word = JavaLexer.identifierAt(text, next)
                if (word != null && text.substring(word.first, word.last + 1) in TYPE_FOLLOWER_WORDS) {
                    return range
                }
            }
        }
        return null
    }

    /** Index of the first range starting at or after [offset]; [size] when there is none. */
    private fun List<IntRange>.binarySearchFirstAtOrAfter(offset: Int): Int {
        var low = 0
        var high = size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (this[mid].first < offset) low = mid + 1 else high = mid
        }
        return low
    }

    private val TYPE_FOLLOWERS = setOf('{', '<', '(')
    private val TYPE_FOLLOWER_WORDS = setOf("extends", "implements", "permits")
    private val METHOD_FOLLOWERS = setOf('(')
    private val FIELD_FOLLOWERS = setOf('=', ';', ',', '[', '(')
}
