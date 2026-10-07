package iondrive.nop.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.v2.ScrollbarAdapter
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import kotlin.math.roundToInt

/** Pixel positions shared by the diff overview and its scrollbar, including grouped rows. */
internal class DiffScrollMetrics(ranges: List<IntRange>, heights: DoubleArray) {
    val itemOffsets = DoubleArray(ranges.size + 1)
    val rowOffsets = DoubleArray((ranges.lastOrNull()?.last?.plus(1) ?: 0) + 1)
    val contentSize: Double get() = itemOffsets.last()

    init {
        require(ranges.size == heights.size)
        for ((item, range) in ranges.withIndex()) {
            val top = itemOffsets[item]
            val height = heights[item]
            val rowHeight = height / range.count()
            for (row in range) rowOffsets[row] = top + (row - range.first) * rowHeight
            itemOffsets[item + 1] = top + height
        }
        rowOffsets[rowOffsets.lastIndex] = contentSize
    }

    fun locationAt(offset: Double): RowLocation {
        if (itemOffsets.size == 1) return RowLocation(0, 0)
        val pixel = offset.coerceIn(0.0, (contentSize - 1).coerceAtLeast(0.0))
        var low = 0
        var high = itemOffsets.lastIndex
        while (low + 1 < high) {
            val mid = (low + high) / 2
            if (itemOffsets[mid] <= pixel) low = mid else high = mid
        }
        return RowLocation(low, (pixel - itemOffsets[low]).roundToInt())
    }

    fun markerBounds(row: Int, laneHeight: Float, viewportHeight: Double): MarkerBar {
        val extent = maxOf(contentSize, viewportHeight)
        if (laneHeight <= 0f || extent <= 0 || row !in 0 until rowOffsets.lastIndex) return MarkerBar(0f, 0f)
        val top = (rowOffsets[row] / extent * laneHeight).toFloat()
        val height = ((rowOffsets[row + 1] - rowOffsets[row]) / extent * laneHeight).toFloat()
            .coerceAtLeast(3f).coerceAtMost(laneHeight)
        return MarkerBar(top.coerceIn(0f, laneHeight - height), height)
    }
}

/** Lazy list items may contain many lines; their item count is not a measure of document height. */
internal class DiffScrollbarAdapter(
    private val listState: LazyListState,
    private val ranges: List<IntRange>,
    private val lineHeightPx: Float,
) : ScrollbarAdapter {
    private val measuredHeights = mutableStateMapOf<Int, Int>()
    val metrics by derivedStateOf {
        DiffScrollMetrics(ranges, DoubleArray(ranges.size) { item ->
            (measuredHeights[item] ?: (ranges[item].count() * lineHeightPx).roundToInt()).coerceAtLeast(1).toDouble()
        })
    }

    fun updateMeasurements(items: List<Pair<Int, Int>>) {
        for ((index, height) in items) {
            if (index in ranges.indices && height > 0 && measuredHeights[index] != height) measuredHeights[index] = height
        }
    }

    override val scrollOffset: Double
        get() = (metrics.itemOffsets.getOrNull(listState.firstVisibleItemIndex) ?: 0.0) +
            listState.firstVisibleItemScrollOffset

    override val contentSize: Double get() = metrics.contentSize

    override val viewportSize: Double
        get() = (listState.layoutInfo.viewportEndOffset - listState.layoutInfo.viewportStartOffset).toDouble()

    override suspend fun scrollTo(scrollOffset: Double) {
        if (ranges.isEmpty()) return
        val offset = scrollOffset.coerceIn(0.0, (contentSize - viewportSize).coerceAtLeast(0.0))
        val location = metrics.locationAt(offset)
        listState.scrollToItem(location.item, location.offsetPx)
    }
}
