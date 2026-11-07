import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Google Maps SDK key: MAPS_API_KEY in local.properties, a Gradle property, or the environment (CI secret).
// Blank means the in-app map falls back to OpenStreetMap.
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
// Server the app signs in to unless the user changes it (DEFAULT_SERVER_URL overrides, e.g. a CI variable).
val defaultServerUrl: String = (findProperty("DEFAULT_SERVER_URL") as String?) ?: System.getenv("DEFAULT_SERVER_URL")?.takeIf { it.isNotBlank() } ?: "https://barkadakaskas.on-forge.com"
// CI build number, so each published APK installs as an update over the previous one.
val buildNumber: Int = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
// Stable signing key for sideloaded releases (CI secrets). Android only updates an app signed with the same key.
val releaseKeystore: String? = System.getenv("ANDROID_KEYSTORE_FILE")?.takeIf { file(it).exists() }
val mapsApiKey: String = (findProperty("MAPS_API_KEY") as String?) ?: System.getenv("MAPS_API_KEY") ?: localProperties.getProperty("MAPS_API_KEY") ?: ""
android {
    namespace = "com.barkadakaskas.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.barkadakaskas.app"
        minSdk = 26
        targetSdk = 35
        versionCode = buildNumber
        versionName = "0.1.$buildNumber"
        manifestPlaceholders["mapsApiKey"] = mapsApiKey
        buildConfigField("String", "DEFAULT_SERVER_URL", "\"$defaultServerUrl\"")
        buildConfigField("String", "MAPS_API_KEY", "\"${mapsApiKey.replace("\"", "")}\"")
    }
    signingConfigs {
        if (releaseKeystore != null) create("sideload") {
            storeFile = file(releaseKeystore)
            storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("ANDROID_KEY_ALIAS")
            keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseKeystore != null) signingConfig = signingConfigs.getByName("sideload")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
}
// APK files are named BarkadaKasKas-<variant>.apk.
base { archivesName.set("BarkadaKasKas") }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.03.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    // OpenStreetMap tiles: an in-app trip map with no API key to provision.
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    // Google Maps in Compose (used when MAPS_API_KEY is set).
    implementation("com.google.maps.android:maps-compose:6.4.1")
    // Trip cover photos.
    implementation("io.coil-kt:coil-compose:2.7.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    // Android's org.json is a stub on the JVM; use the real library for unit tests.
    testImplementation("org.json:json:20240303")
}
