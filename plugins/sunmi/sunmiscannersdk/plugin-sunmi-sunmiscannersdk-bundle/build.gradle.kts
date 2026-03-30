plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "dev.duma.android.hal.plugins.sunmi.sunmiscannersdk.bundle"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "dev.duma.android.hal.plugins.sunmi.sunmiscannersdk"
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
    implementation(project(":plugins:sunmi:sunmiscannersdk:plugin-sunmi-rfid-lib"))
}
