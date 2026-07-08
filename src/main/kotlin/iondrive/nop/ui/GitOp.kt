package iondrive.nop.ui

import kotlinx.coroutines.CancellationException

/**
 * A git mutation (commit, stash, …) that failed, surfaced to the user instead of crashing the app.
 * [title] names the action that failed; [detail] is the underlying error — usually the JGit message,
 * e.g. why a file couldn't be added — so the user can see *why* it failed, not just that it did.
 */
internal data class GitOpError(val title: String, val detail: String)

/**
 * Runs a git mutation and converts any failure into a [GitOpError] the UI can display, so a failing
 * git operation (a file too large to add, a locked index, a stash that won't apply) shows an error
 * dialog rather than escaping the launched coroutine and taking down the whole window.
 *
 * Returns null on success. [CancellationException] is deliberately *not* mapped — it's rethrown so
 * structured-concurrency cancellation (e.g. switching projects mid-commit) still tears the coroutine
 * down normally instead of being reported as a failure.
 */
internal suspend fun runGitOp(title: String, block: suspend () -> Unit): GitOpError? =
    try {
        block()
        null
    } catch (c: CancellationException) {
        throw c
    } catch (t: Throwable) {
        GitOpError(title, t.userMessage())
    }

/**
 * A human-readable message for [this] and its cause chain: the distinct, non-blank messages joined
 * top-down, falling back to the exception's class name when nothing in the chain carries a message
 * (as some JGit and IO exceptions don't).
 */
internal fun Throwable.userMessage(): String {
    val messages = generateSequence(this) { it.cause }
        .mapNotNull { it.message?.trim()?.takeIf(String::isNotEmpty) }
        .distinct()
        .toList()
    return messages.joinToString("\n").ifEmpty { this::class.java.simpleName }
}
