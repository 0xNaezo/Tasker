# syntax=docker/dockerfile:1
#
# AI proxy for Google Play builds (tech plan §18.1). Build from the repository root, because the backend
# shares core modules and the Gradle build with the app:
#
#   docker build -f backend/Dockerfile -t tasker-backend .
#
# backend/Dockerfile.dockerignore keeps build outputs, local SDK paths and secrets out of the context.

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
# --configure-on-demand configures only the projects :backend needs, so the Android SDK is not required.
# The Kotlin compiler runs inside the Gradle process to keep the builder's memory low.
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon --configure-on-demand \
        -Dorg.gradle.jvmargs=-Xmx1536m \
        -Pkotlin.compiler.execution.strategy=in-process \
        :backend:installDist

FROM eclipse-temurin:21-jre
RUN groupadd --system --gid 10001 tasker \
    && useradd --system --uid 10001 --gid tasker --home-dir /app --shell /usr/sbin/nologin tasker
WORKDIR /app
# Owned by root and read-only for the service user: the process cannot modify its own code.
COPY --from=build /src/backend/build/install/backend/ /app/
USER 10001:10001
ENV PORT=8080 \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
ENTRYPOINT ["/app/bin/backend"]
