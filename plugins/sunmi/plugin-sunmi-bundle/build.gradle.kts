plugins {
    alias(libs.plugins.android.application)
    id("maven-publish")
}

android {
    namespace = "dev.duma.android.hal.plugins.sunmi.bundle"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "dev.duma.android.hal.plugins.sunmi"
        minSdk = 24
        targetSdk = 36
        versionCode = 2
        versionName = "0.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(":service:hal-contract"))
    implementation(project(":plugins:sunmi:plugin-sunmi-printer-lib"))
    implementation(project(":plugins:sunmi:plugin-sunmi-scanner-lib"))
    implementation(project(":plugins:sunmi:sunmiperipher:plugin-sunmi-statuslight-lib"))
    implementation(project(":plugins:sunmi:sunmiperipher:plugin-sunmi-nfc-lib"))
    implementation(project(":plugins:sunmi:sunmiperipher:plugin-sunmi-card-lib"))
    implementation(project(":plugins:sunmi:sunmiperipher:plugin-sunmi-screen-lib"))
    implementation(project(":plugins:sunmi:sunmiperipher:plugin-sunmi-docker-lib"))
    implementation(project(":plugins:sunmi:tms:plugin-sunmi-device-lib"))
    implementation(project(":plugins:sunmi:tms:plugin-sunmi-software-lib"))
    implementation(project(":plugins:sunmi:tms:plugin-sunmi-system-lib"))
    implementation(project(":plugins:sunmi:tms:plugin-sunmi-network-lib"))
    implementation(project(":plugins:sunmi:tms:plugin-sunmi-kiosk-lib"))
    implementation(project(":plugins:sunmi:printerx:plugin-sunmi-manager-lib"))
    implementation(project(":plugins:sunmi:printerx:plugin-sunmi-printer-lib"))
    implementation(project(":plugins:sunmi:printerx:plugin-sunmi-drawer-lib"))
    implementation(project(":plugins:sunmi:printerx:plugin-sunmi-lcd-lib"))
    implementation(project(":plugins:sunmi:sunmiscannersdk:plugin-sunmi-rfid-lib"))
    implementation(project(":plugins:sunmi:sunmiscannersdk:plugin-sunmi-scanner-inner-lib"))
    implementation(project(":plugins:sunmi:sunmiscannersdk:plugin-sunmi-scanner-camera-lib"))
    implementation(project(":plugins:sunmi:sunmiscannersdk:plugin-sunmi-scanner-external-lib"))
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                groupId = "dev.duma.android.hal"
                artifactId = project.name
                version = android.defaultConfig.versionName!!
                artifact(layout.buildDirectory.file("outputs/apk/release/${project.name}-release.apk").get()) {
                    extension = "apk"
                    builtBy(tasks.named("assembleRelease"))
                }
            }
        }
        repositories {
            maven {
                name = "GitHubPackages"
                url = uri("https://maven.pkg.github.com/kduma-autoid/android-hal-service")
                credentials {
                    username = System.getenv("GITHUB_ACTOR") ?: ""
                    password = System.getenv("GITHUB_TOKEN") ?: ""
                }
            }
        }
    }
}
