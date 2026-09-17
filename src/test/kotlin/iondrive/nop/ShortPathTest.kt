package iondrive.nop

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShortPathTest {

    private val home = "/home/dev"

    private fun short(path: String, max: Int = ShortPath.DEFAULT_MAX) =
        ShortPath.of(Path.of(path), max = max, home = home)

    @Test
    fun `a path inside the home directory reads from a tilde`() {
        assertEquals("~/nop", short("/home/dev/nop"))
    }

    @Test
    fun `the home directory itself is just the tilde`() {
        assertEquals("~", short("/home/dev"))
    }

    @Test
    fun `a path outside the home directory is left alone`() {
        assertEquals("/srv/api", short("/srv/api"))
    }

    /** A sibling of the home directory only shares a prefix — it is not inside it. */
    @Test
    fun `a directory whose name starts with the home path is not abbreviated`() {
        assertEquals("/home/developer", short("/home/developer"))
    }

    @Test
    fun `a short path is never truncated`() {
        assertEquals("~/hermes", short("/home/dev/hermes"))
    }

    /**
     * Leading directories go, never the last one: what this label answers is "which checkout", and
     * that is the segment at the end.
     */
    @Test
    fun `a long path keeps its tail and drops its head`() {
        val result = short("/home/dev/work/clients/acme/backend/api", max = 20)
        assertTrue(result.startsWith("…/"), result)
        assertTrue(result.endsWith("/api"), result)
        assertTrue(result.length <= 20, result)
    }

    /** As little is dropped as the width takes, rather than cutting to a fixed depth. */
    @Test
    fun `only as many directories are dropped as have to be`() {
        assertEquals("…/acme/backend/api", short("/home/dev/work/clients/acme/backend/api", max = 18))
        assertEquals("…/backend/api", short("/home/dev/work/clients/acme/backend/api", max = 17))
    }

    /**
     * Overflowing beats naming nothing: a row that has to ellipsize one long directory still says
     * more than a row showing only the ellipsis that replaced it.
     */
    @Test
    fun `a last segment longer than the budget is kept whole`() {
        assertEquals("…/a-very-long-directory-name", short("/home/dev/w/a-very-long-directory-name", max = 10))
    }

    @Test
    fun `the filesystem root survives`() {
        assertEquals("/", short("/", max = 0))
    }

    @Test
    fun `no home to compare against leaves the path absolute`() {
        assertEquals("/home/dev/nop", ShortPath.of(Path.of("/home/dev/nop"), home = null))
        assertEquals("/home/dev/nop", ShortPath.of(Path.of("/home/dev/nop"), home = "  "))
    }

    @Test
    fun `a path is normalised before it is shortened`() {
        assertEquals("~/nop", short("/home/dev/hermes/../nop"))
    }
}
