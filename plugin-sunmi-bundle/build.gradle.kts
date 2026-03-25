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
        versionName = "1.0"

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
    implementation(project(":hal-contract"))
    implementation(project(":plugin-sunmi-printer-lib"))
    implementation(project(":plugin-sunmi-scanner-lib"))
}
