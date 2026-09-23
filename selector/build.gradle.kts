// Vendored copy of gkd-kit/gkd `gkd-selector` (GPL-3.0), JVM target only.
// Upstream commit: see UPSTREAM_COMMIT. Sources are unmodified.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    explicitApi()
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_11) }
    }
    sourceSets {
        all {
            languageSettings.optIn("kotlin.js.ExperimentalJsExport")
            languageSettings.optIn("kotlin.js.ExperimentalJsStatic")
        }
        commonMain {
            dependencies {
                implementation(libs.kotlin.stdlib)
            }
        }
    }
}
