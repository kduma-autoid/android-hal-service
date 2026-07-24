plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "dev.duma.android.hal.plugins.sunmi.printerx.bundle"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "dev.duma.android.hal.plugins.sunmi.printerx"
        minSdk = 24
        targetSdk = 36
        versionCode = (project.properties["projectVersionCode"] as String? ?: "1").toInt()
        versionName = project.properties["projectVersion"] as String? ?: "0.0.0"

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

    flavorDimensions += "stability"
    productFlavors {
        create("development") {
            dimension = "stability"
        }
        create("stable") {
            dimension = "stability"
        }
    }
}

dependencies {
    implementation(project(":service:hal-contract"))
    implementation(project(":plugins:sunmi:printerx:plugin-sunmi-manager-lib"))
    implementation(project(":plugins:sunmi:printerx:plugin-sunmi-printer-lib"))
    implementation(project(":plugins:sunmi:printerx:plugin-sunmi-drawer-lib"))
    implementation(project(":plugins:sunmi:printerx:plugin-sunmi-lcd-lib"))
}
