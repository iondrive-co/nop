package iondrive.nop.update

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * The menu entry for a package install whose updates live in the user's home. The package's own
 * entry is `nop-nop.desktop`, which its install script hands to `xdg-desktop-menu`; an entry of the
 * same name under `~/.local/share/applications` comes first in the desktop's search, so writing one
 * there that starts the home is what makes the menu start the updated nop.
 */
internal object DesktopEntry {

    const val NAME = "nop-nop.desktop"

    fun write(home: Path, applications: Path = Path.of(System.getProperty("user.home"), ".local/share/applications")) {
        Files.createDirectories(applications)
        Files.writeString(
            applications.resolve(NAME),
            """
            [Desktop Entry]
            Name=nop
            Comment=Desktop editor and change reviewer
            Exec=${home.resolve("bin/nop")} %F
            Icon=${home.resolve("lib/nop.png")}
            Terminal=false
            Type=Application
            Categories=Development
            StartupWMClass=iondrive-nop-MainKt
            """.trimIndent() + "\n",
        )
        runCatching { ProcessBuilder("update-desktop-database", applications.toString()).start().waitFor(10, TimeUnit.SECONDS) }
    }
}
