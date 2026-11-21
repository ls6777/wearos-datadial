plugins {
    alias(libs.plugins.android.library)
}

// What the phone app and DataDial Stats on the watch send each other over the
// Wearable Data Layer, so both sides agree on the paths and keys.
android {
    namespace = "io.github.ls6777.datadial.shared"
    compileSdk = 36

    defaultConfig {
        minSdk = 34
    }
}

dependencies {
    api(libs.play.services.wearable)
}
