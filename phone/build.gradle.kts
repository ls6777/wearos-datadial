plugins {
    alias(libs.plugins.android.application)
}

// DataDial Stats on the phone: reads Google Health's daily steps, calories and
// floors from Health Connect and sends them to DataDial Stats on the watch.
// The Wearable Data Layer only connects apps with the same application ID and
// signing key, so it shares the watch app's ID.
android {
    namespace = "io.github.ls6777.datadial.phone"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.ls6777.datadial.stats"
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            // TODO: replace with a release signing config before publishing
            // (the same key as DataDial Stats on the watch).
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.health.connect.client)
    implementation(libs.coroutines.play.services)
    implementation(libs.work.runtime.ktx)
    implementation(libs.activity.ktx)
}
