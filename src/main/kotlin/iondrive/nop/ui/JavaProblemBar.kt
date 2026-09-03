package iondrive.nop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import iondrive.nop.lang.JavaProblem
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text

/**
 * One line under the tab strip naming the first syntax error in a Java file, and how many follow.
 *
 * A squiggle alone is only half the feature: it says *where* without saying *what*, and it says
 * nothing at all about an error below the fold. The marker lane on the scrollbar answers the second
 * half; this answers the first. Clicking the bar scrolls to the error it names, so the shortest path
 * from "something is wrong" to the character that is wrong is one click.
 *
 * Drawn only when there is something to say, so a file that parses cleanly — which is most files,
 * most of the time — gets no chrome at all.
 */
@Composable
fun JavaProblemBar(
    problems: List<JavaProblem>,
    onGoTo: (offset: Int) -> Unit,
    lineOf: (offset: Int) -> Int,
    modifier: Modifier = Modifier,
) {
    val first = problems.firstOrNull() ?: return
    val heading = if (JewelTheme.isDark) Color(0xFFE06C69) else Color(0xFFB3261E)
    val muted = if (JewelTheme.isDark) Color(0xFF8B8F99) else Color(0xFF7A7E87)
    val background = if (JewelTheme.isDark) Color(0x1FE06C69) else Color(0x14B3261E)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(background)
            .clickable { onGoTo(first.start) }
            .padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Line ${lineOf(first.start)}", color = muted)
        Text(
            // javac's messages arrive with the line already prefixed in some locales and wrapped
            // onto several lines in others; the bar is one line, so flatten it.
            first.message.replace('\n', ' ').trim(),
            color = heading,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (problems.size > 1) {
            Text("+${problems.size - 1} more", color = muted)
        }
    }
}

/**
 * 1-based line number of [offset] in [text]. Counting is fine here — the bar asks for one offset per
 * recomposition, not one per problem, and the alternative (a cached line table per buffer) is a
 * whole extra thing to keep in step with the text for no measurable gain.
 */
internal fun lineNumberAt(text: String, offset: Int): Int {
    if (offset <= 0) return 1
    var line = 1
    val end = offset.coerceAtMost(text.length)
    for (i in 0 until end) if (text[i] == '\n') line++
    return line
}
