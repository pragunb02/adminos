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

// Supply chain (AOS-622): every resolved version is locked in gradle.lockfile, so builds are reproducible and
// OSV-Scanner sees every transitive dependency; gradle/verification-metadata.xml pins each artifact's SHA-256.
// After changing a dependency: ./gradlew dependencies --write-locks && ./gradlew --write-verification-metadata sha256 test integrationTest koverVerify buildFatJar
dependencyLocking {
    lockAllConfigurations()
}

dependencies {
    // Ktor server
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.call.id)
    implementation(libs.ktor.server.forwarded.header)

    // Jackson is only transitive; the BOM lifts every Jackson module to a patched release (AOS-615, AUD-02).
    implementation(platform(libs.jackson.bom))

    // Auth (JWTs are nimbus-jose-jwt; the unused ktor-server-auth-jwt, which pulled in another JWT library, was removed)
    implementation(libs.nimbus.jose.jwt)
    implementation(libs.google.auth)
    implementation(libs.webauthn.server.core) // passkeys, the second factor after Google (AOS-617)

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
    // S3-compatible object storage (Cloudflare R2) for the documents vault (AOS-507); the small JDK HTTP client, not Netty/Apache
    implementation(libs.aws.s3) { exclude(group = "software.amazon.awssdk", module = "netty-nio-client"); exclude(group = "software.amazon.awssdk", module = "apache-client") }
    implementation(libs.aws.url.connection.client)
    // Claude for the optional AI fallback categorizer (AOS-512, D-62): the official SDK (retries, timeouts, typed errors)
    implementation(libs.anthropic.java)

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

// Categorization evaluation (AOS-511, D-61): ./gradlew -q categorizationReport prints precision/recall/F1 on the
// synthetic golden set and writes build/reports/categorization/report.json. Add -PwriteBaseline to deliberately
// record the current numbers as the new floor (src/test/resources/categorization/baseline.json).
// AOS-512: it also evaluates rules + the AI fallback with recorded answers (report-ai.json, baseline-ai.json).
// -PrecordAi re-records those answers from the real Claude API (needs ANTHROPIC_API_KEY; sends merchant names only).
// AOS-707: and rules + the learned categoriser, 5-fold cross-validated (report-learned.json, baseline-learned.json),
// with naive Bayes printed alongside as the comparison. All local: nothing leaves the machine.
tasks.register<JavaExec>("categorizationReport") {
    group = "verification"
    description = "Evaluate the categorizer on the golden set"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.adminos.api.domain.financial.evaluation.CategorizationReportKt")
    args(layout.buildDirectory.file("reports/categorization/report.json").get().asFile.path)
    if (project.hasProperty("writeBaseline")) {
        args("--write-baseline", file("src/test/resources/categorization").path)
        // AOS-702: also refresh the categorisation record the start-up eval gate reads (D-67)
        args("--eval-records", file("src/main/resources/ai/evals.json").path)
    }
    if (project.hasProperty("recordAi")) {
        args("--record-ai", file("src/test/resources/categorization/ai-recorded.csv").path)
    }
}

// Local-model bake-off (AOS-703, D-69): ./gradlew -q aiBakeOff -Pmodels=qwen3:4b,gemma3:4b runs each Ollama model
// (OLLAMA_BASE_URL, default http://127.0.0.1:11434: start it with `docker compose --profile ollama up -d ollama`)
// through the categorisation golden set via the AI port and prints a comparison with the rules and the recorded
// Claude answers. -PwriteEvalRecord adds passing models to src/main/resources/ai/evals.json (the eval gate, D-68).
tasks.register<JavaExec>("aiBakeOff") {
    group = "verification"
    description = "Compare local models on the categorisation golden set"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.adminos.api.domain.financial.evaluation.AiBakeOffKt")
    args("--models", (project.findProperty("models") ?: "").toString())
    args("--out", layout.buildDirectory.dir("reports/ai-bakeoff").get().asFile.path)
    if (project.hasProperty("writeEvalRecord")) {
        args("--write-eval-records", file("src/main/resources/ai/evals.json").path)
    }
}

// Forecast backtest (AOS-714): ./gradlew -q forecastReport prints MAPE / relative MAE vs the naive pace / 80% range
// coverage on synthetic histories and writes build/reports/forecast/report.json. -PwriteBaseline records the floor
// (src/test/resources/forecast/baseline.json) that ForecastBacktestTest holds the shipped engine to.
tasks.register<JavaExec>("forecastReport") {
    group = "verification"
    description = "Backtest the month-end forecast on synthetic histories"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.adminos.api.domain.forecast.evaluation.ForecastReportKt")
    args(layout.buildDirectory.file("reports/forecast/report.json").get().asFile.path)
    if (project.hasProperty("writeBaseline")) {
        args("--write-baseline", file("src/test/resources/forecast").path)
    }
}

// Prints a new Web Push (VAPID) key pair for .env (AOS-510): ./gradlew -q vapidKeys
tasks.register<JavaExec>("vapidKeys") {
    group = "application"
    description = "Generate a VAPID key pair for Web Push"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.adminos.api.infrastructure.push.VapidKeysKt")
}
