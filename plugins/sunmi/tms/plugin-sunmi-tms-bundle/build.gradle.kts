plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "dev.duma.android.hal.plugins.sunmi.tms.bundle"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "dev.duma.android.hal.plugins.sunmi.tms"
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
    implementation(project(":plugins:sunmi:tms:plugin-sunmi-device-lib"))
    implementation(project(":plugins:sunmi:tms:plugin-sunmi-software-lib"))
    implementation(project(":plugins:sunmi:tms:plugin-sunmi-system-lib"))
    implementation(project(":plugins:sunmi:tms:plugin-sunmi-network-lib"))
    implementation(project(":plugins:sunmi:tms:plugin-sunmi-kiosk-lib"))
}
