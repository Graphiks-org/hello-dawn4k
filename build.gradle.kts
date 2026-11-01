plugins {
    kotlin("jvm") version "2.4.20"
    application
}

kotlin {
    jvmToolchain(25)
}

application {
    mainClass.set("org.graphiks.hellodawn4k.MainKt")
    // kffi drives Dawn through the Java FFM API, which requires an explicit native-access grant.
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

dependencies {
    implementation("org.graphiks:dawn4k-toolkit-core:1.0.0-SNAPSHOT")
    implementation("org.graphiks:dawn4k-toolkit-glfw:1.0.0-SNAPSHOT")
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
}
