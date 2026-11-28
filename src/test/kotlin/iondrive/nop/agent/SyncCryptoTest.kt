package iondrive.nop.agent

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64
import kotlin.random.Random

/** Sealing what nop puts in the store, so the store learns nothing and can change nothing. */
class SyncCryptoTest {

    private val crypto = SyncCrypto.parse(SyncCrypto.generate())!!

    @Test
    fun `a sealed object opens only under its own name and key`() {
        val plain = "{\"type\":\"session_started\"}".toByteArray()
        val sealed = crypto.seal(plain, "projects/abc/lease")
        assertArrayEquals(plain, crypto.open(sealed, "projects/abc/lease"))
        assertNotEquals(sealed.toList(), crypto.seal(plain, "projects/abc/lease").toList())

        val moved = assertThrows(SyncFailure::class.java) { crypto.open(sealed, "projects/def/lease") }
        assertEquals(SyncCrypto.NOT_OURS, moved.message)
        val other = SyncCrypto.parse(SyncCrypto.generate())!!
        assertEquals(SyncCrypto.NOT_OURS, assertThrows(SyncFailure::class.java) { other.open(sealed, "projects/abc/lease") }.message)
        val changed = sealed.clone().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
        assertEquals(SyncCrypto.NOT_OURS, assertThrows(SyncFailure::class.java) { crypto.open(changed, "projects/abc/lease") }.message)
        assertThrows(SyncFailure::class.java) { crypto.open(ByteArray(5), "projects/abc/lease") }
    }

    @Test
    fun `only a key of 32 bytes in base64 is taken`() {
        assertNotNull(SyncCrypto.parse(SyncCrypto.generate()))
        assertNotNull(SyncCrypto.parse("  " + SyncCrypto.generate() + "\n"))
        assertNull(SyncCrypto.parse("short"))
        assertNull(SyncCrypto.parse(""))
        assertNull(SyncCrypto.parse(Base64.getEncoder().encodeToString(ByteArray(16))))
        assertNotEquals(SyncCrypto.generate(), SyncCrypto.generate())
    }

    @Test
    fun `packed bytes come back the same and are held to a limit`() {
        val text = "{\"type\":\"user_message\",\"text\":\"hello\"}\n".repeat(2000).toByteArray()
        val packedText = SyncCrypto.pack(text)
        assertTrue(packedText.size < text.size / 4, "text packed to ${packedText.size} bytes")
        assertArrayEquals(text, SyncCrypto.unpack(packedText, text.size))

        val noise = Random(7).nextBytes(100_000)
        val packedNoise = SyncCrypto.pack(noise)
        assertTrue(packedNoise.size <= noise.size + 16, "noise packed to ${packedNoise.size} bytes")
        assertArrayEquals(noise, SyncCrypto.unpack(packedNoise, noise.size))

        assertArrayEquals(text.copyOfRange(10, 500), SyncCrypto.unpack(SyncCrypto.pack(text, 10, 490), 490))
        assertArrayEquals(ByteArray(0), SyncCrypto.unpack(SyncCrypto.pack(ByteArray(0)), 0))

        assertThrows(SyncFailure::class.java) { SyncCrypto.unpack(packedText, text.size - 1) }
        assertThrows(SyncFailure::class.java) { SyncCrypto.unpack(packedNoise, noise.size - 1) }
        assertThrows(SyncFailure::class.java) { SyncCrypto.unpack(ByteArray(0), 10) }
    }

    @Test
    fun `a chunk's name depends on its bytes and the key`() {
        val chunk = Random(3).nextBytes(4096)
        val name = crypto.name(chunk)
        assertTrue(Regex("[0-9a-f]{64}").matches(name), name)
        assertEquals(name, crypto.name(chunk.copyOf()))
        assertEquals(crypto.name(chunk, 100, 200), crypto.name(chunk.copyOfRange(100, 300)))
        assertNotEquals(name, crypto.name(chunk, 0, 4095))
        assertNotEquals(name, SyncCrypto.parse(SyncCrypto.generate())!!.name(chunk))
    }
}
