package iondrive.nop.ui

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals

class DiffScrollMetricsTest {
    @Test fun `markers in a diff that fits stay beside the changed lines`() {
        val metrics = DiffScrollMetrics(listOf(0..19), doubleArrayOf(400.0))
        assertEquals(MarkerBar(200f, 20f), metrics.markerBounds(10, laneHeight = 800f, viewportHeight = 800.0))
        assertEquals(MarkerBar(380f, 20f), metrics.markerBounds(19, laneHeight = 800f, viewportHeight = 800.0))
    }

    @Test fun `grouped and individual rows give the same overview positions`() {
        val grouped = DiffScrollMetrics(listOf(0..199, 200..204, 205..299), doubleArrayOf(4000.0, 100.0, 1900.0))
        val individual = DiffScrollMetrics((0..299).map { it..it }, DoubleArray(300) { 20.0 })
        assertContentEquals(individual.rowOffsets, grouped.rowOffsets)
        assertEquals(MarkerBar(400f, 3f), grouped.markerBounds(200, laneHeight = 600f, viewportHeight = 600.0))
        assertEquals(RowLocation(0, 3000), grouped.locationAt(3000.0))
        assertEquals(RowLocation(1, 50), grouped.locationAt(4050.0))
        assertEquals(RowLocation(2, 0), grouped.locationAt(4100.0))
    }

    @Test fun `wrapped rows and conflict strips shift the overview and drag targets together`() {
        val metrics = DiffScrollMetrics(listOf(0..0, 1..2, 3..3), doubleArrayOf(28.0, 100.0, 20.0))
        assertContentEquals(doubleArrayOf(0.0, 28.0, 78.0, 128.0, 148.0), metrics.rowOffsets)
        assertEquals(MarkerBar(128f, 20f), metrics.markerBounds(3, laneHeight = 300f, viewportHeight = 300.0))
        assertEquals(RowLocation(1, 70), metrics.locationAt(98.0))
        assertEquals(RowLocation(2, 0), metrics.locationAt(128.0))
    }

    @Test fun `long document markers scale by content height and stay inside the track`() {
        val metrics = DiffScrollMetrics(listOf(0..999), doubleArrayOf(20000.0))
        assertEquals(MarkerBar(150f, 3f), metrics.markerBounds(500, laneHeight = 300f, viewportHeight = 300.0))
        assertEquals(MarkerBar(297f, 3f), metrics.markerBounds(999, laneHeight = 300f, viewportHeight = 300.0))
    }

    @Test fun `empty diff has no scroll extent or markers`() {
        val metrics = DiffScrollMetrics(emptyList(), doubleArrayOf())
        assertEquals(0.0, metrics.contentSize)
        assertEquals(RowLocation(0, 0), metrics.locationAt(100.0))
        assertEquals(MarkerBar(0f, 0f), metrics.markerBounds(0, laneHeight = 300f, viewportHeight = 300.0))
    }
}
