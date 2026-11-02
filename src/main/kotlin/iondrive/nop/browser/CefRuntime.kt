package iondrive.nop.browser

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Properties

/** Native runtime identity is generated from the exact library shipped in this app. */
internal object CefRuntime {
    private val linux = System.getProperty("os.name").startsWith("Linux")
    private val metadata by lazy {
        Properties().apply {
            CefRuntime::class.java.getResourceAsStream("/nop-cef-runtime.properties")?.use(::load)
        }
    }

    val directoryName: String
        get() = if (linux) {
            checkNotNull(metadata.getProperty("directory")) { "This build is missing the corrected Chromium runtime" }
        } else "runtime-152.0.6"

    fun verify(directory: Path) {
        if (linux) verifyLibrary(directory.resolve("libcef.so"), metadata.getProperty("linuxLibcefSha256"))
    }

    fun verifyLibrary(library: Path, expected: String?) {
        check(expected != null && expected.matches(Regex("[0-9a-f]{64}"))) {
            "This build is missing the corrected Chromium runtime checksum"
        }
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(library).use { stream ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        check(actual == expected) {
            "Chromium runtime verification failed. Remove the runtime cache directory and retry: ${library.parent}"
        }
    }
}
