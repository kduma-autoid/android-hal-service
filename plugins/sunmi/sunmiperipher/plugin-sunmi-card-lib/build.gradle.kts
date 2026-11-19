plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.duma.android.hal.plugins.sunmi.card"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

    buildFeatures {
        buildConfig = true
    }

    testOptions { unitTests.isIncludeAndroidResources = true }

    // Experimental methods are compiled in only for the `development` variant.
    flavorDimensions += "stability"
    productFlavors {
        create("development") {
            dimension = "stability"
            buildConfigField("boolean", "WITH_EXPERIMENTAL", "true")
        }
        create("stable") {
            dimension = "stability"
            buildConfigField("boolean", "WITH_EXPERIMENTAL", "false")
        }
    }
}

dependencies {
    implementation(project(":service:hal-contract"))
    implementation(project(":plugins:sunmi:sunmiperipher:plugin-sunmi-sunmiperipher-sdk"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.junit.jupiter)
    // kotlin-test-junit5 brings the JUnit Platform launcher Gradle 9 needs on the test runtime classpath.
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.vintage.engine)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}

tasks.withType<Test> { useJUnitPlatform() }
