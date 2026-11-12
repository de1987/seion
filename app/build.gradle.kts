import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

/*
 * Release signing. The key never lives in the repo. Settings come from `keystore.properties` in the project root
 * (git-ignored; see keystore.properties.example; SEION_KEYSTORE_PROPERTIES points at another file) or from
 * environment variables, which win:
 *
 *   storeFile      SEION_RELEASE_STORE_FILE      keystore path, relative to the project root or absolute
 *   storePassword  SEION_RELEASE_STORE_PASSWORD
 *   keyAlias       SEION_RELEASE_KEY_ALIAS
 *   keyPassword    SEION_RELEASE_KEY_PASSWORD
 *
 * Debug builds don't need any of this. Without a complete signing config the release tasks fail with a clear message.
 */
val keystorePropertiesFile: File =
    providers
        .environmentVariable("SEION_KEYSTORE_PROPERTIES")
        .orNull
        ?.takeIf { it.isNotBlank() }
        ?.let(::File)
        ?: rootProject.file("keystore.properties")
val releaseProperties =
    Properties().apply {
        providers
            .fileContents(objects.fileProperty().fileValue(keystorePropertiesFile))
            .asText.orNull
            ?.let { load(it.reader()) }
    }

fun releaseSetting(env: String, key: String): String? {
    val fromEnv = providers.environmentVariable(env).orNull?.takeIf { it.isNotBlank() }
    return fromEnv ?: releaseProperties.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() }
}

val releaseSigning =
    mapOf(
        "storeFile" to releaseSetting("SEION_RELEASE_STORE_FILE", "storeFile"),
        "storePassword" to releaseSetting("SEION_RELEASE_STORE_PASSWORD", "storePassword"),
        "keyAlias" to releaseSetting("SEION_RELEASE_KEY_ALIAS", "keyAlias"),
        "keyPassword" to releaseSetting("SEION_RELEASE_KEY_PASSWORD", "keyPassword")
    )
val releaseStoreFile = releaseSigning["storeFile"]?.let { rootProject.file(it) }
val releaseSigned: Boolean = releaseSigning.values.none { it == null } && releaseStoreFile!!.isFile
val releaseSigningNote: String? =
    when {
        releaseSigning.values.any { it == null } -> "release signing is not configured (keystore.properties or SEION_RELEASE_*)"
        !releaseStoreFile!!.isFile -> "release keystore not found: $releaseStoreFile"
        else -> null
    }
// A release build never falls back to the debug key: any release packaging task fails without the real one.
tasks.configureEach {
    if (releaseSigningNote != null && (name == "assembleRelease" || name == "packageRelease" || name == "bundleRelease")) {
        val note = releaseSigningNote
        doFirst { throw GradleException("$note: refusing to build a release APK without the release key") }
    }
}

// The FFmpeg audio decoder (GPL-3.0) is bundled unless the build runs with -Pseion.ffmpeg=false.
val withFfmpeg = providers.gradleProperty("seion.ffmpeg").orNull != "false"

// Where Seion's source lives: the "Source code" link in Settings and on the licences screen, and the contact in the
// User-Agent of the MusicBrainz / Cover Art Archive lookups ("Seion/<version> ( <url> )"). Change it here, or build
// with -Pseion.projectUrl=https://...
val defaultProjectUrl = "https://github.com/de1987/seion"
val projectUrl: String =
    providers.gradleProperty("seion.projectUrl").orNull?.trim()?.takeIf(String::isNotEmpty) ?: defaultProjectUrl
require(Regex("""https://[^\s"\\]+""").matches(projectUrl)) { "seion.projectUrl must be an https:// URL: $projectUrl" }

room {
    schemaDirectory("$projectDir/schemas")
}

android {
    namespace = "org.seion"
    compileSdk = 37
    defaultConfig {
        applicationId = "io.github.de1987.seion"
        minSdk = 31
        targetSdk = 37
        versionCode = 20
        versionName = "1.5.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("boolean", "HAS_FFMPEG", withFfmpeg.toString())
        buildConfigField("String", "FFMPEG_DECODER_VERSION", "\"${libs.versions.ffmpegDecoder.get()}\"")
        buildConfigField("String", "PROJECT_URL", "\"$projectUrl\"")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        buildConfig = true
        compose = true
    }
    androidResources {
        // English + Brazilian Portuguese only; drops the libraries' other translations.
        localeFilters += listOf("en", "pt-rBR")
    }
    testOptions.unitTests.isIncludeAndroidResources = true
    // Opt-in DSP tools: -Pseion.bench=true runs the full JVM benchmark; -Pseion.render.in=<dir> (and
    // -Pseion.render.out=<dir>) renders every WAV file in <dir> through each effect setting for blind A/B listening.
    val dspProps = listOf("seion.bench", "seion.render.in", "seion.render.out").associateWith { providers.gradleProperty(it).orNull }
    testOptions.unitTests.all { test ->
        dspProps.forEach { (k, v) -> if (v != null) test.systemProperty(k, v) }
        test.maxHeapSize = "2g"
    }
    // android.util.Log and friends return defaults in JVM unit tests (the art/scan code logs).
    testOptions.unitTests.isReturnDefaultValues = true
    lint {
        abortOnError = true
        warningsAsErrors = false
        checkDependencies = true
        lintConfig = rootProject.file("config/lint/lint.xml")
    }
    signingConfigs {
        if (releaseSigned) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = releaseSigning["storePassword"]
                keyAlias = releaseSigning["keyAlias"]
                keyPassword = releaseSigning["keyPassword"]
                if (releaseStoreFile!!.extension.lowercase() in setOf("p12", "pfx")) storeType = "pkcs12"
                // APK Signature Scheme v3: updates keep working across a future key rotation.
                enableV3Signing = true
            }
        }
    }
    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Only the release key; without it the release tasks fail (above), never a debug-signed release.
            signingConfig = signingConfigs.findByName("release")
        }
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.animation.graphics)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.palette)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.guava)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.common)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.work.runtime.ktx)
    implementation(libs.datastore.preferences)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    // FFmpeg audio decoder for formats without a platform decoder (ALAC). The prebuilt artifact is GPL-3.0:
    // `-Pseion.ffmpeg=false` leaves it out; ALAC files are then skipped with a message. Loaded by reflection only.
    if (withFfmpeg) implementation(libs.media3.ffmpeg.decoder)

    testImplementation(libs.junit)
    // Compose layout checks under Robolectric (e.g. a pill's text must never be cut off).
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.mockwebserver3)
    // Unique background work (e.g. "Find missing covers again" never stacks) under the test WorkManager.
    testImplementation(libs.work.testing)
}
