plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Google Maps SDK key: MAPS_API_KEY in local.properties, a Gradle property, or the environment (CI secret).
// Blank means the in-app map falls back to OpenStreetMap.
val localProperties = java.util.Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val mapsApiKey: String = (findProperty("MAPS_API_KEY") as String?) ?: System.getenv("MAPS_API_KEY") ?: localProperties.getProperty("MAPS_API_KEY") ?: ""
android {
    namespace = "com.barkadakaskas.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.barkadakaskas.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        manifestPlaceholders["mapsApiKey"] = mapsApiKey
        buildConfigField("String", "MAPS_API_KEY", "\"${mapsApiKey.replace("\"", "")}\"")
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
}
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
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    // Android's org.json is a stub on the JVM; use the real library for unit tests.
    testImplementation("org.json:json:20240303")
}
