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

        // Development builds (see product flavors) flip this on to expose the configurable
        // listen address/port. Production builds keep the service bound to localhost.
        buildConfigField("boolean", "DEVELOPMENT", "false")
    }

    buildFeatures {
        aidl = true
        buildConfig = true
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

    // Two orthogonal dimensions:
    //   device    — which vendor plugins are on the classpath (generic vs sunmi)
    //   stability  — whether experimental methods are compiled in (stable vs development)
    // The `stability` dimension is shared with the plugin modules, so selecting a single Build
    // Variant in Android Studio switches the app and every plugin together (no per-module fiddling).
    flavorDimensions += listOf("device", "stability")
    productFlavors {
        create("generic") {
            dimension = "device"
        }
        create("sunmi") {
            dimension = "device"
        }
        create("stable") {
            dimension = "stability"
            // Production build: experimental methods are compiled out of the plugins.
        }
        create("development") {
            dimension = "stability"
            // Full build: every plugin and method, including experimental, plus the
            // development-only options (configurable listen address/port).
            buildConfigField("boolean", "DEVELOPMENT", "true")
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

// AGP generates dependency configurations per flavour and per build type, but NOT for
// multi-dimension flavour combinations, so there is no `sunmiDevelopmentImplementation`. Collect
// the plugins that belong only to the sunmi+development combination in this dependency bucket and
// extend just the matching variants' classpaths with it (see the androidComponents block below).
val sunmiDevelopmentOnly = configurations.create("sunmiDevelopmentOnly") {
    isCanBeResolved = false
    isCanBeConsumed = false
}

androidComponents {
    onVariants { variant ->
        // Variant names are the flavour combination + build type, e.g. sunmiDevelopmentRelease.
        if (variant.name.startsWith("sunmiDevelopment")) {
            variant.compileConfiguration.extendsFrom(sunmiDevelopmentOnly)
            variant.runtimeConfiguration.extendsFrom(sunmiDevelopmentOnly)
        }
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
    // Sunmi plugins shared by both sunmi builds (production subset). Added to the `sunmi` device
    // flavour, so they land in both sunmiStable and sunmiDevelopment; AGP matches each plugin's
    // `stability` variant to the app's (stable → experimental compiled out, development → kept).
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
    // Added to the sunmi+development flavour combination only (via `sunmiDevelopmentOnly`, wired
    // below), so they never reach a stable APK.
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
    }
    sunmiDevelopmentOnlyPlugins.forEach {
        add("sunmiDevelopmentOnly", project(it))
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
