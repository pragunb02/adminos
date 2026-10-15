plugins {
    // Downloads the JDK toolchain (libs.versions.toml `jdk`) when it isn't installed locally.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "adminos-api"
