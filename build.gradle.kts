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
    plugins.withId("com.android.library") {
        extensions.configure<com.android.build.api.dsl.LibraryExtension> {
            publishing {
                singleVariant("release") {
                    withSourcesJar()
                    withJavadocJar()
                }
            }
        }

        afterEvaluate {
            apply(plugin = "maven-publish")
            extensions.configure<org.gradle.api.publish.PublishingExtension> {
                publications {
                    create<org.gradle.api.publish.maven.MavenPublication>("release") {
                        from(components["release"])
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
