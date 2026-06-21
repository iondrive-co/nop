@file:Suppress("UnstableApiUsage")

rootProject.name = "nop"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
        maven("https://www.jetbrains.com/intellij-repository/releases")
        maven("https://cache-redirector.jetbrains.com/intellij-dependencies")
    }
}

// Standalone client-side diff viewer that transpiles nop's pure diff/highlight logic to JS.
// Independent of the desktop app build — see web-diff/README.md.
include("web-diff")
