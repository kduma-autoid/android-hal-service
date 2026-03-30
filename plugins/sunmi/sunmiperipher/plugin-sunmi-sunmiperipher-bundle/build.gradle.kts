plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "dev.duma.android.hal.plugins.sunmi.sunmiperipher.bundle"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "dev.duma.android.hal.plugins.sunmi.sunmiperipher"
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
}

dependencies {
    implementation(project(":service:hal-contract"))
    implementation(project(":plugins:sunmi:sunmiperipher:plugin-sunmi-statuslight-lib"))
    implementation(project(":plugins:sunmi:sunmiperipher:plugin-sunmi-nfc-lib"))
    implementation(project(":plugins:sunmi:sunmiperipher:plugin-sunmi-card-lib"))
    implementation(project(":plugins:sunmi:sunmiperipher:plugin-sunmi-screen-lib"))
    implementation(project(":plugins:sunmi:sunmiperipher:plugin-sunmi-docker-lib"))
}
