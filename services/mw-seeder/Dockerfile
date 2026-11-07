# syntax=docker/dockerfile:1
# Image for this service only (spec 20.1). Build from this directory after the jar:
#   ./gradlew check bootJar && docker build -t mw-seeder .
# Base: eclipse-temurin 25 JRE. Runs as a non-root user; Compose adds a read-only root
# FS and a tmpfs /tmp (20.2). JDK 25 AOT cache (JEP 514) cuts start-up time.
# One-shot (ticket 57): no port and no HEALTHCHECK; the container's exit code is the result.

ARG JRE_IMAGE=eclipse-temurin:25.0.5_8-jre-noble

FROM ${JRE_IMAGE} AS extract
WORKDIR /build
COPY build/libs/*.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --destination extracted

FROM ${JRE_IMAGE}
WORKDIR /app
COPY --from=extract /build/extracted/dependencies/ ./
COPY --from=extract /build/extracted/spring-boot-loader/ ./
COPY --from=extract /build/extracted/snapshot-dependencies/ ./
COPY --from=extract /build/extracted/application/ ./
COPY build/reports/cyclonedx-direct/bom.json /app/sbom/bom.json
# The manifest folder: a fresh named volume mounted here takes this owner, so user 10001 can write it.
RUN mkdir -p /run/mw/seed && chown 10001:10001 /run/mw/seed

# JVM flags of the AOT training run below and of every start: the cache is rejected when the GC or
# the compressed-oops mode differs (ZGC turns compressed oops off). Production default per spec
# 20.1; the dev stack builds with Serial GC (build arg JVM_OPTS in deploy/docker-compose.yml) and
# adds only sizes at run time (JDK_JAVA_OPTIONS), never another JAVA_TOOL_OPTIONS.
ARG JVM_OPTS="-XX:+UseZGC -XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENV JAVA_TOOL_OPTIONS="${JVM_OPTS}"

# AOT cache training run: start the context once, then exit before the seed runner (no stack
# exists at build time).
# MW_AOT=0 skips the training (deploy/docker-compose.yml x-jvm-build-args: the laptop dev stack); the
# ENTRYPOINT then starts without a cache.
ARG MW_AOT=1
RUN if [ "$MW_AOT" = 1 ]; then \
      java -XX:AOTCacheOutput=/app/app.aot -Dspring.context.exit=onRefresh -jar app.jar > /dev/null; fi

USER 10001:10001

ENTRYPOINT ["bash", "-c", "if [ -f /app/app.aot ]; then exec java -XX:AOTCache=/app/app.aot -jar /app/app.jar; fi; exec java -jar /app/app.jar"]
