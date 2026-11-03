pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        google()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        google()
        maven("https://central.sonatype.com/repository/maven-snapshots/") {
            name = "GraphiksSnapshots"
            content { includeGroup("org.graphiks") }
        }
    }
}

rootProject.name = "hello-dawn4k"

include(":androidApp")
