import org.jetbrains.compose.desktop.application.dsl.TargetFormat

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

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.4")
}

tasks.test {
    useJUnitPlatform()
}

compose.desktop {
    application {
        mainClass = "iondrive.nop.MainKt"
        javaHome = javaToolchains.launcherFor {
            languageVersion = JavaLanguageVersion.of(21)
        }.get().metadata.installationPath.asFile.absolutePath

        nativeDistributions {
            targetFormats(TargetFormat.AppImage, TargetFormat.Deb, TargetFormat.Dmg, TargetFormat.Msi)
            // JGit's WindowCache publishes a JMX MBean on first use, so the jlinked runtime
            // must include java.management or opening any repo throws NoClassDefFoundError.
            modules("java.management")
            packageName = "nop"
            packageVersion = "0.1.0"
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

    inputs.dir(distDir)
    outputs.file(desktopFile)

    doLast {
        desktopFile.parentFile.mkdirs()
        val bin = distDir.get().asFile.resolve("bin/nop")
        desktopFile.writeText(
            """
            [Desktop Entry]
            Type=Application
            Name=nop
            GenericName=Desktop editor
            Comment=Desktop editor and change reviewer
            Exec=${bin.absolutePath} %F
            Terminal=false
            Categories=Development;TextEditor;
            StartupWMClass=nop
            """.trimIndent() + "\n",
        )
        println("Wrote ${desktopFile.absolutePath} -> ${bin.absolutePath}")
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
