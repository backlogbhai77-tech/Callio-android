plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.gms.google-services")
}

val callioServerUrl = (project.findProperty("CALLIO_SERVER_URL") as String?) ?: "https://YOUR-CALLIO-VERCEL-DOMAIN.vercel.app"
val turnUrl = (project.findProperty("CALLIO_TURN_URL") as String?) ?: ""
val turnUsername = (project.findProperty("CALLIO_TURN_USERNAME") as String?) ?: ""
val turnCredential = (project.findProperty("CALLIO_TURN_CREDENTIAL") as String?) ?: ""

android {
    namespace = "com.callio.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.callio.app"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        buildConfigField("String", "CALLIO_SERVER_URL", "\"$callioServerUrl\"")
        buildConfigField("String", "CALLIO_TURN_URL", "\"$turnUrl\"")
        buildConfigField("String", "CALLIO_TURN_USERNAME", "\"$turnUsername\"")
        buildConfigField("String", "CALLIO_TURN_CREDENTIAL", "\"$turnCredential\"")
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
