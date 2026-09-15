# syntax=docker/dockerfile:1

########################################
# Build stage
########################################
FROM eclipse-temurin:26-jdk AS build
WORKDIR /workspace

# Wrapper + build scripts first, so dependency resolution is cached
# independently of source changes.
COPY gradlew ./
COPY gradle ./gradle
COPY settings.gradle build.gradle ./
RUN --mount=type=cache,target=/root/.gradle \
    chmod +x ./gradlew && ./gradlew --no-daemon --version

# Source, then the actual build. Tests are skipped here (run them in CI);
# the Gradle cache is mounted so repeat builds don't re-download the world.
COPY src ./src
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon clean bootJar -x test

########################################
# Runtime stage
########################################
FROM eclipse-temurin:26-jre AS runtime
WORKDIR /app

# Non-root runtime user
RUN groupadd --system app && useradd --system --gid app --home /app app

# Spring Boot fat jar (the -plain.jar is intentionally not matched)
COPY --from=build --chown=app:app /workspace/build/libs/*-SNAPSHOT.jar /app/app.jar

USER app

# Honour container memory limits; picked up by the JVM automatically.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0"

# Actuator endpoints (health, prometheus); see server.port.
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
