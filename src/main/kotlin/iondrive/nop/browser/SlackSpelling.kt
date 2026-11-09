package iondrive.nop.browser

import iondrive.nop.Log
import org.cef.browser.CefRequestContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Chromium checks message drafts locally; English variants match the editor's word list. */
internal object SlackSpelling {
    private val languages = listOf("en-AU", "en-GB", "en-US")

    /** Seed CEF's pinned dictionary filenames before it starts, including on an offline first run. */
    fun prepare(profile: Path) {
        val directory = profile.resolve("Dictionaries")
        Files.createDirectories(directory)
        for (language in languages) {
            val filename = "$language-10-1.bdic"
            val target = directory.resolve(filename)
            if (Files.isRegularFile(target)) continue
            val temporary = Files.createTempFile(directory, "dictionary-", ".tmp")
            try {
                checkNotNull(javaClass.getResourceAsStream("/browser/dictionaries/$filename")).use {
                    Files.copy(it, temporary, StandardCopyOption.REPLACE_EXISTING)
                }
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            } finally {
                Files.deleteIfExists(temporary)
            }
        }
    }

    /** Called on CEF's UI thread, where request-context preferences may be changed. */
    fun configure(context: CefRequestContext) {
        for ((name, value) in mapOf(
            "browser.enable_spellchecking" to true,
            "spellcheck.dictionaries" to languages,
            "spellcheck.use_spelling_service" to false,
        )) {
            context.setPreference(name, value)?.let { Log.warn("Could not configure Slack spelling ($name): $it") }
        }
    }
}
