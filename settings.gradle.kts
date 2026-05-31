pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        mavenLocal()
        // org.tekfive sibling libs, each resolved from its own GitHub Packages registry. The
        // packages are public, so any valid token works — the build's GITHUB_TOKEN (CI) or
        // gpr.user/gpr.key. Full-source development happens in the AIDEWAY umbrella build.
        listOf("TekFive/ack", "TekFive/jfk", "TekFive/kviash", "TekFive/keep", "TekFive/konnekt").forEach { ghRepo ->
            maven {
                name = "GitHubPackages-${ghRepo.substringAfter('/')}"
                url = uri("https://maven.pkg.github.com/$ghRepo")
                credentials {
                    username = System.getenv("GITHUB_ACTOR") ?: providers.gradleProperty("gpr.user").orNull
                    password = System.getenv("GITHUB_TOKEN") ?: providers.gradleProperty("gpr.key").orNull
                }
            }
        }
    }
}

rootProject.name = "konnekt"
