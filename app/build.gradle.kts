plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")

    // 🔥 Firebase
    id("com.google.gms.google-services")
}

val useFirestoreEmulator = providers.gradleProperty("useFirestoreEmulator")
    .orNull?.toBoolean() ?: false

android {
    namespace = "com.saimega.vinayakacablenetwork"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.saimega.vinayakacablenetwork"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        buildConfigField("boolean", "USE_FIRESTORE_EMULATOR", useFirestoreEmulator.toString())
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Debug-signed for direct sideload/testing. Not suitable for Play
            // Store — publishing there needs a dedicated release keystore.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}


dependencies {

    implementation("com.google.firebase:firebase-firestore-ktx:24.10.3")
    
    // DantSu ESC/POS Thermal Printer library
    implementation("com.github.DantSu:ESCPOS-ThermalPrinter-Android:3.3.0")

    implementation("com.google.android.material:material:1.11.0")

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")

    implementation("androidx.cardview:cardview:1.0.0")
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)

    // ── ViewModel + LiveData ──────────────────────────────────────────────────
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    // ── Kotlin Coroutines (for suspend functions in Repository) ───────────────
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")

    // ── RecyclerView ─────────────────────────────────────────────────────────
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    testImplementation(libs.junit)
}