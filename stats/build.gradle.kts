plugins {
    alias(libs.plugins.android.application)
}

// DataDial Stats: the elevation complication data source, for a stat Watch
// Face Format can't read. A separate app, because a Watch Face Format bundle
// can't contain code.
android {
    namespace = "io.github.ls6777.datadial.stats"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.ls6777.datadial.stats"
        minSdk = 36
        targetSdk = 36
        versionCode = 7
        versionName = "1.1.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            // TODO: replace with a release signing config before publishing.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

dependencies {
    implementation(libs.wear.complications.data.source.ktx)
    implementation(libs.health.services.client)
    implementation(libs.concurrent.futures.ktx)
    testImplementation(libs.junit)
}
