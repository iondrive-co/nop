package iondrive.nop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.nio.file.Paths

class RailLayoutTest {
    private fun proj(s: String) = RailItem.Project(Paths.get(s))
    private fun sep(name: String, id: Long = 0) = RailItem.Separator(name, id)

    @Test
    fun `projects drops separators and preserves order`() {
        val items = listOf(proj("/p/a"), sep("Work"), proj("/p/b"), proj("/p/c"))
        assertEquals(listOf(Paths.get("/p/a"), Paths.get("/p/b"), Paths.get("/p/c")), RailLayout.projects(items))
    }

    @Test
    fun `move shifts an item forward`() {
        val items = listOf(proj("/p/a"), proj("/p/b"), proj("/p/c"))
        assertEquals(listOf(proj("/p/b"), proj("/p/c"), proj("/p/a")), RailLayout.move(items, 0, 2))
    }

    @Test
    fun `move shifts an item backward`() {
        val items = listOf(proj("/p/a"), proj("/p/b"), proj("/p/c"))
        assertEquals(listOf(proj("/p/c"), proj("/p/a"), proj("/p/b")), RailLayout.move(items, 2, 0))
    }

    @Test
    fun `move is a no-op for equal or out-of-range indices`() {
        val items = listOf(proj("/p/a"), proj("/p/b"))
        assertEquals(items, RailLayout.move(items, 1, 1))
        assertEquals(items, RailLayout.move(items, 0, 5))
        assertEquals(items, RailLayout.move(items, -1, 0))
    }

    @Test
    fun `encode and decode round-trip a project`() {
        val item = proj("/p/a")
        assertEquals(item, RailLayout.decode(RailLayout.encode(item), separatorId = 0))
    }

    @Test
    fun `encode and decode round-trip a separator by name`() {
        val decoded = RailLayout.decode(RailLayout.encode(sep("Work projects")), separatorId = 7)
        assertEquals(sep("Work projects"), decoded)
        assertEquals(7L, (decoded as RailItem.Separator).id)
    }

    @Test
    fun `separator equality ignores id`() {
        assertEquals(sep("Work", 1), sep("Work", 99))
    }

    @Test
    fun `encode flattens newlines in a separator name`() {
        val encoded = RailLayout.encode(sep("line1\nline2"))
        assertEquals("sep:line1 line2", encoded)
    }

    @Test
    fun `decode rejects an unknown prefix`() {
        assertNull(RailLayout.decode("garbage", separatorId = 0))
    }
}
