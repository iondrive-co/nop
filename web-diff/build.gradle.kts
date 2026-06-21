plugins {
    // No version: the Kotlin Gradle plugin is already on the classpath from the root project's
    // kotlin("jvm"). Re-declaring a version here conflicts ("already on the classpath").
    kotlin("multiplatform")
}

kotlin {
    js(IR) {
        outputModuleName.set("nop-diff")
        browser {
            commonWebpackConfig {
                outputFileName = "nop-diff.js"
            }
            // The unit tests are pure (no DOM); run them on Node, not a headless browser.
            testTask {
                useMocha()
            }
        }
        binaries.executable()
    }

    sourceSets {
        val commonMain by getting
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        val jsMain by getting
    }
}
