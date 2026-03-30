plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "dev.duma.android.hal.service"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "dev.duma.android.hal.service"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        aidl = true
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

    flavorDimensions += "device"
    productFlavors {
        create("generic") {
            dimension = "device"
        }
        create("sunmi") {
            dimension = "device"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    packaging {
        resources {
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/io.netty.versions.properties"
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    // Project modules - core
    implementation(project(":service:hal-contract"))
    implementation(project(":service:transport:transport-core"))
    implementation(project(":service:transport:transport-ktor-core"))

    // Project modules - transports
    implementation(project(":service:transport:transport-aidl"))
    implementation(project(":service:transport:transport-ws"))
    implementation(project(":service:transport:transport-http"))
    implementation(project(":service:transport:transport-intent"))
    implementation(project(":service:transport:transport-broadcast"))

    // Project modules - plugins
    implementation(project(":plugins:generic:plugin-generic-lib"))
    "sunmiImplementation"(project(":plugins:sunmi:plugin-sunmi-printer-lib"))
    "sunmiImplementation"(project(":plugins:sunmi:plugin-sunmi-scanner-lib"))
    "sunmiImplementation"(project(":plugins:sunmi:sunmiperipher:plugin-sunmi-statuslight-lib"))
    "sunmiImplementation"(project(":plugins:sunmi:sunmiperipher:plugin-sunmi-nfc-lib"))
    "sunmiImplementation"(project(":plugins:sunmi:sunmiperipher:plugin-sunmi-card-lib"))
    "sunmiImplementation"(project(":plugins:sunmi:sunmiperipher:plugin-sunmi-screen-lib"))
    "sunmiImplementation"(project(":plugins:sunmi:sunmiperipher:plugin-sunmi-docker-lib"))
    "sunmiImplementation"(project(":plugins:sunmi:tms:plugin-sunmi-device-lib"))
    "sunmiImplementation"(project(":plugins:sunmi:tms:plugin-sunmi-software-lib"))
    "sunmiImplementation"(project(":plugins:sunmi:tms:plugin-sunmi-system-lib"))
    "sunmiImplementation"(project(":plugins:sunmi:tms:plugin-sunmi-network-lib"))
    "sunmiImplementation"(project(":plugins:sunmi:tms:plugin-sunmi-kiosk-lib"))
    "sunmiImplementation"(project(":plugins:sunmi:sunmiscannersdk:plugin-sunmi-rfid-lib"))
    "sunmiImplementation"(project(":plugins:sunmi:sunmiscannersdk:plugin-sunmi-scanner-inner-lib"))
    "sunmiImplementation"(project(":plugins:sunmi:sunmiscannersdk:plugin-sunmi-scanner-camera-lib"))
    "sunmiImplementation"(project(":plugins:sunmi:sunmiscannersdk:plugin-sunmi-scanner-external-lib"))
    "sunmiImplementation"(project(":plugins:sunmi:printerx:plugin-sunmi-manager-lib"))
    "sunmiImplementation"(project(":plugins:sunmi:printerx:plugin-sunmi-printer-lib"))
    "sunmiImplementation"(project(":plugins:sunmi:printerx:plugin-sunmi-drawer-lib"))
    "sunmiImplementation"(project(":plugins:sunmi:printerx:plugin-sunmi-lcd-lib"))

    // AndroidX
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.swiperefreshlayout)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Kotlinx Serialization
    implementation(libs.kotlinx.serialization.json)

    // Kotlinx Coroutines
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // Nimbus JOSE+JWT
    implementation(libs.nimbus.jose.jwt)

    // Test
    testImplementation(libs.junit)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.junit.vintage.engine)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

tasks.withType<Test> {
    useJUnitPlatform()
}
