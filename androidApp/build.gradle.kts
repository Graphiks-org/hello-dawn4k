plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "org.graphiks.hellodawn4k.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "org.graphiks.hellodawn4k.app"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
}
