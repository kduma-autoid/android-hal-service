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
        versionCode = (project.properties["projectVersionCode"] as String? ?: "1").toInt()
        versionName = project.properties["projectVersion"] as String? ?: "0.0.0"

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
            // Plugins with a `stability` dimension are consumed in their `stable` variant.
            missingDimensionStrategy("stability", "stable")
        }
        create("sunmi") {
            dimension = "device"
            // Production build: experimental methods are compiled out of the plugins.
            missingDimensionStrategy("stability", "stable")
        }
        create("sunmiDevelopment") {
            dimension = "device"
            // Full build: every plugin and method, including experimental.
            missingDimensionStrategy("stability", "development")
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
    // Sunmi plugins shared by both sunmi builds (production subset). Consumed in their `stable`
    // variant by the `sunmi` flavor and `development` variant by `sunmiDevelopment`.
    val sunmiCommonPlugins = listOf(
        ":plugins:sunmi:plugin-sunmi-scanner-lib",
        ":plugins:sunmi:sunmiperipher:plugin-sunmi-statuslight-lib",
        ":plugins:sunmi:sunmiperipher:plugin-sunmi-nfc-lib",
        ":plugins:sunmi:sunmiperipher:plugin-sunmi-screen-lib",
        ":plugins:sunmi:tms:plugin-sunmi-device-lib",
        ":plugins:sunmi:tms:plugin-sunmi-led-lib",
        ":plugins:sunmi:sunmiscannersdk:plugin-sunmi-rfid-lib",
        ":plugins:sunmi:sunmiscannersdk:plugin-sunmi-scanner-inner-lib",
        ":plugins:sunmi:printerx:plugin-sunmi-manager-lib",
        ":plugins:sunmi:printerx:plugin-sunmi-printer-lib",
        ":plugins:sunmi:printerx:plugin-sunmi-drawer-lib",
        ":plugins:sunmi:printerx:plugin-sunmi-lcd-lib",
    )
    // Sunmi plugins only in the full development build — experimental at the whole-plugin level.
    val sunmiDevelopmentOnlyPlugins = listOf(
        ":plugins:sunmi:plugin-sunmi-printer-lib",
        ":plugins:sunmi:sunmiperipher:plugin-sunmi-card-lib",
        ":plugins:sunmi:sunmiperipher:plugin-sunmi-docker-lib",
        ":plugins:sunmi:tms:plugin-sunmi-software-lib",
        ":plugins:sunmi:tms:plugin-sunmi-system-lib",
        ":plugins:sunmi:tms:plugin-sunmi-network-lib",
        ":plugins:sunmi:tms:plugin-sunmi-kiosk-lib",
        ":plugins:sunmi:sunmiscannersdk:plugin-sunmi-scanner-camera-lib",
        ":plugins:sunmi:sunmiscannersdk:plugin-sunmi-scanner-external-lib",
    )
    sunmiCommonPlugins.forEach {
        add("sunmiImplementation", project(it))
        add("sunmiDevelopmentImplementation", project(it))
    }
    sunmiDevelopmentOnlyPlugins.forEach {
        add("sunmiDevelopmentImplementation", project(it))
    }

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
