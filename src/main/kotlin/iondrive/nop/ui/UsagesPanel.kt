package iondrive.nop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import iondrive.nop.index.SearchHit
import iondrive.nop.lang.UsageResult
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text

/**
 * What the Usages tab is showing right now.
 *
 * A plain snapshot rather than a live search: usages are found once, on request, and then stand
 * still. That is the opposite of the Search tab, which re-runs on every keystroke — and it is the
 * right difference, because a usage list is something the user works *through*, clicking each row in
 * turn, not something they refine.
 */
data class UsagesView(
    /** What was searched for, e.g. "method greet in com.example.Greeter". */
    val title: String? = null,
    val searching: Boolean = false,
    val result: UsageResult? = null,
    /** Shown instead of results when there was nothing to search for. */
    val message: String? = null,
)

/**
 * Bottom-tab "Usages" panel: where a name is used across the project, and how much that list can be
 * trusted.
 *
 * The trust line is drawn on screen, not buried: an approximate result says so above the rows, in
 * the words [UsageResult.note] supplies. A list that may contain strangers is still worth having —
 * it is a far better starting point than a text search — but only if the user knows that is what
 * they are looking at.
 */
@Composable
fun UsagesPanel(view: UsagesView, onPick: (path: String, line: Int) -> Unit) {
    val muted = if (JewelTheme.isDark) Color(0xFF8B8F99) else Color(0xFF7A7E87)
    val warning = if (JewelTheme.isDark) Color(0xFFD9A441) else Color(0xFF8A6D1A)
    Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        val result = view.result
        when {
            view.message != null -> StatusText(view.message)
            view.searching -> {
                if (view.title != null) Text("Usages of ${view.title}", fontWeight = FontWeight.Bold)
                StatusText("Searching…")
            }
            result == null -> StatusText(
                "Put the caret on a name in a Java file and press Alt+F7 to find its usages",
            )
            else -> {
                Text(
                    "${result.usages.size} ${if (result.usages.size == 1) "usage" else "usages"} of " +
                        result.target.description,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!result.exact && result.note != null) {
                    Text(result.note, color = warning, modifier = Modifier.padding(top = 4.dp))
                }
                Box(Modifier.padding(top = 6.dp).fillMaxSize()) {
                    if (result.usages.isEmpty()) {
                        StatusText("No usages found")
                    } else {
                        val hits = remember(result) {
                            result.usages.map {
                                SearchHit(
                                    path = it.path,
                                    line = it.line,
                                    lineText = it.lineText,
                                    matchStart = it.columnStart,
                                    matchEnd = it.columnEnd,
                                )
                            }
                        }
                        HitResultsGrid(
                            results = hits,
                            onPick = onPick,
                            footer = when {
                                result.truncated -> "(showing the first ${result.usages.size} — narrow the search)"
                                result.exact -> "Every occurrence, proved from the parse tree."
                                else -> null
                            },
                        )
                    }
                }
            }
        }
        if (view.result == null && view.message == null && !view.searching) {
            Text(
                "Shift+F6 renames what's under the caret, everywhere it is used.",
                color = muted,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
