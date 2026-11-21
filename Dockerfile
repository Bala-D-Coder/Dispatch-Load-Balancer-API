# syntax=docker/dockerfile:1
# ---------------------------------------------------------------------------
# Dispatch Load Balancer API — multi-stage build.
#
#   docker build -t dispatch-load-balancer-api .
#   docker run --rm -p 8080:8080 -e DB_URL=... -e DB_USERNAME=... -e DB_PASSWORD=... \
#     dispatch-load-balancer-api
#
# The image carries no database. It needs DB_URL / DB_USERNAME / DB_PASSWORD at
# runtime (see application.yaml); with none set it tries localhost:5433 and fails
# to start, which is the correct failure for a container.
# ---------------------------------------------------------------------------

# --- Stage 1: build --------------------------------------------------------
# The image's own Maven is used here; local development and CI use the wrapper,
# which pins Maven 3.10.1. Same pom.xml either way — the layer that must be
# byte-reproducible is the jar, not the toolchain.
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /build

# Dependencies first so this expensive layer survives source-only changes.
# `resolve` (the project's own compile/runtime/test dependencies) is deliberate:
# `go-offline` additionally downloads the whole plugin zoo — kotlin, site,
# versions, the Hibernate enhancement plugins. Measured 2026-11-21 on a cold
# cache: this step 1m49s with resolve vs 10m57s with go-offline, then
# `clean package` 35s and the whole build 153s. Nothing here needs an offline build.
COPY pom.xml .
RUN mvn -B -ntp dependency:resolve

COPY src ./src
# Tests are NOT run here: the *IT suite needs a Docker daemon (Testcontainers),
# which the build container does not have. CI runs the full suite separately with
# `./mvnw -B verify` — see .github/workflows/ci.yml.
RUN mvn -B -ntp -DskipTests clean package

# --- Stage 2: runtime ------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine AS runtime

ARG APP_VERSION=0.0.1-SNAPSHOT
ARG BUILD_ENV=dev

LABEL org.opencontainers.image.title="dispatch-load-balancer-api" \
      org.opencontainers.image.description="Delivery order to vehicle dispatch optimization API" \
      org.opencontainers.image.version="${APP_VERSION}" \
      org.opencontainers.image.licenses="UNLICENSED" \
      build.environment="${BUILD_ENV}"

WORKDIR /app

# Unprivileged, and no writable path is required: the app logs to stdout and has no
# temp-file or upload behaviour.
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

COPY --from=build --chown=appuser:appgroup /build/target/dispatch-load-balancer-api-*.jar app.jar

# Render (and most PaaS) injects PORT; Spring Boot does not read that name, so it is
# passed through explicitly rather than silently ignored.
ENV PORT=8080 \
    JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+UseG1GC \
-XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom -Dfile.encoding=UTF-8"

EXPOSE 8080

# busybox wget ships in the alpine JRE image — no extra packages for the probe.
# The measured context boot is ~4 s, so 40 s of start grace is already generous.
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider "http://127.0.0.1:${PORT}/healthz" || exit 1

USER appuser

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -Dserver.port=\"${PORT:-8080}\" -jar /app/app.jar"]
