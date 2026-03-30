plugins {
    alias(libs.plugins.android.application)
    id("maven-publish")
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
        versionCode = 2
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
    implementation(project(":service:hal-contract"))
    implementation(project(":plugins:sunmi:printerx:plugin-sunmi-manager-lib"))
    implementation(project(":plugins:sunmi:printerx:plugin-sunmi-printer-lib"))
    implementation(project(":plugins:sunmi:printerx:plugin-sunmi-drawer-lib"))
    implementation(project(":plugins:sunmi:printerx:plugin-sunmi-lcd-lib"))
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                groupId = "dev.duma.android.hal"
                artifactId = project.name
                version = android.defaultConfig.versionName!!
                artifact(layout.buildDirectory.file("outputs/apk/release/${project.name}-release.apk").get()) {
                    extension = "apk"
                    builtBy(tasks.named("assembleRelease"))
                }
            }
        }
        repositories {
            maven {
                name = "GitHubPackages"
                url = uri("https://maven.pkg.github.com/kduma-autoid/android-hal-service")
                credentials {
                    username = System.getenv("GITHUB_ACTOR") ?: ""
                    password = System.getenv("GITHUB_TOKEN") ?: ""
                }
            }
        }
    }
}
