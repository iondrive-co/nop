package iondrive.nop.agent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetAddress
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

    @Test
    fun `a name and an address are one host when the name resolves to it`() {
        val resolves = { name: String ->
            if (name == "files") listOf(InetAddress.getByName("192.0.2.7"), InetAddress.getByName("2001:db8::7")) else emptyList()
        }
        assertTrue(NetworkShare.sameHost("192.0.2.7", "files", resolves))
        assertTrue(NetworkShare.sameHost("files", "[2001:db8:0:0:0:0:0:7]", resolves))
        assertFalse(NetworkShare.sameHost("192.0.2.8", "files", resolves))
        assertFalse(NetworkShare.sameHost("192.0.2.7", "printer", resolves))
    }

    @Test
    fun `uses a mount that names the server by address`() {
        val files = NetworkShare.Location("files", "team", "backups")
        val resolves = { name: String -> if (name == "files") listOf(InetAddress.getByName("192.0.2.7")) else emptyList() }
        val automount = listOf("systemd-1 /mnt/team autofs rw,direct 0 0")
        val mounted = automount + "//192.0.2.7/team /mnt/team cifs rw,vers=3.1.1 0 0"
        val fstab = listOf("//192.0.2.7/team /mnt/team cifs credentials=x,x-systemd.automount 0 0")

        assertEquals(Path.of("/mnt/team"), NetworkShare.fromMounts(files, mounted, emptyList(), resolves))
        assertEquals(Path.of("/mnt/team"), NetworkShare.fromMounts(files, automount, fstab, resolves))
        assertNull(NetworkShare.fromMounts(files.copy(host = "printer"), mounted, fstab, resolves))
        // Another share is told apart before any name is looked up.
        assertNull(NetworkShare.fromMounts(files.copy(share = "media"), mounted, fstab) { error("looked up $it") })
    }

    @Test
    fun `puts a share's request for a password to the user as the share asked it`() {
        val ask = GioMount.Ask(user = "pat", domain = "HOMENET", needsUser = true, needsDomain = false)
        val nas = NetworkShare.Location("files", "team", "backups")
        assertEquals(
            NetworkShare.PasswordRequest("smb://files/team", asksUser = true, user = "pat", asksDomain = false, domain = "HOMENET", refused = true),
            NetworkShare.request(nas, GioMount.Outcome.NeedsPassword(ask, refused = true)),
        )
        // A user named in the URL is the one to offer.
        assertEquals("sam", NetworkShare.request(nas.copy(user = "sam"), GioMount.Outcome.NeedsPassword(ask, refused = false)).user)
    }

    @Test
    fun `credentials never print their password`() {
        val text = NetworkShare.Credentials("pat", "HOMENET", "hunter2", remember = true).toString()
        assertFalse("hunter2" in text, text)
    }
}
