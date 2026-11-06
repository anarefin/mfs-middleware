# syntax=docker/dockerfile:1
# Image for this service only (spec 20.1). Build from this directory after the jar:
#   ./gradlew check bootJar && docker build -t mw-orchestrator .
# Base: eclipse-temurin 25 JRE. Runs as a non-root user; Compose adds a read-only root
# FS and a tmpfs /tmp (20.2). JDK 25 AOT cache (JEP 514) cuts start-up time.

ARG JRE_IMAGE=eclipse-temurin:25.0.5_8-jre-noble

FROM ${JRE_IMAGE} AS extract
WORKDIR /build
COPY build/libs/*.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --destination extracted

FROM ${JRE_IMAGE}
# Security pass (ticket 46): Ubuntu fixes newer than the base image (trivy image, HIGH/CRITICAL).
RUN apt-get update && apt-get -y upgrade && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=extract /build/extracted/dependencies/ ./
COPY --from=extract /build/extracted/spring-boot-loader/ ./
COPY --from=extract /build/extracted/snapshot-dependencies/ ./
COPY --from=extract /build/extracted/application/ ./
COPY build/reports/cyclonedx-direct/bom.json /app/sbom/bom.json

# JVM flags of the AOT training run below and of every start: the cache is rejected when the GC or
# the compressed-oops mode differs (ZGC turns compressed oops off). Production default per spec
# 20.1; the dev stack builds with Serial GC (build arg in deploy/compose/mvp/compose.lowmem.yml) and
# adds only sizes at run time (JDK_JAVA_OPTIONS), never another JAVA_TOOL_OPTIONS.
ARG JVM_OPTS="-XX:+UseZGC -XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENV JAVA_TOOL_OPTIONS="${JVM_OPTS}"

# AOT cache training run: start the context once, then exit. No database or Kafka exists at
# build time: Flyway is skipped here only (it runs on every real start-up, 16.4); the pool
# and Kafka clients connect lazily. The search hash key (a secret) comes from OpenBao at run
# time and is validated at start-up, so the training run (only) gets an obviously fake key; the
# image sets none and a real start without the OpenBao value still fails fast.
RUN MW_ORCHESTRATOR_SEARCH_HASHKEY=aot-training-only-fake-search-hash-key-not-a-secret \
      java -XX:AOTCacheOutput=/app/app.aot -Dspring.context.exit=onRefresh \
      -Dspring.flyway.enabled=false -jar app.jar > /dev/null

USER 10001:10001
EXPOSE 8080

HEALTHCHECK --interval=5s --timeout=3s --start-period=30s --retries=3 \
  CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/8080 && printf 'GET /actuator/health/readiness HTTP/1.1\\r\\nHost: localhost\\r\\nConnection: close\\r\\n\\r\\n' >&3 && grep -q '\"status\":\"UP\"' <&3"]

ENTRYPOINT ["java", "-XX:AOTCache=/app/app.aot", "-jar", "/app/app.jar"]
