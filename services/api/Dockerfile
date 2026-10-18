# ── Build: Kotlin API fat jar on JDK 25 (D-28) ──
# Official Gradle image pinned to the wrapper's version (gradle/wrapper/gradle-wrapper.properties), so the
# build doesn't download Gradle (that download is slow and fails on some networks).
# Base images are pinned by digest (AOS-622); Dependabot proposes updates.
FROM gradle:9.8.1-jdk25@sha256:4a80c8083135754f689c3e31bb858a22282c66f5625cafb09ed217814b363ee5 AS build
WORKDIR /src

# Build scripts first, so dependency downloads are cached between code changes.
# Locked versions and SHA-256 verification metadata (AOS-622): the build fails on any unexpected artifact.
COPY settings.gradle.kts build.gradle.kts gradle.properties gradle.lockfile settings-gradle.lockfile ./
COPY gradle/libs.versions.toml gradle/verification-metadata.xml ./gradle/
RUN gradle --no-daemon -q dependencies > /dev/null

COPY src ./src
RUN gradle --no-daemon -q buildFatJar -x test

# ── Run: slim JRE, non-root ──
FROM eclipse-temurin:25-jre-alpine@sha256:3c0a9084927a221ccd1d007fcaf614465672c0af37aaa834c5184483afe56d61
RUN addgroup -S app && adduser -S -G app app
WORKDIR /app
COPY --from=build /src/build/libs/*-all.jar /app/api.jar
USER app

EXPOSE 8080
# busybox wget is in the alpine base; the API's /health needs no auth.
HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=5 \
  CMD wget -q -O /dev/null "http://127.0.0.1:${PORT:-8080}/health" || exit 1

# Native access for the console logger (Jansi); required explicitly on newer JDKs.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "--enable-native-access=ALL-UNNAMED", "-jar", "/app/api.jar"]
