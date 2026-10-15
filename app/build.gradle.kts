import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// ha.url and ha.token live in local.properties (gitignored) until there's a settings screen.
val local = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}

android {
    namespace = "home.kiosk"
    compileSdk = 34

    defaultConfig {
        applicationId = "home.kiosk"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
        buildConfigField("String", "HA_URL", "\"${local.getProperty("ha.url", "http://192.168.1.51:8123")}\"")
        buildConfigField("String", "HA_TOKEN", "\"${local.getProperty("ha.token", "")}\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.5")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.zxing:core:3.5.3") // QR code for "edit on your phone"

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303") // real org.json; Android's is a stub in unit tests
}
