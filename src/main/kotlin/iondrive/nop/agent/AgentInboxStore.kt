package iondrive.nop.agent

import iondrive.nop.Log
import iondrive.nop.ipc.Handover
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/** Unacknowledged messages survive process failure as well as an orderly handover. */
internal object AgentInboxStore {
    private val json = Json { ignoreUnknownKeys = true }

    fun load(sessionId: String, root: Path = Accounts.dataRoot().resolve("inboxes")): List<Handover.HeldRecord>? {
        val file = root.resolve("$sessionId.json")
        if (!Files.exists(file)) return null
        return try {
            json.decodeFromString<List<Handover.HeldRecord>>(Files.readString(file))
        } catch (e: Exception) {
            Log.warn("could not read agent inbox $file: $e")
            throw IllegalStateException("Cannot restore saved messages from $file", e)
        }
    }

    fun save(sessionId: String, messages: List<Handover.HeldRecord>, root: Path = Accounts.dataRoot().resolve("inboxes")) {
        OwnerOnly.directory(root)
        val file = root.resolve("$sessionId.json")
        val temporary = OwnerOnly.file(root.resolve(".$sessionId.tmp"))
        val bytes = json.encodeToString(messages).toByteArray(Charsets.UTF_8)
        FileChannel.open(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { channel ->
            val buffer = java.nio.ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
        Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}
