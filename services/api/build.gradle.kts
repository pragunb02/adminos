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
    // AOS-708: -PlocalGolden=<csv> scores your own scrubbed local golden set (exported from Review); printed only.
    project.findProperty("localGolden")?.let { args("--local-golden", file(it.toString()).path) }
}

// Subscription detection evaluation (AOS-516): ./gradlew -q subscriptionEvalReport prints precision/recall/F1 and
// cycle accuracy on the synthetic eval set (subscriptions vs regular shopping). Add -PwriteBaseline to deliberately
// record the current numbers as the new floor (src/test/resources/subscriptions/baseline.json).
tasks.register<JavaExec>("subscriptionEvalReport") {
    group = "verification"
    description = "Evaluate subscription detection on the synthetic eval set"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.adminos.api.domain.financial.evaluation.subscription.SubscriptionEvalKt")
    if (project.hasProperty("writeBaseline")) {
        args("--write-baseline", file("src/test/resources/subscriptions").path)
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

// "Ask your money" eval (AOS-712, D-68): ./gradlew -q askEvalReport prints tool-choice, argument, abstention and
// number-grounding scores on the synthetic eval set and seeded DB, and writes build/reports/ask/report.json.
// -PwriteEvalRecord records them in src/main/resources/ai/evals.json (the start-up gate); -PdumpResults writes each
// question's expected tool results to build/reports/ask/results.json (for writing recorded answers).
tasks.register<JavaExec>("askEvalReport") {
    group = "verification"
    description = "Evaluate Ask your money on its eval set"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.adminos.api.domain.ask.eval.AskEvalReportKt")
    args("--out", layout.buildDirectory.file("reports/ask/report.json").get().asFile.path)
    if (project.hasProperty("writeEvalRecord")) {
        args("--write-eval-records", file("src/main/resources/ai/evals.json").path)
    }
    if (project.hasProperty("dumpResults")) {
        args("--dump-results", layout.buildDirectory.file("reports/ask/results.json").get().asFile.path)
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

// Merchant grouping evaluation (AOS-709): ./gradlew -q merchantGroupingReport prints pair precision/recall of each
// matcher on the synthetic pairs (src/test/resources/merchants/pairs.csv); -PwriteBaseline records the floor.
tasks.register<JavaExec>("merchantGroupingReport") {
    group = "verification"
    description = "Evaluate merchant grouping on the synthetic pairs"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.adminos.api.domain.financial.grouping.GroupingEvaluationKt")
    args(layout.buildDirectory.file("reports/merchants/report.json").get().asFile.path)
    if (project.hasProperty("writeBaseline")) {
        args("--write-baseline", file("src/test/resources/merchants/baseline.json").path)
    }
}

// Anomaly alert evaluation (AOS-713): ./gradlew -q anomalyReport replays the synthetic spending streams
// (src/test/resources/anomalies/streams.csv) through the detector and prints old vs new precision, recall and
// false alerts per month. -PwriteBaseline records the numbers as the floor (anomalies/baseline.json);
// -PregenerateStreams rewrites the streams from the seeded generator (then re-score and re-baseline).
tasks.register<JavaExec>("anomalyReport") {
    group = "verification"
    description = "Evaluate unusual-charge alerts on the synthetic streams"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.adminos.api.domain.agent.evaluation.AnomalyReportKt")
    args(layout.buildDirectory.file("reports/anomalies/report.json").get().asFile.path)
    if (project.hasProperty("writeBaseline")) {
        args("--write-baseline", file("src/test/resources/anomalies/baseline.json").path)
    }
    if (project.hasProperty("regenerateStreams")) {
        args("--regenerate", file("src/test/resources/anomalies/streams.csv").path)
    }
}

// Cash phrases (AOS-718): ./gradlew -q cashPhraseReport scores the free-text cash parser on the synthetic phrase set
// (src/test/resources/cash/phrases.tsv) and lists the misses; -PwriteBaseline records the floor CashPhraseRegressionTest holds.
tasks.register<JavaExec>("cashPhraseReport") {
    group = "verification"
    description = "Evaluate the cash phrase parser on the phrase set"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.adminos.api.domain.ingestion.cash.evaluation.CashPhraseEvaluationKt")
    args(layout.buildDirectory.file("reports/cash/report.json").get().asFile.path)
    if (project.hasProperty("writeBaseline")) {
        args("--write-baseline", file("src/test/resources/cash").path)
    }
}

// Briefing narration eval (AOS-716, D-140): ./gradlew -q briefingNarrationReport runs the synthetic eval set through the
// AI gateway and prints the number/name check's scores and misses; -PwriteEvalRecord records them in
// src/main/resources/ai/evals.json (the start-up gate, D-68). Nothing leaves the machine (recorded answers).
tasks.register<JavaExec>("briefingNarrationReport") {
    group = "verification"
    description = "Evaluate the briefing narration check on its eval set"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.adminos.api.domain.agent.briefing.eval.BriefingNarrationEvalReportKt")
    if (project.hasProperty("writeEvalRecord")) {
        args("--write-eval-records", file("src/main/resources/ai/evals.json").path)
    }
}

// Message-reading evaluation (AOS-705): ./gradlew -q messageReadingReport prints the fallback's scores on the synthetic
// golden set (src/test/resources/messages/golden.tsv). Add -PwriteBaseline to record them in the eval records the
// start-up gate reads (src/main/resources/ai/evals.json).
tasks.register<JavaExec>("messageReadingReport") {
    group = "verification"
    description = "Evaluate the message-reading fallback on the golden set"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.adminos.api.domain.ingestion.fallback.evaluation.MessageReadingReportKt")
    if (project.hasProperty("writeBaseline")) {
        args("--eval-records", file("src/main/resources/ai/evals.json").path)
    }
}

// Splitwise matching eval (AOS-911): ./gradlew -q splitwiseReport prints match precision/recall and spend correctness
// on the synthetic eval set and writes build/reports/splitwise/report.json. -PwriteBaseline records the current
// numbers as the new floor (src/test/resources/splits/baseline.json), which SplitEvalTest enforces.
tasks.register<JavaExec>("splitwiseReport") {
    group = "verification"
    description = "Evaluate Splitwise matching on the synthetic eval set"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.adminos.api.domain.financial.splits.eval.SplitEvalReportKt")
    args(layout.buildDirectory.file("reports/splitwise/report.json").get().asFile.path)
    if (project.hasProperty("writeBaseline")) {
        args("--write-baseline", file("src/test/resources/splits").path)
    }
}

// Prints a new Web Push (VAPID) key pair for .env (AOS-510): ./gradlew -q vapidKeys
tasks.register<JavaExec>("vapidKeys") {
    group = "application"
    description = "Generate a VAPID key pair for Web Push"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.adminos.api.infrastructure.push.VapidKeysKt")
}
