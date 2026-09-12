import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Secrets live in local.properties (git-ignored) and surface as BuildConfig constants.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun prop(name: String, default: String) = localProps.getProperty(name) ?: default

// Documents is iCloud-synced and iCloud creates "name 2.xml" duplicates inside build output,
// which breaks resource merging. Keep generated files outside the synced tree.
layout.buildDirectory.set(File(System.getProperty("user.home"), "Library/Caches/hudlauncher-build/app"))

android {
    namespace = "com.abbas.hudlauncher"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.abbas.hudlauncher"
        minSdk = 28          // Rokid Glasses report API 32 (Android 12)
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"

        buildConfigField("String", "HUD_BASE_URL", "\"${prop("HUD_BASE_URL", "http://10.0.2.2:8000")}\"")
        buildConfigField("String", "HUD_API_KEY", "\"${prop("HUD_API_KEY", "change-me")}\"")
    }

    buildTypes {
        debug {
            // allows http://<mac-lan-ip>:8000 while developing against a local uvicorn
            manifestPlaceholders["cleartext"] = "true"
        }
        release {
            isMinifyEnabled = false
            manifestPlaceholders["cleartext"] = "false"
            signingConfig = signingConfigs.getByName("debug") // sideload only; replace for store builds
        }
    }

    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
