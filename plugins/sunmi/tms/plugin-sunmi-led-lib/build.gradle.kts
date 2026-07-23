plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.duma.android.hal.plugins.sunmi.tms.led"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }
    defaultConfig {
        minSdk = 19
        consumerProguardFiles("consumer-rules.pro")
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(":service:hal-contract"))
    api(project(":plugins:sunmi:tms:plugin-sunmi-tms-sdk"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)
}
