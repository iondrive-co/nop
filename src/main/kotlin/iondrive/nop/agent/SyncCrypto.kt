package iondrive.nop.agent

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * What keeps the synced tabs private from the store that holds them: everything nop puts there is
 * sealed with a key only the user's machines have, so the store (or anybody who gets at its bucket)
 * sees sizes and times but no conversation, and cannot change or swap an object without nop noticing.
 *
 * The sync key is 32 random bytes, written as base64 and copied by hand to every machine. Two keys
 * are drawn from it, one for sealing and one for naming chunks, so that neither use can help undo
 * the other.
 *
 * [seal] is AES-256-GCM with a fresh 12-byte nonce, bound to the object's name ([aad]) so that a
 * sealed object moved to another name does not open. [name] is an HMAC of a chunk, so equal chunks
 * get the same name and are stored once, without the name saying anything about what is in them.
 */
internal class SyncCrypto private constructor(master: ByteArray) {

    private val sealKey = SecretKeySpec(hmac(master, "nop-sync enc".toByteArray()), "AES")
    private val nameKey = hmac(master, "nop-sync mac".toByteArray())

    /** [plain] sealed for the object named [aad]: the nonce, then the ciphertext and its tag. */
    fun seal(plain: ByteArray, aad: String): ByteArray {
        val nonce = ByteArray(NONCE).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, sealKey, GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad.toByteArray())
        return nonce + cipher.doFinal(plain)
    }

    /** What [sealed] holds, or a [SyncFailure] when it was not sealed with this key for [aad]. */
    fun open(sealed: ByteArray, aad: String): ByteArray {
        if (sealed.size < NONCE + TAG_BITS / 8) throw SyncFailure(NOT_OURS)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, sealKey, GCMParameterSpec(TAG_BITS, sealed, 0, NONCE))
        cipher.updateAAD(aad.toByteArray())
        return try {
            cipher.doFinal(sealed, NONCE, sealed.size - NONCE)
        } catch (_: AEADBadTagException) {
            throw SyncFailure(NOT_OURS)
        }
    }

    /** The name a chunk is stored under: its HMAC, in hex. */
    fun name(chunk: ByteArray, offset: Int = 0, length: Int = chunk.size): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(nameKey, "HmacSHA256"))
        mac.update(chunk, offset, length)
        return hex(mac.doFinal())
    }

    companion object {
        private const val NONCE = 12
        private const val TAG_BITS = 128
        private const val KEY_BYTES = 32
        private val random = SecureRandom()

        const val NOT_OURS = "an object in the store was not sealed with this sync key, or was changed since"

        /** The key [text] holds, or null when it is not base64 of 32 bytes. */
        fun parse(text: String): SyncCrypto? {
            val bytes = runCatching { Base64.getDecoder().decode(text.trim()) }.getOrNull() ?: return null
            return if (bytes.size == KEY_BYTES) SyncCrypto(bytes) else null
        }

        /** A new random sync key, as base64. */
        fun generate(): String = Base64.getEncoder().encodeToString(ByteArray(KEY_BYTES).also { random.nextBytes(it) })

        /**
         * [plain] made smaller when it can be: a flag byte, then the bytes deflated (1) or as they
         * were (0), whichever is shorter. Conversations are text and shrink several times over; what
         * is already compressed is kept as it is.
         */
        fun pack(plain: ByteArray, offset: Int = 0, length: Int = plain.size): ByteArray {
            val deflater = Deflater(Deflater.DEFAULT_COMPRESSION)
            try {
                deflater.setInput(plain, offset, length)
                deflater.finish()
                val out = ByteArrayOutputStream(length / 2 + 64)
                out.write(1)
                val buffer = ByteArray(64 * 1024)
                while (!deflater.finished()) {
                    out.write(buffer, 0, deflater.deflate(buffer))
                    if (out.size() > length) break
                }
                if (deflater.finished() && out.size() <= length) return out.toByteArray()
            } finally {
                deflater.end()
            }
            return byteArrayOf(0) + plain.copyOfRange(offset, offset + length)
        }

        /** What [pack] made of [packed], refused when it would come to more than [limit] bytes. */
        fun unpack(packed: ByteArray, limit: Int): ByteArray {
            if (packed.isEmpty()) throw SyncFailure(NOT_OURS)
            return when (packed[0].toInt()) {
                0 -> {
                    if (packed.size - 1 > limit) throw SyncFailure("an object in the store is larger than nop ever makes one")
                    packed.copyOfRange(1, packed.size)
                }
                1 -> {
                    val inflater = Inflater()
                    try {
                        inflater.setInput(packed, 1, packed.size - 1)
                        val out = ByteArrayOutputStream()
                        val buffer = ByteArray(64 * 1024)
                        while (!inflater.finished()) {
                            val n = inflater.inflate(buffer)
                            if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) throw SyncFailure(NOT_OURS)
                            if (out.size() + n > limit) throw SyncFailure("an object in the store is larger than nop ever makes one")
                            out.write(buffer, 0, n)
                        }
                        out.toByteArray()
                    } catch (_: DataFormatException) {
                        throw SyncFailure(NOT_OURS)
                    } finally {
                        inflater.end()
                    }
                }
                else -> throw SyncFailure(NOT_OURS)
            }
        }

        fun hmac(key: ByteArray, data: ByteArray): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            return mac.doFinal(data)
        }

        fun sha256(data: ByteArray, offset: Int = 0, length: Int = data.size): ByteArray =
            MessageDigest.getInstance("SHA-256").also { it.update(data, offset, length) }.digest()

        fun hex(bytes: ByteArray): String {
            val out = StringBuilder(bytes.size * 2)
            for (b in bytes) {
                val v = b.toInt() and 0xff
                out.append(HEX_DIGITS[v ushr 4]).append(HEX_DIGITS[v and 0xf])
            }
            return out.toString()
        }

        private const val HEX_DIGITS = "0123456789abcdef"
    }
}
