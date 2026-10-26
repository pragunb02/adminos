# ── Build: Kotlin API fat jar on JDK 25 (D-28) ──
# Official Gradle image pinned to the wrapper's version (gradle/wrapper/gradle-wrapper.properties), so the
# build doesn't download Gradle (that download is slow and fails on some networks).
# Base images are pinned by digest (AOS-622); Dependabot proposes updates.
FROM gradle:9.8.2-jdk25@sha256:ca424744a6a45a575f333edea680696d980999c461be38b1574a4c70d5455f24 AS build
WORKDIR /src

# Build scripts first, so dependency downloads are cached between code changes.
# Locked versions and SHA-256 verification metadata (AOS-622): the build fails on any unexpected artifact.
COPY settings.gradle.kts build.gradle.kts gradle.properties gradle.lockfile settings-gradle.lockfile ./
COPY gradle/libs.versions.toml gradle/verification-metadata.xml ./gradle/
RUN gradle --no-daemon -q dependencies > /dev/null

COPY src ./src
RUN gradle --no-daemon -q buildFatJar -x test

# ── Run: slim JRE, non-root ──
FROM eclipse-temurin:25-jre-alpine@sha256:b7528962e65d43064d8a8b67ba175367896bc948363ea2d3fcd7f10d34e1f048
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
