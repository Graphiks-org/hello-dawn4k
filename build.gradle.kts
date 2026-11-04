import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    kotlin("multiplatform") version "2.4.20"
    kotlin("android") version "2.4.20" apply false
    id("com.android.application") version "9.0.0" apply false
    id("com.android.library") version "9.0.0"
}

kotlin {
    jvm()
    androidTarget {
        compilations.all {
            compilerOptions.configure { jvmTarget.set(JvmTarget.JVM_17) }
        }
    }
    // The web target: the same common renderer over the browser's native WebGPU.
    wasmJs {
        browser()
        binaries.executable()
    }
    // iOS device + simulator; the shared framework the Xcode app links.
    val iosArm64 = iosArm64()
    val iosSimulatorArm64 = iosSimulatorArm64()
    for (target in listOf(iosArm64, iosSimulatorArm64)) {
        (target as KotlinNativeTarget).binaries.framework {
            baseName = "CornellBox"
            isStatic = true
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                // dawn4k does not ship wasmJs: common code depends only on the portable
                // WebGPU contracts; the native targets add Dawn through their own source sets.
                api("org.graphiks:webgpu-api:0.1.0-SNAPSHOT")
                // dawn4k exposes webgpu-descriptors only as an implementation dependency, so consumers redeclare it.
                api("org.graphiks:webgpu-descriptors:0.1.0-SNAPSHOT")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
            }
        }
        val jvmMain by getting {
            dependencies {
                implementation("org.graphiks:dawn4k-toolkit-core:1.0.0-SNAPSHOT")
                implementation("org.graphiks:dawn4k-toolkit-surface:1.0.0-SNAPSHOT")
                implementation("org.graphiks:dawn4k-toolkit-glfw:1.0.0-SNAPSHOT")
                implementation("org.graphiks:kalligraphie:1.0.0-SNAPSHOT")
                // The kalligraphie umbrella POM declares these runtime-only; the HUD needs them at compile time.
                implementation("org.graphiks:api:1.0.0-SNAPSHOT")
                implementation("org.graphiks:shaping:1.0.0-SNAPSHOT")
                implementation("org.graphiks:layout:1.0.0-SNAPSHOT")
                implementation("org.graphiks:raster-cpu:1.0.0-SNAPSHOT")
            }
        }
        val androidMain by getting {
            dependencies {
                implementation("org.graphiks:dawn4k-toolkit-core:1.0.0-SNAPSHOT")
                implementation("org.graphiks:dawn4k-toolkit-surface:1.0.0-SNAPSHOT")
            }
        }
        val iosArm64Main by getting {
            dependencies {
                implementation("org.graphiks:dawn4k-toolkit-core:1.0.0-SNAPSHOT")
                implementation("org.graphiks:dawn4k-toolkit-surface:1.0.0-SNAPSHOT")
            }
        }
        val iosSimulatorArm64Main by getting {
            dependencies {
                implementation("org.graphiks:dawn4k-toolkit-core:1.0.0-SNAPSHOT")
                implementation("org.graphiks:dawn4k-toolkit-surface:1.0.0-SNAPSHOT")
            }
        }
        val wasmJsMain by getting {
            dependencies {
                // The browser's native WebGPU behind the same GPUDevice/GPUQueue interfaces.
                implementation("org.graphiks:webgpu-browser:0.1.0-SNAPSHOT")
                implementation("org.jetbrains.kotlinx:kotlinx-browser:0.3")
            }
        }
    }
}

android {
    namespace = "org.graphiks.hellodawn4k"
    compileSdk = 37
    defaultConfig { minSdk = 28 }
}

// runWindow is the desktop entry point (GLFW needs the process main thread on macOS).
tasks.register<JavaExec>("runWindow") {
    group = "application"
    description = "Opens the desktop window with the Cornell box photon lightmap."
    mainClass.set("org.graphiks.hellodawn4k.WindowMainKt")
    classpath = kotlin.targets["jvm"].compilations["main"].output.allOutputs + configurations["jvmRuntimeClasspath"]
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    if (System.getProperty("os.name").lowercase().contains("mac")) {
        jvmArgs("-XstartOnFirstThread")
    }
    // Finite smoke run: ./gradlew runWindow -Pcornell.frames=60
    providers.gradleProperty("cornell.frames").orNull?.let { systemProperty("cornell.frames", it) }
    // Optional orbit override: -Pcornell.orbit=azimuth,elevation,radius (radians)
    providers.gradleProperty("cornell.orbit").orNull?.let { systemProperty("cornell.orbit", it) }
    providers.gradleProperty("cornell.hud").orNull?.let { systemProperty("cornell.hud", it) }
    providers.gradleProperty("cornell.mode").orNull?.let { systemProperty("cornell.mode", it) }
}

// Noise comparison between two framebuffer dumps: ./gradlew noiseMetric a.png b.png
tasks.register<JavaExec>("noiseMetric") {
    group = "verification"
    description = "Mean 3x3 luma standard deviation over the back wall of two dumps."
    mainClass.set("org.graphiks.hellodawn4k.NoiseMetricKt")
    classpath = kotlin.targets["jvm"].compilations["main"].output.allOutputs + configurations["jvmRuntimeClasspath"]
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
    args = (providers.gradleProperty("noise.a").orNull?.let { listOf(it) } ?: emptyList()) +
        (providers.gradleProperty("noise.b").orNull?.let { listOf(it) } ?: emptyList())
}
