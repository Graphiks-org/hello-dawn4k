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
                api(libs.webgpu.api)
                // dawn4k exposes webgpu-descriptors only as an implementation dependency, so consumers redeclare it.
                api(libs.webgpu.descriptors)
                implementation(libs.kotlinx.coroutines.core)
                // The glyph chain (shaping, layout, CPU rasterization) is common to every
                // host: Kalligraphie publishes JVM, Android, iOS and wasm-js variants under
                // org.graphiks.kalligraphie, one valid AGP namespace per module. The umbrella
                // POM declares the leaves runtime-only, so consumers redeclare them.
                api(libs.kalligraphie)
                implementation(libs.kalligraphie.api)
                implementation(libs.kalligraphie.shaping)
                implementation(libs.kalligraphie.layout)
                implementation(libs.kalligraphie.raster.cpu)
            }
        }
        val jvmMain by getting {
            dependencies {
                // One toolkit module (org.graphiks.webgpu:dawn4k-webgpu-toolkit) replaces the
                // three legacy dawn4k-toolkit-core/surface/glfw modules, folded into it.
                implementation(libs.dawn4k.webgpu.toolkit)
            }
        }
        val androidMain by getting {
            dependencies {
                implementation(libs.dawn4k.webgpu.toolkit)
            }
        }
        val iosArm64Main by getting {
            dependencies {
                implementation(libs.dawn4k.webgpu.toolkit)
            }
        }
        val iosSimulatorArm64Main by getting {
            dependencies {
                implementation(libs.dawn4k.webgpu.toolkit)
            }
        }
        // The Dawn-backed mobile hosts (Android + iOS) share the render loop here.
        // It cannot live in commonMain: the web target has no dawn4k dependency.
        val mobileMain by creating { dependsOn(commonMain) }
        androidMain.dependsOn(mobileMain)
        iosArm64Main.dependsOn(mobileMain)
        iosSimulatorArm64Main.dependsOn(mobileMain)
        mobileMain.dependencies {
            implementation(libs.dawn4k.webgpu.toolkit)
        }
        // The two iOS targets share the UIKit host controller in iosMain, over the
        // shared mobile loop. The default hierarchy template is off (explicit edges
        // above), so the source set must be created and attached by hand — otherwise
        // src/iosMain compiles into nothing and the Xcode app cannot find
        // CornellViewControllerKt. mobileMain already carries the dawn4k dependencies.
        val iosMain by creating { dependsOn(mobileMain) }
        iosArm64Main.dependsOn(iosMain)
        iosSimulatorArm64Main.dependsOn(iosMain)
        val wasmJsMain by getting {
            dependencies {
                // The browser's native WebGPU behind the same GPUDevice/GPUQueue interfaces.
                implementation(libs.webgpu.browser)
                // Toolkit session/surface over the browser implementation: webToolkit(),
                // CanvasSurfaceTarget (toolkit-web), withSurface/PresentationPolicy/Frame
                // (toolkit-surface), AdapterHints/SessionConfig (toolkit-core, redeclared —
                // the toolkit modules expose them only as implementation dependencies).
                implementation(libs.webgpu.toolkit.web)
                implementation(libs.webgpu.toolkit.surface)
                implementation(libs.webgpu.toolkit.core)
                implementation(libs.kotlinx.browser)
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test"))
                // DawnToolkitInterop.withDawnSession takes the toolkit's SessionConfig, published
                // by toolkit-core; dawn4k-webgpu-toolkit exposes it only as an implementation
                // dependency, so the consumer redeclares it (like dawn4k-toolkit-examples does).
                implementation(libs.webgpu.toolkit.core)
            }
        }
        val iosSimulatorArm64Test by getting {
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
    dependencies.create(libs.kffi.harfbuzz.wasm.js.get()),
)
val extractKffiHarfbuzzGlue by tasks.registering(Copy::class) {
    val klibFile = provider { kffiHarfbuzzKlib.files.first { it.name.endsWith(".klib") } }
    from(zipTree(klibFile))
    include("*.mjs")
    into(layout.buildDirectory.dir("wasm/packages/${project.name}/kotlin"))
}
// The Kotlin compile regenerates the package directory the glue is copied into — run the
// copy after it, or a recompile wipes the .mjs files out from under webpack (flake seen).
extractKffiHarfbuzzGlue.configure { dependsOn(tasks.named("compileKotlinWasmJs")) }
listOf("wasmJsBrowserDevelopmentWebpack", "wasmJsBrowserProductionWebpack").forEach { name ->
    tasks.matching { it.name == name }.configureEach { dependsOn(extractKffiHarfbuzzGlue) }
}
