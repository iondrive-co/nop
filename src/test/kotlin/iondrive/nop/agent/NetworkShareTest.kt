package iondrive.nop.agent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** Naming a backup folder on an SMB share, and finding the mount it is written through. */
class NetworkShareTest {

    private val anhur = NetworkShare.Location("anhur", "data", "backups/nop")

    @Test
    fun `reads smb URLs and UNC paths, and leaves plain paths alone`() {
        assertEquals(anhur, NetworkShare.parse("smb://anhur/data/backups/nop/"))
        assertEquals(anhur, NetworkShare.parse("""\\anhur\data\backups\nop"""))
        assertEquals(NetworkShare.Location("nas", "My Files", "", "bob"), NetworkShare.parse("smb://bob@nas:445/My%20Files"))
        assertNull(NetworkShare.parse("smb://anhur"))
        assertNull(NetworkShare.parse("/mnt/anhur/backups"))
        assertEquals(Path.of("/mnt/x"), NetworkShare.resolve(" /mnt/x "))
    }

    @Test
    fun `uses a cifs mount of the share, matching the host by its first label`() {
        val mounts = listOf(
            "//anhur.local/data /mnt/anhur\\040nas cifs rw,vers=3.1.1 0 0",
            "/dev/sda1 / ext4 rw 0 0",
        )
        assertEquals(Path.of("/mnt/anhur nas"), NetworkShare.fromMounts(anhur, mounts, emptyList()))
        assertNull(NetworkShare.fromMounts(anhur.copy(share = "media"), mounts, emptyList()))
    }

    @Test
    fun `uses an fstab entry only when systemd automounts it`() {
        val fstab = listOf("# comment", "//anhur.local/data /mnt/anhur cifs credentials=x,x-systemd.automount 0 0")
        val automount = listOf("systemd-1 /mnt/anhur autofs rw,direct 0 0")
        assertEquals(Path.of("/mnt/anhur"), NetworkShare.fromMounts(anhur, automount, fstab))
        // Not mounted: /mnt/anhur is an empty folder on the local disk.
        assertNull(NetworkShare.fromMounts(anhur, emptyList(), fstab))
    }

    @Test
    fun `finds the GVFS mount of the share`(@TempDir gvfs: Path) {
        Files.createDirectories(gvfs.resolve("smb-share:server=other,share=data"))
        Files.createDirectories(gvfs.resolve("smb-share:server=anhur.local,share=DATA,user=thoth"))
        assertEquals(gvfs.resolve("smb-share:server=anhur.local,share=DATA,user=thoth"), NetworkShare.fromGvfs(anhur, gvfs))
        assertNull(NetworkShare.fromGvfs(anhur.copy(user = "someone"), gvfs))
        assertNull(NetworkShare.fromGvfs(anhur.copy(share = "media"), gvfs))
    }

    @Test
    fun `tells addresses apart rather than by their first number`() {
        assertTrue(NetworkShare.sameHost("ANHUR", "anhur.local"))
        assertFalse(NetworkShare.sameHost("192.168.1.98", "192.168.1.99"))
        assertTrue(NetworkShare.sameHost("192.168.1.98", "192.168.1.98"))
    }
}
