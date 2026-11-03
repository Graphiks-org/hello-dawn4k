plugins {
    kotlin("jvm") version "2.4.20"
}

kotlin {
    jvmToolchain(25)
}

// runWindow is the project's entry point (GLFW needs the process main thread on macOS).

dependencies {
    implementation("org.graphiks:dawn4k-toolkit-core:1.0.0-SNAPSHOT")
    implementation("org.graphiks:dawn4k-toolkit-glfw:1.0.0-SNAPSHOT")
    implementation("org.graphiks:kalligraphie:1.0.0-SNAPSHOT")
    // The kalligraphie umbrella POM declares these runtime-only; the HUD needs them at compile time.
    implementation("org.graphiks:api:1.0.0-SNAPSHOT")
    implementation("org.graphiks:shaping:1.0.0-SNAPSHOT")
    implementation("org.graphiks:layout:1.0.0-SNAPSHOT")
    implementation("org.graphiks:raster-cpu:1.0.0-SNAPSHOT")
    // dawn4k exposes webgpu-descriptors only as an implementation dependency, so consumers redeclare it.
    implementation("org.graphiks:webgpu-descriptors:0.1.0-SNAPSHOT")
}

// `run` stays headless; runWindow opens the GLFW desktop window (needs the process main
// thread on macOS, exactly like the toolkit's own desktop launchers).
tasks.register<JavaExec>("runWindow") {
    group = "application"
    description = "Opens a desktop window with the live-converging Cornell box."
    mainClass.set("org.graphiks.hellodawn4k.WindowMainKt")
    classpath = sourceSets["main"].runtimeClasspath
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
    classpath = sourceSets["main"].runtimeClasspath
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
    args = (providers.gradleProperty("noise.a").orNull?.let { listOf(it) } ?: emptyList()) +
        (providers.gradleProperty("noise.b").orNull?.let { listOf(it) } ?: emptyList())
}
