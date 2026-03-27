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

include(":service:hal-contract")
include(":service:hal-service")

include(":service:transport:transport-core")
include(":service:transport:transport-ktor-core")
include(":service:transport:transport-aidl")
include(":service:transport:transport-ws")
include(":service:transport:transport-http")
include(":service:transport:transport-intent")
include(":service:transport:transport-broadcast")

include(":plugins:generic:plugin-generic-lib")

include(":plugins:sunmi:plugin-sunmi-bundle")
include(":plugins:sunmi:plugin-sunmi-printer-lib")
include(":plugins:sunmi:plugin-sunmi-scanner-lib")
