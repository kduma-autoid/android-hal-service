plugins {
    `maven-publish`
}

data class VendorAar(
    val artifactId: String,
    val version: String,
    val filePath: String,
)

val vendorAars = listOf(
    VendorAar(
        artifactId = "sunmiperipher-sdk",
        version = "1.0.2",
        filePath = "libs/sunmiperipher_sdk_v1.0.2.aar",
    ),
    VendorAar(
        artifactId = "sunmi-customer-api",
        version = "1.3.33",
        filePath = "libs/SUNMI_CUSTOMER_API_v1.3.33_release.aar",
    ),
    VendorAar(
        artifactId = "sunmi-scanner-sdk",
        version = "1.1.12",
        filePath = "libs/SunmiScannerSdk-release-v1.1.12.aar",
    ),
)

publishing {
    publications {
        vendorAars.forEach { aar ->
            create<MavenPublication>(aar.artifactId) {
                groupId = "dev.duma.android.hal.vendor"
                artifactId = aar.artifactId
                version = aar.version
                artifact(file(aar.filePath)) {
                    extension = "aar"
                }
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
