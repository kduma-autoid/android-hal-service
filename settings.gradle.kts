pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "HAL Service"
include(":hal-contract")
include(":transport-core")
include(":transport-ktor-core")
include(":transport-aidl")
include(":transport-ws")
include(":transport-http")
include(":transport-intent")
include(":transport-broadcast")
include(":plugin-generic-lib")
include(":plugin-sunmi-printer-lib")
include(":plugin-sunmi-scanner-lib")
include(":plugin-sunmi-bundle")
include(":hal-service")
