package iondrive.nop.browser

import iondrive.nop.Log
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Explicit project associations, separate from the catalog of saved sign-ins. */
internal class SlackProjectStore(private val directory: (Path) -> Path) {
    private val json = Json { ignoreUnknownKeys = true }

    fun load(project: Path): Set<String> = runCatching {
        val file = directory(project).resolve("slack-workspaces.json")
        if (!Files.isRegularFile(file) || Files.size(file) > 65_536) return@runCatching emptySet()
        json.decodeFromString<List<String>>(Files.readString(file))
            .filter(SlackWorkspaceNames::validId).take(128).toSet()
    }.getOrDefault(emptySet())

    fun save(project: Path, ids: Set<String>) {
        runCatching {
            val file = directory(project).resolve("slack-workspaces.json")
            Files.createDirectories(file.parent)
            val temporary = file.resolveSibling("slack-workspaces.json.tmp")
            Files.writeString(temporary, json.encodeToString(ids.filter(SlackWorkspaceNames::validId).take(128)))
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
        }.onFailure { Log.warn("Could not save project Slack tabs: ${it.message}") }
    }
}
