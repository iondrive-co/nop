package iondrive.nop.browser

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.assertFailsWith

class CefRuntimeTest {
    @Test
    fun `only the exact bundled native library passes verification`(@TempDir directory: Path) {
        val library = directory.resolve("libcef.so")
        val data = "invented native library bytes".toByteArray()
        val expected = MessageDigest.getInstance("SHA-256").digest(data)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        Files.write(library, data)
        CefRuntime.verifyLibrary(library, expected)
        Files.writeString(library, "different native bytes")
        assertFailsWith<IllegalStateException> { CefRuntime.verifyLibrary(library, expected) }
    }

    @Test
    fun `missing or invalid checksum cannot authorize a runtime`(@TempDir directory: Path) {
        val library = directory.resolve("libcef.so")
        Files.writeString(library, "invented native library")
        for (checksum in listOf(null, "", "not-a-sha256")) {
            assertFailsWith<IllegalStateException> { CefRuntime.verifyLibrary(library, checksum) }
        }
    }
}
