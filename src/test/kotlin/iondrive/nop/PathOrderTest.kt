package iondrive.nop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PathOrderTest {

    private fun sorted(vararg paths: String) = paths.toList().sortedWith(PathOrder)

    @Test
    fun `files in a folder stay together, folders in lexical order`() {
        assertEquals(
            listOf(
                "docs/api/endpoints.md",
                "docs/api/overview.md",
                "docs/ui/buttons.md",
                "guides/start.md",
            ),
            sorted(
                "docs/ui/buttons.md",
                "guides/start.md",
                "docs/api/overview.md",
                "docs/api/endpoints.md",
            ),
        )
    }

    @Test
    fun `a folder's own files come before its subfolders'`() {
        assertEquals(
            listOf("docs/index.md", "docs/api/endpoints.md"),
            sorted("docs/api/endpoints.md", "docs/index.md"),
        )
    }

    @Test
    fun `root files sort ahead of everything in a folder`() {
        assertEquals(
            listOf("README.md", "SUMMARY.md", "docs/index.md"),
            sorted("docs/index.md", "SUMMARY.md", "README.md"),
        )
    }

    @Test
    fun `case is ignored, so a capitalised folder isn't exiled to the front`() {
        assertEquals(
            listOf("src/Admin/page.kt", "src/advertising/page.kt", "src/Billing/page.kt"),
            sorted("src/Billing/page.kt", "src/advertising/page.kt", "src/Admin/page.kt"),
        )
    }

    @Test
    fun `paths differing only in case still have an order`() {
        // A tie would let a sort swap them between runs — the exact reshuffling this order exists
        // to stop — so the case-insensitive comparison falls through to a case-sensitive one.
        assertTrue(PathOrder.compare("docs/readme.md", "docs/README.md") != 0)
        assertEquals(0, PathOrder.compare("docs/readme.md", "docs/readme.md"))
    }
}
