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
        // The Dawn-backed mobile hosts (Android + iOS) share the render loop here.
        // It cannot live in commonMain: the web target has no dawn4k dependency.
        val mobileMain by creating { dependsOn(commonMain) }
        androidMain.dependsOn(mobileMain)
        iosArm64Main.dependsOn(mobileMain)
        iosSimulatorArm64Main.dependsOn(mobileMain)
        mobileMain.dependencies {
            implementation("org.graphiks:dawn4k-toolkit-core:1.0.0-SNAPSHOT")
            implementation("org.graphiks:dawn4k-toolkit-surface:1.0.0-SNAPSHOT")
        }
        val wasmJsMain by getting {
            dependencies {
                // The browser's native WebGPU behind the same GPUDevice/GPUQueue interfaces.
                implementation("org.graphiks:webgpu-browser:0.1.0-SNAPSHOT")
                implementation("org.jetbrains.kotlinx:kotlinx-browser:0.3")
            }
        }
        // The shared glyph chain (Kalligraphie shaping/layout/rasterization) lives here.
        // Temporary Android quarantine: the new org.graphiks.kalligraphie coordinates give
        // every module a unique AGP namespace except raster-cpu, whose hyphenated
        // namespace ('org.graphiks.kalligraphie.raster-cpu') is not a valid Java package
        // and AGP refuses its AAR transform. Android keeps its native status text until
        // that one module is republished; then this collapses into commonMain and the
        // Android host switches to GlyphOverlay.
        val glyphMain by creating { dependsOn(commonMain) }
        jvmMain.dependsOn(glyphMain)
        iosArm64Main.dependsOn(glyphMain)
        iosSimulatorArm64Main.dependsOn(glyphMain)
        wasmJsMain.dependsOn(glyphMain)
        glyphMain.dependencies {
            api("org.graphiks.kalligraphie:kalligraphie:1.0.0-SNAPSHOT")
            // The kalligraphie umbrella POM declares the leaves runtime-only; consumers redeclare them.
            implementation("org.graphiks.kalligraphie:api:1.0.0-SNAPSHOT")
            implementation("org.graphiks.kalligraphie:shaping:1.0.0-SNAPSHOT")
            implementation("org.graphiks.kalligraphie:layout:1.0.0-SNAPSHOT")
            implementation("org.graphiks.kalligraphie:raster-cpu:1.0.0-SNAPSHOT")
        }
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test"))
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
}

// Kalligraphie's wasm-js HarfBuzz bridge: the klib ships its JS loaders
// (kffi-harfbuzz-web.mjs, hb.mjs) at the klib root, but the Kotlin webpack packaging
// does not extract klib-root .mjs files, so the bundle cannot resolve them. Extract
// them next to the generated package before webpack runs (both variants).
val kffiHarfbuzzKlib = configurations.detachedConfiguration(
    dependencies.create("org.graphiks:kffi-harfbuzz-wasm-js:1.0.0-SNAPSHOT"),
)
val extractKffiHarfbuzzGlue by tasks.registering(Copy::class) {
    val klibFile = provider { kffiHarfbuzzKlib.files.first { it.name.endsWith(".klib") } }
    from(zipTree(klibFile))
    include("*.mjs")
    into(layout.buildDirectory.dir("wasm/packages/${project.name}/kotlin"))
}
listOf("wasmJsBrowserDevelopmentWebpack", "wasmJsBrowserProductionWebpack").forEach { name ->
    tasks.matching { it.name == name }.configureEach { dependsOn(extractKffiHarfbuzzGlue) }
}
