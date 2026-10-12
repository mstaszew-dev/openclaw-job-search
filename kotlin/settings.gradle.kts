pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "campaign-kotlin"

include(
    "common",
    "finder-service",
    "apply-worker",
    "campaign-api",
    "import-tracker",
)

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
