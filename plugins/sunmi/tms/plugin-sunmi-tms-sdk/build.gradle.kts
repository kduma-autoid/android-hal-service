plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.duma.android.hal.plugins.sunmi.tms.sdk"
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
    api(group = "", name = "SUNMI_CUSTOMER_API_v1.3.33_release", ext = "aar")
    api(project(":service:hal-contract"))
    implementation(libs.kotlinx.coroutines.core)
}
