rootProject.name = "tactical"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/") { name = "papermc" }
        maven("https://repo.codemc.io/repository/maven-releases/") { name = "codemc" }
        maven("https://maven.maxhenkel.de/repository/public/") { name = "maxhenkel" }
    }
}

include("tactical-engine", "tactical-paper")
