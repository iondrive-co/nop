package iondrive.nop.agent

import iondrive.nop.Log
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * A backup folder on an SMB share, written `smb://host/share/folder` (or `\\host\share\folder`),
 * turned into a local path that [Backup] writes to like any other folder.
 *
 * nop speaks no SMB itself; it goes through a mount the system already knows how to make. A kernel
 * (cifs) mount of the share comes first, since it behaves like a disk: one in `/proc/mounts`, or an
 * fstab entry that systemd automounts on first access. Failing that, the desktop's GVFS mount — the
 * one a file manager makes, with its password from the keyring — is used, and made with `gio mount`
 * when it is not there yet. `gio` is never given a password: a share that needs one it does not
 * already have fails with a message saying how to mount it once by hand.
 *
 * An fstab entry that is neither mounted nor automounted is never used. Its mount point is an empty
 * folder on the local disk, and a backup written there would look like it worked.
 */
internal object NetworkShare {

    data class Location(val host: String, val share: String, val folder: String, val user: String? = null) {
        /** The share itself, in the form `gio mount` takes. */
        val shareUrl: String get() = "smb://${user?.let { "$it@" } ?: ""}$host/$share"
    }

    private const val MOUNT_TIMEOUT_SECONDS = 60L
    private val SMB_TYPES = setOf("cifs", "smb3")

    /** The share [text] names, or null when it is an ordinary path. */
    fun parse(text: String): Location? {
        val trimmed = text.trim()
        val rest = when {
            trimmed.startsWith("smb://", ignoreCase = true) -> trimmed.substring(6)
            trimmed.startsWith("\\\\") -> trimmed.substring(2).replace('\\', '/')
            else -> return null
        }
        val parts = rest.split('/').filter { it.isNotEmpty() }.map { URLDecoder.decode(it.replace("+", "%2B"), StandardCharsets.UTF_8) }
        if (parts.size < 2) return null
        val authority = parts[0]
        val user = authority.substringBeforeLast('@', "").takeIf { it.isNotEmpty() }?.substringBefore(':')
        val host = authority.substringAfterLast('@').substringBefore(':')
        if (host.isEmpty()) return null
        return Location(host, parts[1], parts.drop(2).joinToString("/"), user)
    }

    fun isShare(text: String): Boolean = parse(text) != null

    /**
     * The local folder [text] is written to: [text] itself when it is a plain path, otherwise the
     * share's mount with its folder under it. Mounts the share through GVFS when [mount] allows and
     * nothing has it mounted; a restore, which runs as a session starts, passes false so it never
     * waits on a network.
     */
    fun resolve(text: String, mount: Boolean = true): Path {
        val location = parse(text) ?: return Path.of(text.trim())
        val root = mountedRoot(location)
            ?: if (mount) gioMount(location) else throw IOException("${location.shareUrl} is not mounted")
        if (!Files.isDirectory(root)) throw IOException("${location.shareUrl} is mounted at $root, but it cannot be read")
        return if (location.folder.isEmpty()) root else root.resolve(location.folder)
    }

    private fun mountedRoot(location: Location): Path? =
        fromMounts(location, readLines(Path.of("/proc/mounts")), readLines(Path.of("/etc/fstab")))
            ?: gvfsRoot()?.let { fromGvfs(location, it) }

    /**
     * Where a kernel mount of [location]'s share is, from the lines of `/proc/mounts` and
     * `/etc/fstab`. An fstab entry counts only when its mount point is an automount already set up.
     */
    internal fun fromMounts(location: Location, mounts: List<String>, fstab: List<String>): Path? {
        val mounted = mounts.mapNotNull(::entry)
        mounted.firstOrNull { it.type in SMB_TYPES && matches(location, it.source) }?.let { return Path.of(it.point) }
        val automounts = mounted.filter { it.type == "autofs" }.map { it.point }.toSet()
        return fstab.mapNotNull(::entry)
            .firstOrNull { it.type in SMB_TYPES && it.point in automounts && matches(location, it.source) }
            ?.let { Path.of(it.point) }
    }

    /** The GVFS mount of [location]'s share under [gvfs], named `smb-share:server=host,share=name[,user=…]`. */
    internal fun fromGvfs(location: Location, gvfs: Path): Path? {
        val names = runCatching { gvfs.toFile().list()?.toList() }.getOrNull() ?: return null
        return names.firstOrNull { name ->
            if (!name.startsWith("smb-share:")) return@firstOrNull false
            val keys = name.removePrefix("smb-share:").split(',').associate { it.substringBefore('=') to it.substringAfter('=', "") }
            val server = keys["server"] ?: return@firstOrNull false
            sameHost(server, location.host) &&
                keys["share"].equals(location.share, ignoreCase = true) &&
                (location.user == null || keys["user"] == null || keys["user"].equals(location.user.substringAfter(';'), ignoreCase = true))
        }?.let { gvfs.resolve(it) }
    }

    /** True when the mount source `//host/share` is [location]'s share. */
    private fun matches(location: Location, source: String): Boolean {
        val parts = source.replace('\\', '/').trimStart('/').split('/').filter { it.isNotEmpty() }
        return parts.size >= 2 && sameHost(parts[0], location.host) && parts[1].equals(location.share, ignoreCase = true)
    }

    /** `anhur` and `anhur.local` are one machine: a name is compared by its first label, an address whole. */
    internal fun sameHost(a: String, b: String): Boolean {
        if (a.equals(b, ignoreCase = true)) return true
        if (isAddress(a) || isAddress(b)) return false
        return a.substringBefore('.').equals(b.substringBefore('.'), ignoreCase = true)
    }

    private fun isAddress(host: String): Boolean = host.all { it.isDigit() || it == '.' } || ':' in host || host.startsWith('[')

    private data class Entry(val source: String, val point: String, val type: String)

    private fun entry(line: String): Entry? {
        val fields = line.trim().takeIf { it.isNotEmpty() && !it.startsWith('#') }?.split(Regex("\\s+")) ?: return null
        if (fields.size < 3) return null
        return Entry(unescape(fields[0]), unescape(fields[1]), fields[2])
    }

    /** Mount tables write a space in a path as `\040`, and so on for tab, newline and backslash. */
    private fun unescape(field: String): String =
        Regex("\\\\([0-7]{3})").replace(field) { it.groupValues[1].toInt(8).toChar().toString() }

    private fun readLines(file: Path): List<String> = runCatching { Files.readAllLines(file) }.getOrDefault(emptyList())

    private fun gvfsRoot(): Path? =
        (System.getenv("XDG_RUNTIME_DIR")?.takeIf { it.isNotBlank() }?.let { Path.of(it, "gvfs") }
            ?: runCatching { Path.of("/run/user/${Files.getAttribute(Path.of(System.getProperty("user.home")), "unix:uid")}/gvfs") }.getOrNull())
            ?.takeIf { Files.isDirectory(it) }

    /** Mounts [location]'s share with `gio mount` and returns where GVFS put it. */
    private fun gioMount(location: Location): Path {
        val gvfs = gvfsRoot() ?: throw IOException(
            "${location.shareUrl} is not mounted, and this desktop has no GVFS to mount it with. " +
                "Mount the share (for example in /etc/fstab) and choose the folder it is mounted at.",
        )
        val process = try {
            ProcessBuilder("gio", "mount", location.shareUrl)
                .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
                .redirectErrorStream(true)
                .start()
        } catch (e: IOException) {
            throw IOException("${location.shareUrl} is not mounted, and gio could not be run to mount it: ${e.message}")
        }
        val finished = process.waitFor(MOUNT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        val said = runCatching { process.inputStream.bufferedReader().readText() }.getOrDefault("")
            .lines().map { it.trim() }.filter { it.isNotEmpty() && !it.endsWith(":") }.joinToString(" ")
        fromGvfs(location, gvfs)?.let {
            Log.info("mounted ${location.shareUrl} at $it for the backup")
            return it
        }
        throw IOException(
            "could not mount ${location.shareUrl}" + (if (said.isNotEmpty()) " ($said)" else if (!finished) " (timed out)" else "") +
                ". If it needs a password, open it once in your file manager and let it remember the password.",
        )
    }
}
