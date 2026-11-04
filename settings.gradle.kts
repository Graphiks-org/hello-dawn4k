pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        google()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        mavenCentral()
        google()
        ivy("https://nodejs.org/dist") {
            name = "Distributions"
            patternLayout {
                artifact("v[revision]/[artifact](-v[revision]-[classifier]).[ext]")
            }
            metadataSources { artifact() }
            content { includeModule("org.nodejs", "node") }
        }
        ivy("https://github.com/yarnpkg/yarn/releases/download") {
            name = "Yarn"
            patternLayout {
                artifact("v[revision]/[artifact]-v[revision].tar.gz")
            }
            metadataSources { artifact() }
            content { includeModule("com.yarnpkg", "yarn") }
        }
        maven("https://central.sonatype.com/repository/maven-snapshots/") {
            name = "GraphiksSnapshots"
            content { includeGroup("org.graphiks") }
        }
    }
}

rootProject.name = "hello-dawn4k"

include(":androidApp")
