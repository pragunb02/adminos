plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktor)
    alias(libs.plugins.versions)
    alias(libs.plugins.kover)
    application
}

group = "dev.adminos"
version = "0.1.0"

application {
    mainClass.set("dev.adminos.api.ApplicationKt")
}

repositories {
    mavenCentral()
}

dependencies {
    // Ktor server
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.auth.jwt)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.call.id)
    implementation(libs.ktor.server.forwarded.header)

    // Auth
    implementation(libs.nimbus.jose.jwt)
    implementation(libs.google.auth)

    // Database
    implementation(libs.exposed.core)
    implementation(libs.exposed.dao)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.java.time)
    implementation(libs.exposed.json)
    implementation(libs.postgresql)
    implementation(libs.hikari)
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.postgresql) // Flyway 10+ ships Postgres support separately

    // Queue
    implementation(libs.lettuce)

    // DI
    implementation(libs.koin.ktor)
    implementation(libs.koin.logger.slf4j)

    // Logging
    implementation(libs.logback)
    implementation(libs.angus.mail) // SMTP for email notifications (AOS-504)

    // Tests
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.greenmail.junit5) // in-process SMTP server for the email sender tests (AOS-504)
    testImplementation(libs.ktor.client.content.negotiation)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.jqwik)
    testImplementation(libs.jqwik.kotlin)
    testImplementation(libs.jspecify) // nullness annotations referenced by jqwik's API
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

// Unit tests only; integration tests (tagged "integration", need Docker) run via `integrationTest`.
tasks.test {
    useJUnitPlatform { excludeTags("integration") }
}

tasks.register<Test>("integrationTest") {
    description = "Runs integration tests (requires Docker)."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("integration") }
    shouldRunAfter(tasks.test)
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
}

// Coverage gate (AOS-312, D-47): unit + integration tests over the whole API.
// `./gradlew test integrationTest koverVerify` fails below the bar; the HTML report is `koverHtmlReport`.
kover {
    reports {
        verify {
            rule("whole API") {
                minBound(90, kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE)
                minBound(85, kotlinx.kover.gradle.plugin.dsl.CoverageUnit.BRANCH)
            }
        }
    }
}

// Prints a new Web Push (VAPID) key pair for .env (AOS-510): ./gradlew -q vapidKeys
tasks.register<JavaExec>("vapidKeys") {
    group = "application"
    description = "Generate a VAPID key pair for Web Push"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.adminos.api.infrastructure.push.VapidKeysKt")
}
