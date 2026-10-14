package iondrive.nop.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

class AgentServicesTest {
    @Test
    fun `a service keeps its own ticket across registration and restart`(@TempDir tmp: Path) {
        val dir = tmp.resolve("services")
        val project = Files.createDirectory(tmp.resolve("project"))
        val path = AgentServices.register("Task scheduler", project.toFile(), tmp.resolve("first.sock"), dir)
        val first = AgentServices.read(path)!!
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(path))
        // All a service is given to reach nop with.
        val fields = Json.parseToJsonElement(Files.readString(path)).jsonObject
        assertEquals(setOf("name", "project", "ticket", "socket"), fields.keys)
        assertEquals(project.toString(), first.project)
        assertTrue(Regex("[0-9a-f]{48}").matches(first.ticket), first.ticket)
        assertEquals(first, AgentServices.identify(first.ticket, dir))
        assertNull(AgentServices.identify("unknown", dir))
        AgentServices.register("Task scheduler", project.toFile(), tmp.resolve("second.sock"), dir)
        assertEquals(first.ticket, AgentServices.read(path)!!.ticket)
        AgentServices.attachSocket(tmp.resolve("third.sock"), dir)
        assertEquals(first.copy(socket = tmp.resolve("third.sock").toString()), AgentServices.read(path))
        val elsewhere = Files.createDirectory(tmp.resolve("elsewhere"))
        val other = AgentServices.register("Task scheduler", elsewhere.toFile(), tmp.resolve("third.sock"), dir)
        assertNotEquals(path, other)
        assertNotEquals(first.ticket, AgentServices.read(other)!!.ticket)
    }

    @Test
    fun `public or linked credentials are refused`(@TempDir tmp: Path) {
        val dir = tmp.resolve("services")
        val path = AgentServices.register("Task scheduler", tmp.toFile(), tmp.resolve("endpoint.sock"), dir)
        val registration = AgentServices.read(path)!!
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-r--r--"))
        assertNull(AgentServices.identify(registration.ticket, dir))
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
        val saved = Files.move(path, tmp.resolve("saved.json"))
        Files.createSymbolicLink(path, saved)
        assertNull(AgentServices.identify(registration.ticket, dir))
    }

    @Test
    fun `registration neither loosens a directory nor accepts a multiline identity`(@TempDir tmp: Path) {
        val dir = Files.createDirectory(tmp.resolve("open"))
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"))
        assertThrows(IllegalArgumentException::class.java) {
            AgentServices.register("Task scheduler", tmp.toFile(), tmp.resolve("endpoint.sock"), dir)
        }
        assertEquals(PosixFilePermissions.fromString("rwxr-xr-x"), Files.getPosixFilePermissions(dir))
        for (name in listOf("", "line\nbreak", "escape\u001b", "tab\tname")) {
            assertThrows(IllegalArgumentException::class.java) {
                AgentServices.register(name, tmp.toFile(), tmp.resolve("endpoint.sock"), tmp.resolve("private"))
            }
        }
    }
}
