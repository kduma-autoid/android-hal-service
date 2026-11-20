// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}

allprojects {
    version = project.properties["projectVersion"] as String? ?: "0.0.0"
}

subprojects {
    plugins.withId("com.android.application") {
        extensions.configure<com.android.build.api.dsl.ApplicationExtension> {
            signingConfigs {
                create("release") {
                    storeFile = rootProject.file("keys.jks")
                    storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                        ?: project.findProperty("signingStorePassword") as String? ?: ""
                    keyAlias = "release"
                    keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
                        ?: project.findProperty("signingKeyPassword") as String? ?: ""
                }
            }
            buildTypes {
                getByName("release") {
                    signingConfig = signingConfigs.getByName("release")
                    signingConfig?.enableV1Signing = true
                    signingConfig?.enableV2Signing = true
                }
            }
        }
    }

    plugins.withId("com.android.library") {
        extensions.configure<com.android.build.api.variant.LibraryAndroidComponentsExtension> {
            finalizeDsl { library ->
                library.publishing {
                    // Flavors are declared in each module after the root plugin hook. Include
                    // every value explicitly: filtering only by build type selects no variants
                    // for a flavored library in AGP 9.
                    multipleVariants("release") {
                        includeBuildTypeValues("release")
                        library.flavorDimensions.forEach { dimension ->
                            val values = library.productFlavors.filter { it.dimension == dimension }
                                .map { it.name }.toTypedArray()
                            includeFlavorDimensionAndValues(dimension, *values)
                        }
                        withSourcesJar()
                        withJavadocJar()
                    }
                }
            }
        }

        if (project.findProperty("publishLibrary")?.toString()?.toBoolean() == true) {
            // Apply publishing before attaching the Android components after evaluation.
            apply(plugin = "maven-publish")
            afterEvaluate {
                extensions.configure<org.gradle.api.publish.PublishingExtension> {
                    publications {
                        create<org.gradle.api.publish.maven.MavenPublication>("release") {
                            // AGP may register the component after this afterEvaluate callback,
                            // especially for newly added flavored libraries. Attach it when it is
                            // registered instead of depending on callback registration order.
                            val publication = this
                            components.matching { it.name == "release" }.all {
                                publication.from(this)
                            }
                            groupId = "dev.duma.android.hal"
                            artifactId = project.name
                            version = rootProject.version.toString()
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
        }
    }
}
