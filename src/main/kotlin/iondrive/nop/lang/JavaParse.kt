package iondrive.nop.lang

import com.sun.source.tree.CompilationUnitTree
import com.sun.source.tree.Tree
import com.sun.source.util.JavacTask
import com.sun.source.util.SourcePositions
import com.sun.source.util.Trees
import iondrive.nop.Log
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import javax.tools.Diagnostic
import javax.tools.DiagnosticCollector
import javax.tools.JavaCompiler
import javax.tools.JavaFileObject
import javax.tools.SimpleJavaFileObject
import javax.tools.StandardJavaFileManager
import javax.tools.ToolProvider

/**
 * Java source parsing, via the javac that ships inside nop's own runtime.
 *
 * This is deliberately *parse only* — [JavacTask.parse] and nothing after it. Parsing needs no
 * classpath, so it works on any file in any project the moment it is opened, with no build-system
 * import, no dependency resolution and no background compile. What it costs is types: the tree
 * knows that `foo.bar()` is a method call on something called `foo`, but not what `foo` is. Every
 * feature built on this file is designed around that line — see [JavaSymbols], [JavaUsages] and
 * [JavaRename] for where it holds and where they decline to guess.
 *
 * Requires the `jdk.compiler` module, which build.gradle.kts adds to the packaged runtime. When
 * it is missing (someone running the jar on a bare JRE) [available] is false and every entry point
 * returns null, so the editor simply behaves as it did before any of this existed.
 */
object JavaParse {
    private val warnedMissing = AtomicBoolean(false)

    /** True when this runtime carries a compiler to parse with. */
    val available: Boolean
        get() = ToolProvider.getSystemJavaCompiler() != null

    /**
     * Parses [text] as the contents of [fileName], or null when there is no compiler or javac threw
     * outright. A file with syntax errors still parses: javac recovers, so the result carries both a
     * usable (if patchy) tree and the [ParsedJava.problems] describing what it could not read.
     *
     * Safe off the EDT and safe to call concurrently — each call builds its own task and file
     * manager, sharing nothing.
     */
    fun parse(text: String, fileName: String = "Buffer.java"): ParsedJava? {
        val compiler = ToolProvider.getSystemJavaCompiler()
        if (compiler == null) {
            // Once per process: this is a packaging fault, not a per-file one, and a project full
            // of Java files would otherwise write a line per file per keystroke.
            if (warnedMissing.compareAndSet(false, true)) {
                Log.warn("no system Java compiler on this runtime — Java analysis is off")
            }
            return null
        }
        return runCatching {
            val collector = DiagnosticCollector<JavaFileObject>()
            val source = StringSource(fileName, text)
            val task = compiler.getTask(
                null,
                fileManager(compiler),
                collector,
                // -proc:none keeps annotation processors out of a parse that will never run them;
                // -nowarn drops the lint noise we have no way to act on.
                listOf("-proc:none", "-nowarn"),
                null,
                listOf(source),
            ) as JavacTask
            val unit = task.parse().firstOrNull() ?: return@runCatching null
            val positions = Trees.instance(task).sourcePositions
            ParsedJava(unit, positions, collector.problems(text), text)
        }.getOrElse { t ->
            // A parser that throws must cost one file's analysis, never the editor. The same
            // argument tokenizerForExtension makes for highlighting: errors as well as exceptions,
            // because a runaway parse can arrive as a StackOverflowError.
            Log.error("Java parse failed for $fileName (${text.length} chars)", t)
            null
        }
    }

    /** Diagnostics as text offsets, anchored to something the editor can actually draw under. */
    private fun DiagnosticCollector<JavaFileObject>.problems(text: String): List<JavaProblem> =
        diagnostics.mapNotNull { d ->
            if (d.kind != Diagnostic.Kind.ERROR) return@mapNotNull null
            val rawStart = d.startPosition
            val rawEnd = d.endPosition
            val start = when {
                rawStart >= 0 -> rawStart
                d.position >= 0 -> d.position
                else -> return@mapNotNull null
            }.toInt().coerceIn(0, text.length)
            val end = if (rawEnd > rawStart) rawEnd.toInt().coerceIn(start, text.length) else start
            val (from, to) = anchor(text, start, end)
            JavaProblem(from, to, d.getMessage(null) ?: "syntax error")
        }.distinctBy { it.start to it.endExclusive }

    /**
     * Widens a diagnostic's position onto something visible.
     *
     * javac reports a missing token at the position where it *should have been* — which is the
     * whitespace or the line break after the token that came before it. Underlining that is
     * underlining nothing: the range has no width on screen, so `';' expected` produced a bar
     * message, a mark in the scrollbar lane, and no squiggle anywhere in the file. Anchoring back
     * onto the previous token puts the underline where the user has to look anyway, which is what
     * every other editor does with the same class of error.
     *
     * A diagnostic that already covers real text is left exactly as javac reported it.
     */
    private fun anchor(text: String, start: Int, end: Int): Pair<Int, Int> {
        val hasContent = (start until end).any { it < text.length && !text[it].isWhitespace() }
        if (hasContent) return start to end

        var tokenEnd = start
        while (tokenEnd > 0 && text[tokenEnd - 1].isWhitespace()) tokenEnd--
        if (tokenEnd == 0) {
            // Nothing precedes it — an error on the first token of the file. Fall forward instead.
            val to = (maxOf(end, start + 1)).coerceAtMost(text.length)
            return start.coerceAtMost(maxOf(0, to - 1)) to maxOf(to, 1).coerceAtMost(maxOf(text.length, 1))
        }
        var tokenStart = tokenEnd - 1
        // Take the whole preceding word when it is one, so the underline sits under `foo` rather
        // than under its last letter.
        if (JavaLexer.isIdentifierPart(text[tokenStart])) {
            while (tokenStart > 0 && JavaLexer.isIdentifierPart(text[tokenStart - 1])) tokenStart--
        }
        return tokenStart to tokenEnd
    }

    /**
     * A file manager per thread, reused across parses.
     *
     * Passing null here would work, but javac then builds a fresh StandardJavaFileManager for every
     * single file — and building one means locating and reading the platform class path, which for a
     * parse-only run is pure overhead paid once per file. Reusing one cuts a whole-project index
     * roughly in half. They are not thread-safe, hence one per thread rather than one shared: the
     * index build runs the parses in parallel, and two threads inside the same manager corrupt each
     * other's caches. The pool that runs those parses is bounded, so this holds a bounded number of
     * managers for the life of the process, which is the trade being made for the time saved.
     */
    private val fileManagers = ThreadLocal.withInitial<StandardJavaFileManager?> { null }

    private fun fileManager(compiler: JavaCompiler): StandardJavaFileManager? {
        fileManagers.get()?.let { return it }
        val made = runCatching { compiler.getStandardFileManager(null, null, Charsets.UTF_8) }.getOrNull()
        if (made != null) fileManagers.set(made)
        return made
    }

    /** javac reads sources through this interface; ours is a String already in memory. */
    private class StringSource(name: String, private val text: String) :
        SimpleJavaFileObject(URI.create("nop:///${name.substringAfterLast('/')}"), JavaFileObject.Kind.SOURCE) {
        override fun getCharContent(ignoreEncodingErrors: Boolean): CharSequence = text
    }
}

/** A syntax error javac reported, as a half-open offset range into the parsed text. */
data class JavaProblem(val start: Int, val endExclusive: Int, val message: String)

/**
 * A parsed Java file: the tree, and the mapping from its nodes back to offsets in [text].
 *
 * Positions are the whole point of holding [unit] and [positions] together — a tree node on its own
 * cannot say where it came from, and every caller here needs to underline, jump to, or rewrite the
 * exact characters behind a node.
 */
class ParsedJava(
    val unit: CompilationUnitTree,
    val positions: SourcePositions,
    val problems: List<JavaProblem>,
    val text: String,
) {
    /** Start offset of [tree], or -1 when javac kept no position for it. */
    fun startOf(tree: Tree): Int = positions.getStartPosition(unit, tree).toInt()

    /** End offset (exclusive) of [tree], or -1 when javac kept no position for it. */
    fun endOf(tree: Tree): Int = positions.getEndPosition(unit, tree).toInt()

    /** The package this file declares, or "" for the default package. */
    val packageName: String
        get() = unit.packageName?.toString() ?: ""

    /** 1-based line number for a text [offset]. */
    fun lineAt(offset: Int): Int {
        if (offset <= 0) return 1
        var line = 1
        val end = offset.coerceAtMost(text.length)
        for (i in 0 until end) if (text[i] == '\n') line++
        return line
    }
}
