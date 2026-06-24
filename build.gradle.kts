import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

plugins {
    kotlin("jvm") version "2.2.20"
    id("org.jetbrains.compose") version "1.10.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20"
}

kotlin {
    jvmToolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    implementation("org.jetbrains.jewel:jewel-int-ui-standalone:0.34.0-253.32098.37")
    implementation(compose.desktop.currentOs) {
        exclude(group = "org.jetbrains.compose.material")
    }

    implementation("net.java.dev.jna:jna:5.17.0")
    implementation("org.eclipse.jgit:org.eclipse.jgit:7.1.1.202505221757-r")
    implementation("io.github.java-diff-utils:java-diff-utils:4.16")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    // Used only via the JsonElement API to parse package.json — no compiler plugin needed.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.commonmark:commonmark:0.24.0")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.24.0")
    implementation("org.commonmark:commonmark-ext-gfm-strikethrough:0.24.0")

    // Real terminal for launcher runs: pty4j allocates a pseudo-terminal (so child processes see
    // a TTY and password prompts / full-screen apps work), JediTerm is the Swing VT100 widget that
    // renders it — the same stack IntelliJ's embedded terminal uses. jediterm-* resolve from the
    // intellij-dependencies repo declared in settings.gradle.kts; slf4j-nop silences jediterm-ui's
    // "no SLF4J provider" warning. pty4j reuses the JNA dependency already declared above.
    implementation("org.jetbrains.pty4j:pty4j:0.13.12")
    implementation("org.jetbrains.jediterm:jediterm-core:3.72")
    implementation("org.jetbrains.jediterm:jediterm-ui:3.72")
    implementation("org.slf4j:slf4j-nop:2.0.9")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.4")
}

tasks.test {
    useJUnitPlatform()
    // After tests pass, refresh the deployed jar that the desktop launcher points at. Skipping
    // this step has bitten us — code under test would change but the menu icon still ran the
    // stale jar from build/compose/binaries/main/app/nop/. Up-to-date checks make this a ~1s
    // no-op when nothing has changed.
    finalizedBy("installDesktopEntry")
}

compose.desktop {
    application {
        mainClass = "iondrive.nop.MainKt"
        javaHome = javaToolchains.launcherFor {
            languageVersion = JavaLanguageVersion.of(21)
        }.get().metadata.installationPath.asFile.absolutePath

        // A desktop editor never needs the JVM default max heap (¼ of RAM — ~15 GB on a 62 GB
        // box). The live heap sits around 60 MB even with several windows open, so cap it as a
        // runaway guard. String dedup reclaims the many identical strings the index produces
        // (repeated file paths, symbol names); it requires G1, which is already the default GC.
        jvmArgs += listOf("-Xmx512m", "-XX:+UseStringDeduplication")

        nativeDistributions {
            targetFormats(TargetFormat.AppImage, TargetFormat.Deb, TargetFormat.Dmg, TargetFormat.Msi)
            // JGit's WindowCache publishes a JMX MBean on first use, so the jlinked runtime
            // must include java.management or opening any repo throws NoClassDefFoundError.
            modules("java.management")
            packageName = "nop"
            packageVersion = "0.20.0"
            description = "Desktop editor and change reviewer"
            vendor = "iondrive"
            copyright = "Copyright (c) 2026 iondrive. All rights reserved."

            linux {
                menuGroup = "Development"
                appCategory = "Development"
                shortcut = true
            }
            // jpackage's macOS/Windows installers reject MAJOR=0, so use 1.0.0 there until
            // we bump packageVersion past 0.x. The Linux .deb keeps the project-level value.
            macOS {
                packageVersion = "1.0.0"
                dmgPackageVersion = "1.0.0"
            }
            windows {
                packageVersion = "1.0.0"
                msiPackageVersion = "1.0.0"
                menuGroup = "nop"
                shortcut = true
                // Stable UUID lets future MSIs upgrade this install in place instead of
                // appearing as a separate "nop" entry in Add/Remove Programs. Generated once
                // with `uuidgen` — never change it for an existing product line.
                upgradeUuid = "5a7e1f2b-9c4d-4a3e-8b1f-2d6c4b1a9e07"
            }
        }
    }
}

// Installs a .desktop entry into ~/.local/share/applications pointing at the createDistributable
// output, so `nop` appears in the system app launcher without needing to install the .deb.
// Inputs/outputs are declared so `./gradlew --continuous installDesktopEntry` re-runs on edits.
tasks.register("installDesktopEntry") {
    group = "distribution"
    description = "Write ~/.local/share/applications/iondrive.nop.desktop pointing at the local distributable"
    dependsOn("createDistributable")

    val distDir = layout.buildDirectory.dir("compose/binaries/main/app/nop")
    val home = System.getProperty("user.home")
    val desktopFile = file("$home/.local/share/applications/iondrive.nop.desktop")
    // Install at multiple sizes under the hicolor theme so GNOME / KDE / XFCE icon caches can
    // pick the right one. Most desktops look up Icon=iondrive.nop here first; an absolute path
    // in the .desktop entry works as a fallback for the launchers that don't follow the spec.
    val iconSizes = listOf(48, 64, 128, 256)
    val themedIcons = iconSizes.map { size ->
        size to file("$home/.local/share/icons/hicolor/${size}x${size}/apps/iondrive.nop.png")
    }
    val fallbackIcon = file("$home/.local/share/icons/iondrive.nop.png")

    inputs.dir(distDir)
    outputs.files(desktopFile, fallbackIcon, *themedIcons.map { it.second }.toTypedArray())

    doLast {
        // Draw the "n" tile at each requested size in a neutral grey, so the launcher icon
        // exists even before any project window picks its tint. Per-window icons override at
        // runtime via Window(icon = …). Java2D keeps this self-contained.
        fun renderTile(size: Int): BufferedImage {
            val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
            val g = img.createGraphics()
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                g.composite = AlphaComposite.Src
                g.color = Color(0, 0, 0, 0)
                g.fillRect(0, 0, size, size)
                g.color = Color(0x4F, 0x55, 0x66)
                val arc = size / 5
                g.fillRoundRect(0, 0, size, size, arc, arc)
                g.color = Color(0xF7, 0xF8, 0xFA)
                g.font = Font(Font.SANS_SERIF, Font.BOLD, (size * 0.72f).toInt())
                val fm = g.fontMetrics
                val txt = "n"
                val tx = (size - fm.stringWidth(txt)) / 2
                val ty = (size - fm.height) / 2 + fm.ascent - (size / 24)
                g.drawString(txt, tx, ty)
            } finally {
                g.dispose()
            }
            return img
        }

        for ((size, file) in themedIcons) {
            file.parentFile.mkdirs()
            ImageIO.write(renderTile(size), "PNG", file)
        }
        fallbackIcon.parentFile.mkdirs()
        ImageIO.write(renderTile(128), "PNG", fallbackIcon)

        desktopFile.parentFile.mkdirs()
        val bin = distDir.get().asFile.resolve("bin/nop")
        // Icon= uses the icon-theme name (iondrive.nop); launchers resolve it against the
        // hicolor PNGs above. StartupWMClass must match the actual WM_CLASS the running window
        // advertises — Compose Desktop / Skiko derive that from the JVM main class name and we
        // have no Java-side hook to override before the X11 toolkit caches it, so we mirror the
        // generated value here rather than try to rename it.
        desktopFile.writeText(
            """
            [Desktop Entry]
            Type=Application
            Name=nop
            GenericName=Desktop editor
            Comment=Desktop editor and change reviewer
            Exec=${bin.absolutePath} %F
            Icon=iondrive.nop
            Terminal=false
            Categories=Development;TextEditor;
            StartupWMClass=iondrive-nop-MainKt
            """.trimIndent() + "\n",
        )
        // Best-effort cache refresh so newly-installed icons + .desktop are visible without
        // having to log out. Both tools are widely available; failures are ignored.
        listOf(
            listOf("update-desktop-database", "$home/.local/share/applications"),
            listOf("gtk-update-icon-cache", "-f", "-t", "$home/.local/share/icons/hicolor"),
        ).forEach { cmd ->
            runCatching {
                ProcessBuilder(cmd).redirectErrorStream(true).start().waitFor()
            }
        }
        println("Wrote ${desktopFile.absolutePath} -> ${bin.absolutePath}")
        for ((_, f) in themedIcons) println("Wrote ${f.absolutePath}")
        println("Wrote ${fallbackIcon.absolutePath}")
    }
}

// Convenience: runs continuous mode + a desktop notification on every successful rebuild,
// so you can `./gradlew watch` once and forget about it. Continuous mode watches the inputs
// of installDesktopEntry (which transitively includes src/) and re-runs on save.
tasks.register("watch") {
    group = "distribution"
    description = "Print instructions for the auto-rebuild loop"
    doLast {
        println(
            """
            |Auto-rebuild loop (option B): run this in a terminal and leave it running —
            |
            |    ./gradlew --continuous installDesktopEntry
            |
            |Each save under src/ triggers an incremental rebuild + refreshes the launcher
            |binary at build/compose/binaries/main/app/nop/bin/nop, so the next click on the
            |menu icon picks up the change. Ctrl-C stops the loop.
            |
            |Or run `scripts/watch.sh` which does the same with a notify-send ping per build.
            """.trimMargin(),
        )
    }
}
