package iondrive.nop.lang

/**
 * A rename that has been worked out but not yet performed.
 *
 * Everything a rename needs to be judged is here before a byte moves: which files it touches, how
 * many places in each, whether the file itself has to be renamed alongside its type, what would
 * break, and — the honest part — whether the occurrence list is provably complete. The UI puts this
 * in front of the user; nothing applies a plan the user hasn't seen.
 */
data class RenamePlan(
    val target: UsageTarget,
    val oldName: String,
    val newName: String,
    /** Ranges to replace, per project-relative path, ascending. */
    val edits: Map<String, List<IntRange>>,
    /** Set when the declaring file is named after the type and must follow it. */
    val fileRename: FileRename?,
    /** False when the underlying usage search couldn't prove it had found everything. */
    val exact: Boolean,
    /** Why the search is approximate, when it is — shown to the user before they confirm. */
    val note: String?,
    /** Reasons this rename must not go ahead. Non-empty means the plan cannot be applied. */
    val problems: List<String>,
) {
    val occurrenceCount: Int get() = edits.values.sumOf { it.size }
    val fileCount: Int get() = edits.size
    val canApply: Boolean get() = problems.isEmpty() && occurrenceCount > 0
}

/** A source file that has to be renamed because the type it is named after is being renamed. */
data class FileRename(val path: String, val newFileName: String)

/**
 * Turns a set of usages into a rename, and refuses the ones it cannot do safely.
 *
 * The rewrite itself is deliberately dumb — replace these character ranges with this text — because
 * all the intelligence has already happened in [JavaUsages]: the ranges are identifier tokens the
 * lexer found and the tree vouched for, so there is no formatting to preserve, no comment to avoid
 * and no string literal to accidentally edit. What this file adds is the checks: a name Java will
 * accept, and no collision with something already visible where the new name would land.
 */
object JavaRename {
    /**
     * Plans renaming [result]'s target to [newName].
     *
     * [declaringText] is the source of the file that declares the target, used for the collision
     * checks — a rename that produces two locals of the same name in one scope, or two members of
     * the same name on one type, is a compile error the user should hear about now rather than from
     * the build.
     */
    fun plan(
        result: UsageResult,
        newName: String,
        declaringPath: String?,
        declaringText: String?,
        /**
         * Whether a project-relative path already exists. Only consulted for the file a renamed
         * class would move to: without it a rename can rewrite every reference and *then* fail to
         * move the file, leaving the project in a state neither name describes. Defaults to "no"
         * so the pure planning path stays testable without a filesystem.
         */
        pathExists: (String) -> Boolean = { false },
    ): RenamePlan {
        val target = result.target
        val oldName = target.name
        val problems = mutableListOf<String>()

        if (newName.isBlank()) {
            problems += "Enter a new name"
        } else if (!JavaLexer.isValidIdentifier(newName)) {
            problems += if (newName in JavaLexer.KEYWORDS) {
                "\"$newName\" is a Java keyword"
            } else {
                "\"$newName\" is not a valid Java identifier"
            }
        } else if (newName == oldName) {
            problems += "That is already its name"
        }

        if (problems.isEmpty() && declaringText != null) {
            problems += collisions(target, newName, declaringText)
        }

        if (result.usages.isEmpty()) {
            problems += "Nothing to rename — no occurrences were found"
        }

        val fileRename = fileRenameFor(target, newName, declaringPath)
        // Checked before anything is written, because the write happens in two steps — every
        // reference first, then the file — and a failure at the second step is not recoverable by
        // stopping. Better to refuse the whole rename while it is still only a plan.
        if (fileRename != null && problems.isEmpty()) {
            val destination = fileRename.path.substringBeforeLast('/', "").let {
                if (it.isEmpty()) fileRename.newFileName else "$it/${fileRename.newFileName}"
            }
            if (pathExists(destination)) problems += "\"${fileRename.newFileName}\" already exists"
        }

        val edits = result.usages
            .groupBy { it.path }
            .mapValues { (_, list) -> list.map { it.offsetStart until it.offsetEnd }.sortedBy { it.first } }

        return RenamePlan(
            target = target,
            oldName = oldName,
            newName = newName,
            edits = edits,
            fileRename = fileRename,
            exact = result.exact,
            note = result.note,
            problems = problems,
        )
    }

    /**
     * A top-level type has to live in a file of its own name, so renaming one renames the other.
     * Only when the two currently agree: a type in a file that is already named after something else
     * is either a nested or non-public type, and moving its file would be a change nobody asked for.
     */
    private fun fileRenameFor(target: UsageTarget, newName: String, declaringPath: String?): FileRename? {
        if (target !is UsageTarget.Type || declaringPath == null) return null
        val fileName = declaringPath.substringAfterLast('/')
        if (!fileName.equals("${target.name}.java", ignoreCase = false)) return null
        return FileRename(declaringPath, "$newName.java")
    }

    /**
     * Names already taken where the new one would land.
     *
     * Deliberately narrow: only collisions the parse tree can see without types are reported, and a
     * collision that isn't found doesn't make the rename safe — it makes it unchecked. Erring
     * towards silence here is right, because a false "this will collide" would block a rename that
     * is perfectly fine, and the compiler is a much better second opinion than a guess.
     */
    private fun collisions(target: UsageTarget, newName: String, declaringText: String): List<String> {
        val parsed = JavaParse.parse(declaringText) ?: return emptyList()
        val declarations = JavaSymbols.declarations(parsed)
        return when (target) {
            is UsageTarget.Member -> {
                val clash = declarations.firstOrNull {
                    it.name == newName && it.owner == target.owner && it.kind == target.kind
                }
                if (clash != null) {
                    listOf("${target.owner} already declares ${if (target.kind == JavaDeclKind.METHOD) "a method" else "a field"} called \"$newName\"")
                } else {
                    emptyList()
                }
            }

            is UsageTarget.Type -> {
                val clash = declarations.firstOrNull {
                    it.kind == JavaDeclKind.TYPE && it.name == newName
                }
                if (clash != null) listOf("This file already declares a type called \"$newName\"") else emptyList()
            }

            is UsageTarget.Local -> {
                // Anything named [newName] whose own occurrences fall inside our scope would either
                // capture our references or be captured by them.
                val clashing = JavaLexer.occurrencesOf(declaringText, newName).any { it.first in target.scope }
                if (clashing) {
                    listOf("\"$newName\" is already used in this scope")
                } else {
                    emptyList()
                }
            }
        }
    }

    /**
     * Applies [ranges] to [text], replacing each with [newName].
     *
     * Rewritten back to front so that every range still indexes into the text it was measured
     * against — replacing left to right would shift each subsequent range by the difference in name
     * length, which is exactly the bug that turns a rename into corruption.
     */
    fun applyEdits(text: String, ranges: List<IntRange>, newName: String): String {
        if (ranges.isEmpty()) return text
        val builder = StringBuilder(text)
        for (range in ranges.sortedByDescending { it.first }) {
            if (range.first < 0 || range.last + 1 > builder.length) continue
            builder.replace(range.first, range.last + 1, newName)
        }
        return builder.toString()
    }
}
