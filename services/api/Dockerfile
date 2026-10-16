# ── Build: Kotlin API fat jar on JDK 25 (D-28) ──
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src

# Gradle wrapper and build scripts first, so dependency downloads are cached between code changes.
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
RUN ./gradlew --no-daemon -q dependencies > /dev/null

COPY src ./src
RUN ./gradlew --no-daemon -q buildFatJar -x test

# ── Run: slim JRE, non-root ──
FROM eclipse-temurin:25-jre-alpine
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
