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
    // dawn4k exposes webgpu-descriptors only as an implementation dependency, so consumers redeclare it.
    implementation("org.graphiks:webgpu-descriptors:0.1.0-SNAPSHOT")
}
