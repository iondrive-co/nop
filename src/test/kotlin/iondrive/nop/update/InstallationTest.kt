package iondrive.nop.update

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class InstallationTest {

    @TempDir
    lateinit var tmp: Path

    private val v = Version(1, 1, 1)

    @Test
    fun `a launcher outside any checkout is a release install`() {
        val launcher = tmp.resolve("opt/nop/bin/nop").also { it.parent.createDirectories() }
        assertEquals(Installation.Release(launcher, v), Installation.of(launcher, v))
    }

    @Test
    fun `a launcher built inside a checkout is not updated from releases`() {
        tmp.resolve("nop/.git").createDirectories().resolve("HEAD").writeText("ref: refs/heads/main\n")
        val launcher = tmp.resolve("nop/build/compose/binaries/main/app/nop/bin/nop")
        val result = assertIs<Installation.NotUpdatable>(Installation.of(launcher, v))
        assertTrue("rebuild" in result.reason, result.reason)
    }

    @Test
    fun `an empty git directory is not a checkout`() {
        tmp.resolve("shared/.git").createDirectories()
        assertNull(Installation.checkoutAbove(tmp.resolve("shared/opt/nop/bin/nop"))?.takeIf { it.startsWith(tmp) })
    }

    @Test
    fun `a linked worktree counts as a checkout`() {
        tmp.resolve("wt").createDirectories()
        tmp.resolve("wt/.git").writeText("gitdir: /elsewhere/.git/worktrees/wt\n")
        assertEquals(tmp.resolve("wt"), Installation.checkoutAbove(tmp.resolve("wt/build/app/nop/bin/nop")))
    }

    @Test
    fun `no launcher or no version means no updates`() {
        assertIs<Installation.NotUpdatable>(Installation.of(null, v))
        assertIs<Installation.NotUpdatable>(Installation.of(tmp.resolve("bin/nop"), null))
    }

    @Test
    fun `versions parse with or without the tag's v and order numerically`() {
        assertEquals(Version(1, 10, 0), Version.parse("v1.10.0"))
        assertEquals(Version(1, 2, 3), Version.parse(" 1.2.3\n"))
        assertNull(Version.parse("1.2"))
        assertNull(Version.parse("1.2.3-rc1"))
        assertTrue(Version.parse("1.10.0")!! > Version.parse("1.9.9")!!)
    }

    @Test
    fun `the build stamps its own version`() {
        assertNotNull(BuildInfo.version)
    }
}
