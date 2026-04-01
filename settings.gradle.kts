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
        maven {
            url = uri("https://maven.pkg.github.com/kduma-autoid/android-hal-service")
            credentials {
                username = System.getenv("GITHUB_ACTOR") ?: ""
                password = System.getenv("GITHUB_TOKEN") ?: ""
            }
        }
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

include(":plugins:sunmi:sunmiperipher:plugin-sunmi-sunmiperipher-sdk")
include(":plugins:sunmi:sunmiperipher:plugin-sunmi-statuslight-lib")
include(":plugins:sunmi:sunmiperipher:plugin-sunmi-nfc-lib")
include(":plugins:sunmi:sunmiperipher:plugin-sunmi-card-lib")
include(":plugins:sunmi:sunmiperipher:plugin-sunmi-screen-lib")
include(":plugins:sunmi:sunmiperipher:plugin-sunmi-docker-lib")
include(":plugins:sunmi:sunmiperipher:plugin-sunmi-sunmiperipher-bundle")

include(":plugins:sunmi:tms:plugin-sunmi-tms-sdk")
include(":plugins:sunmi:tms:plugin-sunmi-device-lib")
include(":plugins:sunmi:tms:plugin-sunmi-software-lib")
include(":plugins:sunmi:tms:plugin-sunmi-system-lib")
include(":plugins:sunmi:tms:plugin-sunmi-network-lib")
include(":plugins:sunmi:tms:plugin-sunmi-kiosk-lib")
include(":plugins:sunmi:tms:plugin-sunmi-tms-bundle")

include(":plugins:sunmi:sunmiscannersdk:plugin-sunmi-sunmiscannersdk-sdk")
include(":plugins:sunmi:sunmiscannersdk:plugin-sunmi-rfid-lib")
include(":plugins:sunmi:sunmiscannersdk:plugin-sunmi-scanner-common-lib")
include(":plugins:sunmi:sunmiscannersdk:plugin-sunmi-scanner-inner-lib")
include(":plugins:sunmi:sunmiscannersdk:plugin-sunmi-scanner-camera-lib")
include(":plugins:sunmi:sunmiscannersdk:plugin-sunmi-scanner-external-lib")
include(":plugins:sunmi:sunmiscannersdk:plugin-sunmi-sunmiscannersdk-bundle")

include(":plugins:sunmi:printerx:plugin-sunmi-printerx-sdk")
include(":plugins:sunmi:printerx:plugin-sunmi-manager-lib")
include(":plugins:sunmi:printerx:plugin-sunmi-printer-lib")
include(":plugins:sunmi:printerx:plugin-sunmi-drawer-lib")
include(":plugins:sunmi:printerx:plugin-sunmi-lcd-lib")
include(":plugins:sunmi:printerx:plugin-sunmi-printerx-bundle")

include(":vendor")
