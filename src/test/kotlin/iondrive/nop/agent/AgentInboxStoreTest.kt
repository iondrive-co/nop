package iondrive.nop.agent

import iondrive.nop.ipc.Handover
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class AgentInboxStoreTest {
    @Test
    fun `pending messages retain their approval order and retry receipts until acknowledged`(@TempDir root: Path) {
        val first = Handover.HeldRecord("tab A", "sender-a", "/work/example", "First\n雨", "[Envelope]\nFirst\n雨", 41,
            true, "notice-orchid", "tab:sender-a", attemptedAt = 53, attempts = 2)
        val second = first.copy(id = "notice-fern", body = "Second", typed = "[Envelope]\nSecond", approved = false, attempts = 0)
        assertNull(AgentInboxStore.load("recipient", root))
        AgentInboxStore.save("recipient", listOf(first, second), root)
        assertEquals(listOf(first, second), AgentInboxStore.load("recipient", root))
        AgentInboxStore.save("recipient", listOf(second), root)
        assertEquals(listOf(second), AgentInboxStore.load("recipient", root))
        AgentInboxStore.save("recipient", emptyList(), root)
        assertEquals(emptyList<Handover.HeldRecord>(), AgentInboxStore.load("recipient", root))
    }

    @Test
    fun `an interrupted replacement leaves the acknowledged snapshot readable`(@TempDir root: Path) {
        AgentInboxStore.save("recipient", emptyList(), root)
        Files.writeString(root.resolve(".recipient.tmp"), "{incomplete")
        assertEquals(emptyList<Handover.HeldRecord>(), AgentInboxStore.load("recipient", root))
        Files.writeString(root.resolve("recipient.json"), "{damaged")
        assertThrows(IllegalStateException::class.java) { AgentInboxStore.load("recipient", root) }
        assertEquals("{damaged", Files.readString(root.resolve("recipient.json")))
    }
}
