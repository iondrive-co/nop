package iondrive.nop.spell

/**
 * What to offer instead of a misspelled word.
 *
 * Two passes, cheapest first. Nearly every real typo is one edit away from the word that was meant —
 * a dropped letter, a doubled one, two swapped, one hit instead of its neighbour — so the first pass
 * generates every one-edit variant of the word and asks the dictionary which of them exist. That is
 * a few hundred hash lookups and no scan at all. Only when it comes up short does the second pass
 * walk the word list looking for anything within two edits, which is the case worth paying for: a
 * word mangled twice has no cheap neighbourhood to search.
 */

/** How many corrections to offer. Long menus are read as "the editor has no idea" — and past the
 *  first few, the suggestions stop being plausible anyway. */
const val MAX_SUGGESTIONS = 8

/** Letters an edit may insert or substitute. The apostrophe earns its place: `doesnt` and `isnt`
 *  are among the commonest misspellings in comments, and neither is reachable without it. */
private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz'"

/**
 * Corrections for [word], best first, at most [limit] of them. Empty when nothing plausible is
 * within two edits — an editor that offers nonsense is worse than one that admits it has nothing.
 *
 * [words] is the vocabulary to search, defaulting to the loaded dictionary; tests pass their own.
 * The result follows [word]'s capitalisation, so a sentence that starts `Recieve` is offered
 * `Receive` rather than a lowercase word the user would have to fix again.
 */
fun suggestionsFor(
    word: String,
    words: Set<String> = Dictionary.words(),
    limit: Int = MAX_SUGGESTIONS,
): List<String> {
    val lower = word.lowercase()
    if (lower.isEmpty() || limit <= 0) return emptyList()

    // candidate -> how it was reached, as (edit distance, kind of edit). The kind matters as much
    // as the count: two letters typed the wrong way round is the single commonest slip there is,
    // so `teh` must offer `the` ahead of the dozen real words one substitution away from it.
    val found = LinkedHashMap<String, Pair<Int, Int>>()
    fun offer(candidate: String, distance: Int, kind: Int) {
        if (candidate == lower) return
        val existing = found[candidate]
        if (existing == null || distance < existing.first ||
            (distance == existing.first && kind < existing.second)
        ) {
            found[candidate] = distance to kind
        }
    }

    for ((candidate, kind) in oneEditVariants(lower)) {
        if (candidate in words) offer(candidate, 1, kind)
    }
    // A missing space is an edit the letter-level variants above can't express: `thequick` is one
    // insertion from nothing in the dictionary, but two known words back to back.
    for (i in 2..lower.length - 2) {
        val head = lower.substring(0, i)
        val tail = lower.substring(i)
        if (head in words && tail in words) offer("$head $tail", 1, EDIT_INSERT)
    }
    if (found.size < limit) {
        for (candidate in withinTwoEdits(lower, words, limit * 4)) offer(candidate, 2, EDIT_SUBSTITUTE)
    }

    return found.entries
        .sortedWith(
            compareBy(
                { it.value.first },
                { it.value.second },
                // A typo rarely gets its first letter wrong, so corrections that keep it come first.
                { if (it.key.firstOrNull() == lower.firstOrNull()) 0 else 1 },
                { -commonPrefix(it.key, lower) },
                { it.key },
            ),
        )
        .take(limit)
        .map { matchCase(word, it.key) }
}

// How likely each kind of slip is, lowest first — the order corrections are offered in when they
// are the same number of edits away.
private const val EDIT_TRANSPOSE = 0
private const val EDIT_INSERT = 1    // the typist dropped a letter; putting it back is the fix
private const val EDIT_DELETE = 1    // the typist doubled or added one
private const val EDIT_SUBSTITUTE = 2

/** Every string one insertion, deletion, substitution or transposition away from [word], each
 *  paired with the kind of edit that produced it. */
private fun oneEditVariants(word: String): Sequence<Pair<String, Int>> = sequence {
    val n = word.length
    for (i in 0 until n - 1) {
        yield(word.substring(0, i) + word[i + 1] + word[i] + word.substring(i + 2) to EDIT_TRANSPOSE)
    }
    for (i in 0 until n) yield(word.substring(0, i) + word.substring(i + 1) to EDIT_DELETE)
    for (i in 0..n) {
        for (c in ALPHABET) yield(word.substring(0, i) + c + word.substring(i) to EDIT_INSERT)
    }
    for (i in 0 until n) {
        for (c in ALPHABET) {
            if (c != word[i]) yield(word.substring(0, i) + c + word.substring(i + 1) to EDIT_SUBSTITUTE)
        }
    }
}

/**
 * Dictionary words within two edits of [word], stopping once [cap] have been collected.
 *
 * This is the pass that scans, so it is written to reject fast: a word whose length differs by more
 * than two can't be within two edits, which throws out the great majority before any distance is
 * computed, and the distance itself abandons a row as soon as every cell in it exceeds two.
 */
private fun withinTwoEdits(word: String, words: Set<String>, cap: Int): List<String> {
    val out = mutableListOf<String>()
    val lengths = (word.length - 2)..(word.length + 2)
    for (candidate in words) {
        if (candidate.length !in lengths || candidate == word) continue
        if (distanceAtMost(word, candidate, 2)) {
            out += candidate
            if (out.size >= cap) break
        }
    }
    return out
}

/**
 * Whether [a] and [b] are within [max] single-character edits (Levenshtein, with transposition
 * counted as the two edits it is). Two rolling rows rather than a full matrix, and the whole thing
 * gives up as soon as a row's best possible score passes [max] — the point of the function is to
 * answer "close enough?" cheaply, not to report how far apart two unrelated words are.
 */
internal fun distanceAtMost(a: String, b: String, max: Int): Boolean {
    if (kotlin.math.abs(a.length - b.length) > max) return false
    var previous = IntArray(b.length + 1) { it }
    var current = IntArray(b.length + 1)
    for (i in 1..a.length) {
        current[0] = i
        var best = current[0]
        for (j in 1..b.length) {
            val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
            current[j] = minOf(current[j - 1] + 1, previous[j] + 1, substitution)
            if (current[j] < best) best = current[j]
        }
        if (best > max) return false
        val swap = previous
        previous = current
        current = swap
    }
    return previous[b.length] <= max
}

private fun commonPrefix(a: String, b: String): Int {
    var i = 0
    while (i < a.length && i < b.length && a[i] == b[i]) i++
    return i
}

/** Gives [suggestion] the shape of [original]: `Recieve` → `Receive`, `RECIEVE` → `RECEIVE`. */
private fun matchCase(original: String, suggestion: String): String = when {
    original.all { it.isUpperCase() || !it.isLetter() } && original.any { it.isLetter() } ->
        suggestion.uppercase()
    original.firstOrNull()?.isUpperCase() == true ->
        suggestion.replaceFirstChar { it.uppercaseChar() }
    else -> suggestion
}
