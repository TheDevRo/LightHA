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
    ndkVersion = "29.0.14206865" // wake word engine (src/main/cpp)

    defaultConfig {
        applicationId = "home.kiosk"
        minSdk = 21
        targetSdk = 34
        versionCode = 4
        versionName = "0.4.0"
        // Release builds ship with no server or token: first launch opens Settings.
        buildConfigField("String", "HA_URL", "\"\"")
        buildConfigField("String", "HA_TOKEN", "\"\"")
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") } // the tablets; skips x86 emulator builds
    }

    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "4.1.2" }
    }

    signingConfigs {
        // Keystore path/password live in local.properties (gitignored); without them, release falls back to debug signing.
        local.getProperty("release.store")?.let { store ->
            create("release") {
                storeFile = file(store)
                storePassword = local.getProperty("release.password")
                keyAlias = "lightha"
                keyPassword = local.getProperty("release.password")
            }
        }
    }

    buildTypes {
        debug {
            // Dev convenience only: prefill your own server so a fresh install connects immediately.
            buildConfigField("String", "HA_URL", "\"${local.getProperty("ha.url", "")}\"")
            buildConfigField("String", "HA_TOKEN", "\"${local.getProperty("ha.token", "")}\"")
            signingConfigs.findByName("release")?.let { signingConfig = it } // same key as releases, so they install over each other
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
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
