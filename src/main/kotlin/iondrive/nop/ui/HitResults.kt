package iondrive.nop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import iondrive.nop.index.SearchHit
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text

/** The muted one-liner a results panel shows in place of rows: "Searching…", "No matches". */
@Composable
internal fun StatusText(text: String) {
    val muted = if (JewelTheme.isDark) Color(0xFF6F737A) else Color(0xFF7A7E87)
    Text(text, color = muted)
}

/** Row chrome around a hit's text: the row padding plus the column's list scrollbar. */
private val HIT_ROW_CHROME = 28.dp

/**
 * A grid of hit rows, grouped by [PathGrouping] into the same side-by-side columns the commit panel
 * uses — source directories first, then tests, config and docs.
 *
 * Shared by "Find in files" and "Usages": both answer "here is a list of places in the project", and
 * a usage that looked different from a search hit would be a distinction without a difference. The
 * caller supplies [footer] for whatever it needs to say underneath — a truncation notice, a warning
 * about how exact the list is.
 */
@Composable
internal fun HitResultsGrid(
    results: List<SearchHit>,
    onPick: (path: String, line: Int) -> Unit,
    footer: String? = null,
) {
    val groups = remember(results) { PathGrouping.group(results) { it.path } }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    // Widest row text across the columns, so the grid can size them to fit the matched code. Both
    // row lines are monospaced, so the longest string by character count is also the widest — one
    // measure per line style per column instead of one per hit.
    val naturalTextPx = remember(groups) {
        var widest = 0
        for (group in groups) {
            val label = group.paths.maxByOrNull { it.length }?.let { "${group.labelFor(it)}:0000" }
            val code = group.items.maxByOrNull { it.lineText.length }?.lineText
            if (label != null) widest = maxOf(widest, measurer.measure(label, LOCATION_STYLE).size.width)
            if (code != null) widest = maxOf(widest, measurer.measure(code, CODE_STYLE).size.width)
        }
        widest
    }
    Column(modifier = Modifier.fillMaxSize()) {
        GroupColumnGrid(
            columns = groups.map { group ->
                GroupColumn(key = group.title, header = group.header) {
                    HitRows(group = group, onPick = onPick)
                }
            },
            naturalColumnWidth = with(density) { naturalTextPx.toDp() } + HIT_ROW_CHROME,
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
        if (footer != null) {
            val muted = if (JewelTheme.isDark) Color(0xFF8B8F99) else Color(0xFF7A7E87)
            Text(footer, color = muted, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/** The hit rows filling one column of the grid, under the heading [GroupColumnGrid] draws. */
@Composable
private fun HitRows(group: PathGroup<SearchHit>, onPick: (path: String, line: Int) -> Unit) {
    val listState = rememberLazyListState()
    ScrollableColumn(listState = listState, modifier = Modifier.fillMaxSize()) {
        items(group.items) { hit ->
            HitRow(
                hit = hit,
                label = group.labelFor(hit.path),
                onClick = { onPick(hit.path, hit.line) },
            )
        }
    }
}

private val LOCATION_STYLE = TextStyle(fontFamily = NopFonts.Mono, fontSize = 11.sp)
private val CODE_STYLE = TextStyle(fontFamily = NopFonts.Mono, fontSize = 13.sp)

@Composable
private fun HitRow(hit: SearchHit, label: String, onClick: () -> Unit) {
    val muted = if (JewelTheme.isDark) Color(0xFF8B8F99) else Color(0xFF7A7E87)
    val highlight = if (JewelTheme.isDark) Color(0x66629755) else Color(0x66629755)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text("$label:${hit.line}", color = muted, style = LOCATION_STYLE)
        Text(
            annotateLine(hit.lineText, hit.matchStart, hit.matchEnd, highlight),
            style = CODE_STYLE,
            softWrap = false,
        )
    }
}

private fun annotateLine(
    text: String,
    matchStart: Int,
    matchEnd: Int,
    highlight: Color,
): AnnotatedString {
    val start = matchStart.coerceIn(0, text.length)
    val end = matchEnd.coerceIn(start, text.length)
    if (end == start) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text.substring(0, start))
        withStyle(SpanStyle(background = highlight)) {
            append(text.substring(start, end))
        }
        append(text.substring(end))
    }
}
