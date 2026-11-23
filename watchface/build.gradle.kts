plugins {
    alias(libs.plugins.android.application)
}

android {
    // Watch Face Format bundles are resource-only: no Kotlin or Java code.
    enableKotlin = false
    namespace = "io.github.ls6777.datadial"
    // Watch Face Format version 4 needs Wear OS 6 (API 36) or later.
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.ls6777.datadial"
        minSdk = 36
        targetSdk = 36
        versionCode = 5
        versionName = "1.0.4"
    }

    buildTypes {
        debug {
            isMinifyEnabled = true
        }
        release {
            isMinifyEnabled = true
            // Resource shrinking would drop the drawables, which only the
            // watch face XML refers to.
            isShrinkResources = false
            // TODO: replace with a release signing config before publishing.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}
