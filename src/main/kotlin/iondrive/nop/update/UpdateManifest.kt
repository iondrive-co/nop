package iondrive.nop.update

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * A release's `update.json`, as `scripts/update-manifest.py` writes it: the version and platform it
 * is for, the tarball that carries the app image, and every file in that image by sha256.
 *
 * Nothing in a release is trusted on its own. The manifest is trusted because it is signed with the
 * key built into nop ([verified]); the tarball and every file in it are trusted only through their
 * hashes here. Someone able to edit a GitHub release can replace any asset, but cannot sign.
 */
@Serializable
data class UpdateManifest(
    val format: Int,
    val version: String,
    val platform: String,
    val archive: Archive,
    val files: List<Entry>,
) {
    @Serializable
    data class Archive(
        val name: String,
        val sha256: String,
        val size: Long,
        /** The tarball's single top-level directory, which holds the image. */
        val root: String,
    )

    /** One file of the image, or one link when [link] is set. [path] is relative to the image. */
    @Serializable
    data class Entry(
        val path: String,
        val sha256: String? = null,
        val size: Long? = null,
        val executable: Boolean = false,
        val link: String? = null,
    )

    companion object {
        /** The manifest format this nop reads. */
        const val FORMAT = 1

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * [bytes] as a manifest, provided [signature] is [key]'s Ed25519 signature over exactly those
         * bytes and the format is one this nop reads. Throws [UpdateException] otherwise.
         */
        fun verified(bytes: ByteArray, signature: ByteArray, key: PublicKey): UpdateManifest {
            if (!signedBy(bytes, signature, key)) {
                throw UpdateException("the release's update.json is not signed with nop's update key")
            }
            val manifest = runCatching { json.decodeFromString(serializer(), bytes.decodeToString()) }
                .getOrElse { throw UpdateException("the release's update.json does not parse: ${it.message}") }
            if (manifest.format != FORMAT) {
                throw UpdateException("the release's update.json is format ${manifest.format}; this nop reads $FORMAT")
            }
            return manifest
        }

        internal fun signedBy(bytes: ByteArray, signature: ByteArray, key: PublicKey): Boolean = runCatching {
            Signature.getInstance("Ed25519").run {
                initVerify(key)
                update(bytes)
                verify(signature)
            }
        }.getOrDefault(false)

        /** An Ed25519 public key from the PEM `openssl pkey -pubout` writes. */
        fun publicKey(pem: String): PublicKey {
            val body = pem.lineSequence().filterNot { it.startsWith("-----") }.joinToString("").trim()
            return KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(body)))
        }

        /** The key releases are signed with: `src/main/resources/iondrive/nop/update/update-signing-key.pem`. */
        val releaseKey: PublicKey by lazy {
            val pem = UpdateManifest::class.java.getResourceAsStream("update-signing-key.pem")
                ?.use { it.readBytes().decodeToString() }
                ?: error("update-signing-key.pem is missing from the build")
            publicKey(pem)
        }

        /**
         * This machine's platform as release manifests name it, or null for one no release carries a
         * self-update bundle for. Only Linux x64 does: the restart that installs an update hands
         * the running terminals over, and that handover is Linux-only.
         */
        fun currentPlatform(): String? {
            val os = System.getProperty("os.name").orEmpty()
            val arch = System.getProperty("os.arch").orEmpty()
            return if (os.startsWith("Linux") && arch in setOf("amd64", "x86_64")) "linux-x64" else null
        }
    }
}

/** Why an update could not be fetched or staged, in words fit to show the user. */
class UpdateException(message: String) : Exception(message)
