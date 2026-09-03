package iondrive.nop.lang

import com.sun.source.tree.BlockTree
import com.sun.source.tree.CatchTree
import com.sun.source.tree.EnhancedForLoopTree
import com.sun.source.tree.ForLoopTree
import com.sun.source.tree.LambdaExpressionTree
import com.sun.source.tree.MethodTree
import com.sun.source.tree.TryTree
import com.sun.source.tree.VariableTree
import com.sun.source.util.TreeScanner
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/**
 * What the user asked for the usages of.
 *
 * The distinction between these is the distinction between what a parse tree can prove and what it
 * can only guess, and it decides both how far the search has to look and how much the answer can be
 * trusted. A local is provably confined to one method; a private member to one file; a type is
 * pinned down by the imports that name it. A public member is the one case with no answer without a
 * classpath, and it is marked as such rather than dressed up as one — see [UsageResult.exact].
 */
sealed interface UsageTarget {
    /** The simple name being searched for. */
    val name: String

    /** A description for the panel header, e.g. "method greet in com.example.Greeter". */
    val description: String

    /**
     * A local variable or a parameter. Nothing outside [scope] can refer to it, and any nested
     * redeclaration of the same name inside [shadowedBy] belongs to that declaration, not this one.
     */
    data class Local(
        override val name: String,
        val scope: IntRange,
        val shadowedBy: List<IntRange>,
    ) : UsageTarget {
        override val description: String get() = "local $name"
    }

    /** A field or method declared in a type. */
    data class Member(
        override val name: String,
        val owner: String,
        val kind: JavaDeclKind,
        val isPrivate: Boolean,
    ) : UsageTarget {
        override val description: String
            get() = "${if (kind == JavaDeclKind.METHOD) "method" else "field"} $name in $owner"
    }

    /** A class, interface, enum, record or annotation type. */
    data class Type(
        override val name: String,
        val fqn: String,
        val packageName: String,
    ) : UsageTarget {
        override val description: String get() = "type $fqn"
    }
}

/** One occurrence of the searched-for name, with both the offsets to rewrite and the line to show. */
data class JavaUsage(
    /** Project-relative, forward-slashed. */
    val path: String,
    val line: Int,
    val lineText: String,
    /** Offsets of the name within [lineText] — what the results panel highlights. */
    val columnStart: Int,
    val columnEnd: Int,
    /** Offsets of the name within the whole file — what a rename rewrites. */
    val offsetStart: Int,
    val offsetEnd: Int,
    /** True for the occurrence that declares the thing, rather than referring to it. */
    val isDeclaration: Boolean,
)

/**
 * The usages found, and how much they can be trusted.
 *
 * [exact] is the honest half of this feature. When it is false, [note] says why in a sentence the
 * user can act on, and every caller that would *write* something — [JavaRename] — puts that sentence
 * in front of them before it touches a file.
 */
data class UsageResult(
    val target: UsageTarget,
    val usages: List<JavaUsage>,
    val exact: Boolean,
    val note: String? = null,
    val truncated: Boolean = false,
)

/**
 * Finds every reference to a declaration, using the parse tree for scope and the lexer for position.
 *
 * The precision ladder, and why each rung is where it is:
 *
 *  - **Locals and parameters — exact.** The tree says which block a declaration belongs to, so the
 *    search is confined to that block and to occurrences no nested redeclaration has taken over.
 *  - **Private members — exact.** Nothing outside the declaring file can name them, so one file is
 *    the whole search space.
 *  - **Types — exact.** A file's package and imports say whether the `Widget` it mentions is *this*
 *    `Widget`; a file that imports a different one, or declares its own, is excluded outright.
 *  - **Non-private members — approximate, and said so.** Whether `x.close()` calls this `close` or
 *    another one is a question about the type of `x`, and without a classpath there is no type of
 *    `x`. Occurrences that a local variable of the same name shadows are still excluded — that much
 *    the tree does prove — but what remains may include same-named members of unrelated types.
 */
object JavaUsages {
    /** Enough to fill a results panel; beyond this the answer is "narrow your search", not a list. */
    const val MAX_USAGES = 2000

    /**
     * What the caret at [offset] is on, or null when it is on nothing nameable.
     *
     * Resolution order is narrowest-first, which is also correctness-first: a name that is a local
     * *is* the local, whatever else in the project shares it, and only when nothing nearer explains
     * the name do the file's own declarations and then its imports get a say.
     */
    fun targetAt(parsed: ParsedJava, offset: Int): UsageTarget? {
        val range = JavaLexer.identifierAt(parsed.text, offset) ?: return null
        val name = parsed.text.substring(range.first, range.last + 1)
        if (name in JavaLexer.KEYWORDS) return null

        val declarations = JavaSymbols.declarations(parsed)

        // The caret is on a declaration's own name: no inference needed, we know exactly what it is.
        declarations.firstOrNull { range.first >= it.nameStart && range.last + 1 <= it.nameEnd }
            ?.let { return fromDecl(it, parsed) }

        localTarget(parsed, offset, name)?.let { return it }

        declarations.firstOrNull { it.name == name && it.kind != JavaDeclKind.TYPE }
            ?.let { return fromDecl(it, parsed) }
        declarations.firstOrNull { it.name == name && it.kind == JavaDeclKind.TYPE }
            ?.let { return fromDecl(it, parsed) }

        // Not declared here, so it is a type this file imported — or something whose declaration is
        // out of reach, in which case there is nothing honest to search for.
        val header = JavaHeader.of(parsed.text)
        header.importedFqnFor(name)?.let {
            return UsageTarget.Type(name, it, it.substringBeforeLast('.', ""))
        }
        return null
    }

    private fun fromDecl(decl: JavaDecl, parsed: ParsedJava): UsageTarget = when (decl.kind) {
        JavaDeclKind.TYPE -> UsageTarget.Type(decl.name, decl.fqn, parsed.packageName)
        else -> UsageTarget.Member(decl.name, decl.owner, decl.kind, decl.isPrivate)
    }

    /** The innermost local or parameter named [name] visible at [offset], or null. */
    private fun localTarget(parsed: ParsedJava, offset: Int, name: String): UsageTarget.Local? {
        val locals = collectLocals(parsed)
        val visible = locals.filter { it.name == name && offset in it.scope }
        // Innermost wins: the declaration nearest the caret is the one the caret can see.
        val chosen = visible.maxByOrNull { it.scope.first } ?: return null
        // Any *other* declaration of the same name opening inside our scope takes over from there.
        val shadows = locals
            .filter { it.name == name && it !== chosen && it.scope.first in chosen.scope }
            .map { it.scope }
        return UsageTarget.Local(name, chosen.scope, shadows)
    }

    /** A local declaration and the span of source that can see it. */
    private data class LocalDecl(val name: String, val scope: IntRange)

    /**
     * Every local and parameter in the file, each with the span that can refer to it: from its own
     * declaration to the end of the block, loop, catch, lambda or method that encloses it.
     *
     * Collected in one walk rather than by asking for each declaration's path separately — the same
     * reason the index parses each file once: the answer is needed for every name in the file, and a
     * per-name walk turns a linear pass into a quadratic one.
     */
    private fun collectLocals(parsed: ParsedJava): List<LocalDecl> {
        val out = mutableListOf<LocalDecl>()
        object : TreeScanner<Unit, IntRange?>() {
            private fun spanOf(tree: com.sun.source.tree.Tree): IntRange? {
                val start = parsed.startOf(tree)
                val end = parsed.endOf(tree)
                return if (start < 0 || end < start) null else start..end
            }

            override fun visitMethod(node: MethodTree, scope: IntRange?) =
                super.visitMethod(node, spanOf(node) ?: scope)

            override fun visitBlock(node: BlockTree, scope: IntRange?) =
                super.visitBlock(node, spanOf(node) ?: scope)

            override fun visitForLoop(node: ForLoopTree, scope: IntRange?) =
                super.visitForLoop(node, spanOf(node) ?: scope)

            override fun visitEnhancedForLoop(node: EnhancedForLoopTree, scope: IntRange?) =
                super.visitEnhancedForLoop(node, spanOf(node) ?: scope)

            override fun visitCatch(node: CatchTree, scope: IntRange?) =
                super.visitCatch(node, spanOf(node) ?: scope)

            override fun visitTry(node: TryTree, scope: IntRange?) =
                super.visitTry(node, spanOf(node) ?: scope)

            override fun visitLambdaExpression(node: LambdaExpressionTree, scope: IntRange?) =
                super.visitLambdaExpression(node, spanOf(node) ?: scope)

            override fun visitVariable(node: VariableTree, scope: IntRange?): Unit? {
                val declStart = parsed.startOf(node)
                // A field has no enclosing block; only variables inside one are locals.
                if (scope != null && declStart >= 0) {
                    // Visible from its own declaration, not from the top of the block: code above it
                    // in the same block refers to something else, or to nothing at all.
                    out += LocalDecl(node.name.toString(), declStart..scope.last)
                }
                return super.visitVariable(node, scope)
            }
        }.scan(parsed.unit, null)
        return out
    }

    /**
     * Every usage of [target] under [projectRoot], searching only the files that could hold one.
     *
     * [files] is the project's file index; only `.java` entries are considered, and of those only the
     * ones whose text actually contains the name are parsed. That prefilter is what keeps this
     * interactive on a large project: the parse is the expensive part, and most files have no reason
     * to pay for it.
     */
    suspend fun find(
        projectRoot: File,
        files: List<String>,
        target: UsageTarget,
        originPath: String?,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): UsageResult {
        val candidates = candidateFiles(files, target, originPath)
        val approximate = target is UsageTarget.Member && !target.isPrivate
        val note = if (!approximate) null else
            "Without the project's classpath nop can't tell this ${
                if ((target as UsageTarget.Member).kind == JavaDeclKind.METHOD) "method" else "field"
            } from a same-named one on another type, so this list may include occurrences that " +
                "aren't really it. Locals that shadow the name have been excluded."

        val found = withContext(dispatcher) {
            val parallelism = minOf(8, maxOf(2, Runtime.getRuntime().availableProcessors()))
            val buckets = Array(parallelism) { ArrayList<String>() }
            candidates.forEachIndexed { i, rel -> buckets[i % parallelism].add(rel) }
            coroutineScope {
                buckets.map { bucket ->
                    async {
                        val local = ArrayList<JavaUsage>()
                        for (rel in bucket) {
                            ensureActive()
                            scanFile(projectRoot, rel, target, local)
                        }
                        local
                    }
                }.awaitAll()
            }.flatten()
        }
        val ordered = found.sortedWith(compareBy({ it.path.lowercase() }, { it.line }, { it.columnStart }))
        val capped = if (ordered.size > MAX_USAGES) ordered.subList(0, MAX_USAGES) else ordered
        return UsageResult(
            target = target,
            usages = capped,
            exact = !approximate,
            note = note,
            truncated = ordered.size > MAX_USAGES,
        )
    }

    /** Which files are worth opening at all, given what kind of thing is being looked for. */
    private fun candidateFiles(
        files: List<String>,
        target: UsageTarget,
        originPath: String?,
    ): List<String> = when {
        // A local can't escape its method, let alone its file.
        target is UsageTarget.Local -> listOfNotNull(originPath)
        // Nothing outside the declaring file can name a private member.
        target is UsageTarget.Member && target.isPrivate -> listOfNotNull(originPath)
        else -> files.filter { it.endsWith(".java", ignoreCase = true) }
    }

    private fun scanFile(root: File, rel: String, target: UsageTarget, out: MutableList<JavaUsage>) {
        val file = File(root, rel)
        if (!file.isFile || file.length() > MAX_FILE_BYTES) return
        val text = runCatching { file.readText() }.getOrNull() ?: return
        // The prefilter that makes this cheap: no mention of the name, no reason to parse.
        if (!text.contains(target.name)) return
        val occurrences = JavaLexer.occurrencesOf(text, target.name)
        if (occurrences.isEmpty()) return
        val kept = filterOccurrences(text, rel, target, occurrences) ?: return
        if (kept.isEmpty()) return
        appendUsages(rel, text, kept, out)
    }

    /**
     * Narrows one file's raw name matches to the ones that really refer to [target], or null when the
     * file cannot refer to it at all.
     */
    private fun filterOccurrences(
        text: String,
        rel: String,
        target: UsageTarget,
        occurrences: List<IntRange>,
    ): List<Occurrence>? {
        when (target) {
            is UsageTarget.Local -> {
                // Confined to the declaration's own scope, minus anything a nested redeclaration of
                // the same name has taken over, minus `something.name` — a local is never selected
                // off an object.
                return occurrences
                    .filter { it.first in target.scope }
                    .filter { r -> target.shadowedBy.none { r.first in it } }
                    .filter { !isMemberSelected(text, it.first) }
                    .map { Occurrence(it, isDeclaration = it.first == target.scope.first) }
            }

            is UsageTarget.Type -> {
                val header = JavaHeader.of(text)
                if (!header.canSee(target)) return null
                val parsed = JavaParse.parse(text, rel.substringAfterLast('/'))
                val shadows = parsed?.let { p ->
                    collectLocals(p).filter { it.name == target.name }.map { it.scope }
                } ?: emptyList()
                val declared = parsed?.let { p ->
                    JavaSymbols.declarations(p)
                        .firstOrNull { it.kind == JavaDeclKind.TYPE && it.fqn == target.fqn }
                }
                return occurrences
                    .filter { r -> !isShadowed(text, r, shadows) }
                    .map { Occurrence(it, isDeclaration = declared != null && it.first == declared.nameStart) }
            }

            is UsageTarget.Member -> {
                val parsed = JavaParse.parse(text, rel.substringAfterLast('/'))
                // A local of the same name shadows the member wherever it is in scope — the one
                // narrowing the tree can prove for a member without knowing any types.
                val shadows = parsed?.let { p ->
                    collectLocals(p).filter { it.name == target.name }.map { it.scope }
                } ?: emptyList()
                val declared = parsed?.let { p ->
                    JavaSymbols.declarations(p).firstOrNull {
                        it.name == target.name && it.kind == target.kind && it.owner == target.owner
                    }
                }
                return occurrences
                    .filter { r -> !isShadowed(text, r, shadows) }
                    .map { Occurrence(it, isDeclaration = declared != null && it.first == declared.nameStart) }
            }
        }
    }

    /**
     * Whether a local declaration of the same name has taken over the name at [range].
     *
     * A selected name — the `label` in `this.label` — is never shadowed, however many locals called
     * `label` are in scope: the dot says outright that a member is being named, which is why
     * `this.field = field` is the standard way to write a constructor at all. Treating those as
     * shadowed was silently dropping the assignment in every constructor that follows the
     * convention, which is most of them.
     */
    private fun isShadowed(text: String, range: IntRange, shadows: List<IntRange>): Boolean {
        if (shadows.isEmpty()) return false
        if (isMemberSelected(text, range.first)) return false
        return shadows.any { range.first in it }
    }

    /** True when the identifier at [start] is preceded by a `.` — i.e. selected off something. */
    private fun isMemberSelected(text: String, start: Int): Boolean {
        var i = start - 1
        while (i >= 0 && text[i].isWhitespace()) i--
        return i >= 0 && text[i] == '.'
    }

    private data class Occurrence(val range: IntRange, val isDeclaration: Boolean)

    /**
     * Turns offsets into panel rows: the line number, the whole line to show, and where in it the
     * name sits.
     *
     * The line table is built once per file and then bisected, rather than re-scanning the text for
     * each occurrence. A name used two hundred times in one file is exactly the case where find
     * usages earns its keep, and it is also the case where per-occurrence rescanning turns the
     * answer quadratic.
     */
    private fun appendUsages(
        rel: String,
        text: String,
        occurrences: List<Occurrence>,
        out: MutableList<JavaUsage>,
    ) {
        if (occurrences.isEmpty()) return
        val lineStarts = ArrayList<Int>().apply {
            add(0)
            for (i in text.indices) if (text[i] == '\n') add(i + 1)
        }
        for (occurrence in occurrences.sortedBy { it.range.first }) {
            val start = occurrence.range.first
            val lineIndex = lineStarts.lineIndexFor(start)
            val lineStart = lineStarts[lineIndex]
            var lineEnd = if (lineIndex + 1 < lineStarts.size) lineStarts[lineIndex + 1] - 1 else text.length
            // Drop a trailing \r so a Windows line ending doesn't bleed into the row's text.
            if (lineEnd > lineStart && text[lineEnd - 1] == '\r') lineEnd--
            val end = (occurrence.range.last + 1).coerceAtMost(lineEnd)
            out += JavaUsage(
                path = rel,
                line = lineIndex + 1,
                lineText = text.substring(lineStart, lineEnd),
                columnStart = start - lineStart,
                columnEnd = end - lineStart,
                offsetStart = start,
                offsetEnd = occurrence.range.last + 1,
                isDeclaration = occurrence.isDeclaration,
            )
        }
    }

    /** Index of the line containing [offset]: the last line start at or before it. */
    private fun List<Int>.lineIndexFor(offset: Int): Int {
        var low = 0
        var high = size - 1
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            if (this[mid] <= offset) low = mid else high = mid - 1
        }
        return low
    }

    /** Same cutoff the search and the indexer use for "plausibly source". */
    private const val MAX_FILE_BYTES = 2L * 1024 * 1024
}
