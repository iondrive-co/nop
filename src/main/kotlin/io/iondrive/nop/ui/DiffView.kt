package io.iondrive.nop.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.iondrive.nop.diff.DiffComputer
import io.iondrive.nop.diff.DiffResult
import io.iondrive.nop.diff.DiffRow
import io.iondrive.nop.diff.InlineSpan
import io.iondrive.nop.diff.RowKind
import io.iondrive.nop.git.ChangeKind
import io.iondrive.nop.git.GitRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.jewel.ui.component.Text

// Background tints — muted dark-theme palette
private val INSERT_BG = Color(0x33629755) // green
private val DELETE_BG = Color(0x33B35E5E) // red
private val CHANGE_BG = Color(0x33547B9D) // blue
private val EMPTY_BG = Color(0x14FFFFFF)  // subtle gray (filler for missing side)
private val INLINE_WORD_BG = Color(0x66629755)
private val INLINE_WORD_BG_OLD = Color(0x66B35E5E)
private val GUTTER_FG = Color(0xFF808080)

// Saturated marker colours for the scrollbar lane — must read at a glance on the dark panel.
private val INSERT_MARK = Color(0xFF7DBE6E)
private val DELETE_MARK = Color(0xFFD96B6B)
private val CHANGE_MARK = Color(0xFF6FA8DC)

@Composable
fun DiffView(repo: GitRepo, tab: Tab.Diff) {
    var loading by remember(tab.id) { mutableStateOf(true) }
    var error by remember(tab.id) { mutableStateOf<String?>(null) }
    var result by remember(tab.id) { mutableStateOf<DiffResult?>(null) }

    LaunchedEffect(tab.id) {
        try {
            val (old, new) = withContext(Dispatchers.IO) {
                val o = when (tab.change.kind) {
                    ChangeKind.UNTRACKED, ChangeKind.ADDED -> ""
                    else -> repo.readHeadContent(tab.change.path) ?: ""
                }
                val n = when (tab.change.kind) {
                    ChangeKind.REMOVED, ChangeKind.MISSING -> ""
                    else -> repo.readWorkingTreeContent(tab.change.path) ?: ""
                }
                o to n
            }
            result = withContext(Dispatchers.Default) { DiffComputer.compute(old, new) }
            loading = false
        } catch (t: Throwable) {
            error = t.message ?: t::class.simpleName
            loading = false
        }
    }

    when {
        loading -> Box(Modifier.fillMaxSize().padding(16.dp), Alignment.Center) {
            Text("Loading diff…")
        }
        error != null -> Box(Modifier.fillMaxSize().padding(16.dp), Alignment.Center) {
            Text("Could not load diff: $error")
        }
        result != null -> DiffRowsList(result!!)
    }
}

@Composable
private fun DiffRowsList(result: DiffResult) {
    val listState = rememberLazyListState()
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(end = MARKER_LANE_W + SCROLLBAR_W),
        ) {
            items(result.rows) { row -> DiffRowView(row) }
        }
        // Marker lane sits just to the left of the scrollbar, so the markers stay readable
        // even while the user is dragging the (translucent) scrollbar thumb across them.
        Row(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Canvas(Modifier.width(MARKER_LANE_W).fillMaxHeight()) {
                drawChangeMarkers(result.rows)
            }
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(listState),
                style = NopScrollbarStyle,
                modifier = Modifier.width(SCROLLBAR_W).fillMaxHeight(),
            )
        }
    }
}

private val MARKER_LANE_W = 4.dp
private val SCROLLBAR_W = 10.dp

private fun DrawScope.drawChangeMarkers(rows: List<DiffRow>) {
    val n = rows.size
    if (n == 0) return
    val markerH = (size.height / n).coerceAtLeast(3f)
    val w = size.width
    rows.forEachIndexed { idx, row ->
        val color = when (row.kind) {
            RowKind.EQUAL -> return@forEachIndexed
            RowKind.INSERT -> INSERT_MARK
            RowKind.DELETE -> DELETE_MARK
            RowKind.CHANGE -> CHANGE_MARK
        }
        val y = (idx.toFloat() / n) * size.height
        drawRect(color = color, topLeft = Offset(0f, y), size = Size(w, markerH))
    }
}

@Composable
private fun DiffRowView(row: DiffRow) {
    val (oldBg, newBg) = backgroundsFor(row)

    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicMinHeightLine),
        horizontalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        DiffHalf(
            text = row.oldLine,
            spans = row.oldSpans,
            lineNumber = row.oldLineNumber,
            background = oldBg,
            inlineHighlight = INLINE_WORD_BG_OLD,
            modifier = Modifier.weight(1f),
        )
        Box(Modifier.width(1.dp).fillMaxSize().background(Color(0x33FFFFFF)))
        DiffHalf(
            text = row.newLine,
            spans = row.newSpans,
            lineNumber = row.newLineNumber,
            background = newBg,
            inlineHighlight = INLINE_WORD_BG,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun DiffHalf(
    text: String?,
    spans: List<InlineSpan>,
    lineNumber: Int?,
    background: Color,
    inlineHighlight: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxSize().background(background),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = lineNumber?.toString()?.padStart(5) ?: "     ",
            color = GUTTER_FG,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            softWrap = false,
            modifier = Modifier.padding(horizontal = 6.dp),
        )
        Text(
            text = annotateLine(text ?: "", spans, inlineHighlight),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            softWrap = false,
            modifier = Modifier.padding(end = 4.dp),
        )
    }
}

private fun annotateLine(text: String, spans: List<InlineSpan>, highlightColor: Color): AnnotatedString {
    if (spans.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        for (s in spans) {
            // Defensive: clamp into the line. A malformed span (e.g. start > end after clamping,
            // or a negative start from a stray close sentinel) used to throw StringIndexOOB and
            // tear down the whole row during scroll.
            val start = s.startChar.coerceIn(0, text.length)
            val end = s.endCharExclusive.coerceIn(start, text.length)
            if (end == start) continue
            val piece = text.substring(start, end)
            if (s.changed) {
                withStyle(SpanStyle(background = highlightColor)) { append(piece) }
            } else {
                append(piece)
            }
        }
    }
}

private fun backgroundsFor(row: DiffRow): Pair<Color, Color> = when (row.kind) {
    RowKind.EQUAL -> Color.Transparent to Color.Transparent
    RowKind.CHANGE -> CHANGE_BG to CHANGE_BG
    RowKind.INSERT -> EMPTY_BG to INSERT_BG
    RowKind.DELETE -> DELETE_BG to EMPTY_BG
}

private val IntrinsicMinHeightLine = 18.dp
