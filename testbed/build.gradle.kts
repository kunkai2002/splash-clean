// Fake "ad" screens used to test SplashClean on an emulator. Never shipped.
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "io.github.kunkai2002.splashclean.testbed"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "io.github.kunkai2002.splashclean.testbed"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}
