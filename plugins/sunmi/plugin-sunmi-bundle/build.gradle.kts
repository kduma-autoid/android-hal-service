plugins {
    alias(libs.plugins.android.application)
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
        versionCode = 1
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
}
