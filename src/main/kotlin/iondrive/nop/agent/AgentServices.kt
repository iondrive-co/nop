package iondrive.nop.agent

import iondrive.nop.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest

/**
 * Services: programs of the user's that message a project's agent tabs under their own name, with no
 * tab of their own — a scheduler, say, or a controller that reports what it did.
 *
 * A tab registers one for its project (`nop-msg service-register <name>`) and is told the path of
 * its registration: an owner-only JSON file in a private directory, holding the service's name,
 * project, ticket and the socket to reach nop at ([Registration]). The service then talks to
 * [AgentSocket] as `nop-msg` does, with that ticket in place of a tab's, and [AgentMessages] keeps
 * it to its project's tabs. The ticket belongs to the service rather than the tab, so it keeps
 * working once that tab has closed; registering the same name for the same project again keeps it,
 * and a restarted nop writes its new socket into every registration ([attachSocket]).
 *
 * A file that is not private, is a link, or does not hold what its name says is not a registration:
 * it identifies nobody and is never written over.
 */
internal object AgentServices {

    @Serializable
    data class Registration(val name: String, val project: String, val ticket: String, val socket: String) {
        val projectDir: File get() = File(project)
    }

    /** `services` under nop's data root. */
    fun directory(): Path = Accounts.dataRoot().resolve("services")

    /**
     * Registers [name] for [project], reachable at [socket], and returns where the registration is.
     * The same name for the same project is the same service, and keeps its ticket.
     */
    @Synchronized
    fun register(name: String, project: File, socket: Path, dir: Path = directory()): Path {
        require(isPlainName(name)) { "a service's name is 1 to $MAX_NAME characters on one line, with no control characters" }
        OwnerOnly.directory(dir)
        require(AgentSocket.isPrivate(dir)) { "$dir is not a private directory of this user's" }
        val home = project.absoluteFile.normalize()
        val path = dir.resolve(fileName(name, home))
        val previous = read(path)
        require(previous != null || !Files.exists(path, LinkOption.NOFOLLOW_LINKS)) { "$path is not a registration nop wrote; it is left as it is" }
        write(path, Registration(name, home.path, previous?.ticket ?: AgentSocket.newTicket(), socket.toString()))
        Log.info("agent service \"$name\" ${if (previous == null) "registered" else "registered again"} for $home")
        return path
    }

    /** The service whose ticket [ticket] is. */
    fun identify(ticket: String, dir: Path = directory()): Registration? = registrations(dir)
        .firstOrNull { MessageDigest.isEqual(it.second.ticket.toByteArray(), ticket.toByteArray()) }?.second

    /** Points every registration at [socket]: a restart changes where nop listens, never who a service is. */
    @Synchronized
    fun attachSocket(socket: Path, dir: Path = directory()) {
        registrations(dir).forEach { (path, registration) ->
            if (registration.socket != socket.toString()) write(path, registration.copy(socket = socket.toString()))
        }
    }

    private fun registrations(dir: Path): List<Pair<Path, Registration>> {
        if (!AgentSocket.isPrivate(dir)) return emptyList()
        return Files.list(dir).use { paths ->
            paths.filter { FILE_NAME.matches(it.fileName.toString()) }.toList().mapNotNull { path -> read(path)?.let { path to it } }
        }
    }

    /** The registration at [path], or null when it is anything but one nop wrote and nobody else can read. */
    internal fun read(path: Path): Registration? = runCatching {
        require(AgentSocket.isPrivate(path.parent))
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && Files.size(path) <= MAX_BYTES)
        require(Files.getOwner(path, LinkOption.NOFOLLOW_LINKS).name == System.getProperty("user.name"))
        require(Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS).none { it.name.startsWith("GROUP") || it.name.startsWith("OTHERS") })
        Json.decodeFromString<Registration>(Files.readString(path)).also {
            require(isPlainName(it.name))
            require(it.projectDir.isAbsolute && it.projectDir == it.projectDir.normalize())
            require(TICKET.matches(it.ticket))
            require(Path.of(it.socket).isAbsolute)
            require(path.fileName.toString() == fileName(it.name, it.projectDir))
        }
    }.getOrNull()

    private fun isPlainName(name: String) =
        name.isNotBlank() && name.length <= MAX_NAME && name.none { it == '\n' || it == '\t' } && AgentMessages.clean(name) == name

    /** The file a service is kept in, named for its project and name together. */
    private fun fileName(name: String, project: File): String = MessageDigest.getInstance("SHA-256")
        .digest((project.path + "\n" + name).toByteArray())
        .joinToString("", postfix = ".json") { "%02x".format(it) }

    private fun write(path: Path, registration: Registration) {
        val tmp = Files.createTempFile(path.parent, ".service-", ".tmp", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        try {
            Files.writeString(tmp, Json.encodeToString(registration))
            Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    private const val MAX_NAME = 80
    private const val MAX_BYTES = 8192L
    private val FILE_NAME = Regex("[0-9a-f]{64}\\.json")
    private val TICKET = Regex("[0-9a-f]{48}")
}
