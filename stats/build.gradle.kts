plugins {
    alias(libs.plugins.android.application)
}

// The release key, from Gradle properties (put them in ~/.gradle/gradle.properties,
// never in this repo) or else from environment variables.
fun releaseSetting(property: String, variable: String): String? =
    providers.gradleProperty(property).orElse(providers.environmentVariable(variable)).orNull

val releaseKeystore = releaseSetting("datadial.keystore", "DATADIAL_KEYSTORE")
val releaseKeystorePassword = releaseSetting("datadial.keystorePassword", "DATADIAL_KEYSTORE_PASSWORD")
val releaseKeyAlias = releaseSetting("datadial.keyAlias", "DATADIAL_KEY_ALIAS")
val releaseKeyPassword = releaseSetting("datadial.keyPassword", "DATADIAL_KEY_PASSWORD")
val releaseKeyMissing = listOf(releaseKeystore, releaseKeystorePassword, releaseKeyAlias, releaseKeyPassword)
    .any { it.isNullOrEmpty() }

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
        versionCode = 8
        versionName = "2.0.0"
    }

    signingConfigs {
        if (!releaseKeyMissing) {
            create("release") {
                storeFile = file(releaseKeystore!!)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            signingConfig = signingConfigs.findByName("release")
        }
    }
}

// Never build an unsigned (or debug-signed) release: stop with the setup steps instead.
tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    val missing = releaseKeyMissing
    doFirst {
        if (missing) {
            throw GradleException(
                "No release key. Set datadial.keystore, datadial.keystorePassword, " +
                    "datadial.keyAlias and datadial.keyPassword in ~/.gradle/gradle.properties " +
                    "(or DATADIAL_KEYSTORE, DATADIAL_KEYSTORE_PASSWORD, DATADIAL_KEY_ALIAS and " +
                    "DATADIAL_KEY_PASSWORD). See the README."
            )
        }
    }
}

dependencies {
    implementation(libs.wear.complications.data.source.ktx)
    implementation(libs.health.services.client)
    implementation(libs.concurrent.futures.ktx)
    testImplementation(libs.junit)
}
