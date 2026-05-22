package iondrive.nop.launchers

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class LauncherDiscoveryTest {
    @Test
    fun `returns empty when package json missing`(@TempDir tmp: Path) {
        assertEquals(emptyList<Launcher>(), discoverLaunchers(tmp))
    }

    @Test
    fun `parses scripts and uses npm by default`(@TempDir tmp: Path) {
        Files.writeString(
            tmp.resolve("package.json"),
            """
            {
              "name": "demo",
              "scripts": {
                "build": "tsc -p .",
                "test": "vitest run"
              }
            }
            """.trimIndent(),
        )

        val launchers = discoverLaunchers(tmp)
        assertEquals(
            listOf(
                Launcher("npm: build", "npm run build"),
                Launcher("npm: test", "npm run test"),
            ),
            launchers,
        )
    }

    @Test
    fun `bun lockfile selects bun runner`(@TempDir tmp: Path) {
        Files.writeString(tmp.resolve("package.json"), """{"scripts":{"dev":"vite"}}""")
        Files.writeString(tmp.resolve("bun.lockb"), "")

        assertEquals(listOf(Launcher("bun: dev", "bun run dev")), discoverLaunchers(tmp))
    }

    @Test
    fun `pnpm lockfile selects pnpm runner`(@TempDir tmp: Path) {
        Files.writeString(tmp.resolve("package.json"), """{"scripts":{"dev":"vite"}}""")
        Files.writeString(tmp.resolve("pnpm-lock.yaml"), "")

        assertEquals(listOf(Launcher("pnpm: dev", "pnpm run dev")), discoverLaunchers(tmp))
    }

    @Test
    fun `yarn lockfile selects yarn runner`(@TempDir tmp: Path) {
        Files.writeString(tmp.resolve("package.json"), """{"scripts":{"dev":"vite"}}""")
        Files.writeString(tmp.resolve("yarn.lock"), "")

        assertEquals(listOf(Launcher("yarn: dev", "yarn run dev")), discoverLaunchers(tmp))
    }

    @Test
    fun `bun wins when multiple lockfiles are present`(@TempDir tmp: Path) {
        Files.writeString(tmp.resolve("package.json"), """{"scripts":{"x":"echo"}}""")
        Files.writeString(tmp.resolve("bun.lockb"), "")
        Files.writeString(tmp.resolve("pnpm-lock.yaml"), "")
        Files.writeString(tmp.resolve("yarn.lock"), "")

        assertEquals(listOf(Launcher("bun: x", "bun run x")), discoverLaunchers(tmp))
    }

    @Test
    fun `returns empty when scripts section is missing`(@TempDir tmp: Path) {
        Files.writeString(tmp.resolve("package.json"), """{"name": "demo", "version": "1.0.0"}""")
        assertEquals(emptyList<Launcher>(), discoverLaunchers(tmp))
    }

    @Test
    fun `returns empty on malformed json`(@TempDir tmp: Path) {
        Files.writeString(tmp.resolve("package.json"), "{not valid json")
        assertEquals(emptyList<Launcher>(), discoverLaunchers(tmp))
    }

    @Test
    fun `skips non-string and blank script entries`(@TempDir tmp: Path) {
        Files.writeString(
            tmp.resolve("package.json"),
            """
            {
              "scripts": {
                "ok": "echo hi",
                "blank": "",
                "obj": { "win": "echo win" },
                "num": 42
              }
            }
            """.trimIndent(),
        )

        assertEquals(listOf(Launcher("npm: ok", "npm run ok")), discoverLaunchers(tmp))
    }

    @Test
    fun `quoted script bodies do not confuse the parser`(@TempDir tmp: Path) {
        // Inner-quote handling is the whole reason we picked a real JSON parser over regex.
        Files.writeString(
            tmp.resolve("package.json"),
            """
            {
              "scripts": {
                "build": "webpack --define 'process.env.NODE_ENV=\"production\"'"
              }
            }
            """.trimIndent(),
        )

        val launchers = discoverLaunchers(tmp)
        assertEquals(1, launchers.size)
        assertTrue(launchers[0].name == "npm: build")
        assertTrue(launchers[0].command == "npm run build")
    }
}
