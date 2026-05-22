package iondrive.nop.launchers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path

/**
 * Reads `package.json` at the project root and surfaces every `scripts` entry as a launcher.
 * The package manager is picked from whichever lockfile is present (bun > pnpm > yarn > npm),
 * so `scripts.build = "tsc"` becomes a launcher running `npm run build` — or `bun run build`,
 * `pnpm run build`, `yarn run build` — without us having to inline the script body.
 *
 * Returns an empty list on missing/malformed JSON: discovery should never throw into the UI.
 */
fun discoverLaunchers(projectRoot: Path): List<Launcher> {
    val pkg = projectRoot.resolve("package.json")
    if (!Files.isRegularFile(pkg)) return emptyList()
    val text = runCatching { Files.readString(pkg) }.getOrNull() ?: return emptyList()
    val scripts = runCatching {
        Json.parseToJsonElement(text).jsonObject["scripts"] as? JsonObject
    }.getOrNull() ?: return emptyList()

    val runner = detectPackageManager(projectRoot)
    return scripts.entries.mapNotNull { (name, value) ->
        val body = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@mapNotNull null
        if (body.isBlank()) return@mapNotNull null
        runCatching { Launcher("$runner: $name", "$runner run $name") }.getOrNull()
    }
}

private fun detectPackageManager(projectRoot: Path): String = when {
    Files.exists(projectRoot.resolve("bun.lockb")) -> "bun"
    Files.exists(projectRoot.resolve("pnpm-lock.yaml")) -> "pnpm"
    Files.exists(projectRoot.resolve("yarn.lock")) -> "yarn"
    else -> "npm"
}
