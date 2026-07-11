plugins {
    alias(libs.plugins.kotlin.jvm)
    `maven-publish`
}

group = "org.tekfive"
version = "1.0.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    implementation(libs.kotlin.stdlib)
    implementation(libs.okhttp)
    implementation("com.sun.mail:jakarta.mail:2.0.2")
    implementation("org.tekfive:jfk:1.0.0")
    implementation("org.tekfive:keep:1.0.0")
    implementation("org.tekfive:ack:1.0.0")
    implementation("org.tekfive:kviash:1.0.0")

    compileOnly(libs.exposed.jdbc)

    testImplementation(libs.exposed.jdbc)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.postgresql)
    testImplementation(libs.testcontainers.core)
    testImplementation(libs.testcontainers.postgresql)
}

tasks.test {
    useJUnitPlatform()
}

allprojects {
    // Workaround: LinuxKit/Docker overlay filesystem corrupts jars and Gradle caches
    // during Kotlin compilation. Redirect build output to /tmp when explicitly enabled.
    System.getenv("SANDBOX_BUILD_DIR")?.let { sandboxDir ->
        layout.buildDirectory = file("$sandboxDir/${project.name}")
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            groupId = "org.tekfive"
            artifactId = "konnekt"
        }
    }
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri(
                System.getenv("GITHUB_REPOSITORY")?.let { "https://maven.pkg.github.com/$it" }
                    ?: "https://maven.pkg.github.com/TekFive/konnekt",
            )
            credentials {
                username = System.getenv("GITHUB_ACTOR") ?: findProperty("gpr.user") as String?
                password = System.getenv("GITHUB_TOKEN") ?: findProperty("gpr.key") as String?
            }
        }
    }
}
