plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.gms.google-services")
}

val callioServerUrl = (project.findProperty("CALLIO_SERVER_URL") as String?) ?: "https://YOUR-CALLIO-VERCEL-DOMAIN.vercel.app"
val turnUrl = (project.findProperty("CALLIO_TURN_URL") as String?) ?: ""
val turnUsername = (project.findProperty("CALLIO_TURN_USERNAME") as String?) ?: ""
val updateUrl = (project.findProperty("CALLIO_UPDATE_URL") as String?) ?: ""
val appVersionCode = (project.findProperty("APP_VERSION_CODE") as String?)?.toIntOrNull() ?: 1
val appVersionName = (project.findProperty("APP_VERSION_NAME") as String?) ?: "1.0.0"
val keystorePath: String? = System.getenv("CALLIO_KEYSTORE_PATH")?.takeIf { it.isNotBlank() && file(it).exists() }
val turnCredential = (project.findProperty("CALLIO_TURN_CREDENTIAL") as String?) ?: ""

android {
    namespace = "com.callio.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.callio.app"
        minSdk = 31
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        buildConfigField("String", "CALLIO_SERVER_URL", "\"$callioServerUrl\"")
        buildConfigField("String", "CALLIO_TURN_URL", "\"$turnUrl\"")
        buildConfigField("String", "CALLIO_TURN_USERNAME", "\"$turnUsername\"")
        buildConfigField("String", "CALLIO_TURN_CREDENTIAL", "\"$turnCredential\"")
        buildConfigField("String", "CALLIO_UPDATE_URL", "\"$updateUrl\"")
    }

    // One stable signing key is REQUIRED for in-app updates: Android refuses to update an app
    // with an APK signed by a different key. The key is decrypted from the repo in CI (see RELEASE-SETUP-HI.md).
    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("CALLIO_KEYSTORE_PASSWORD")
                keyAlias = "callio"
                keyPassword = System.getenv("CALLIO_KEYSTORE_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystorePath != null) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            if (keystorePath != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")

    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-auth-ktx")
    implementation("com.google.firebase:firebase-firestore-ktx")
    implementation("com.google.firebase:firebase-messaging-ktx")

    implementation("io.github.webrtc-sdk:android:150.7871.04")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
