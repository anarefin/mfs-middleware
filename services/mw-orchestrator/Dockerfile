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
WORKDIR /app
COPY --from=extract /build/extracted/dependencies/ ./
COPY --from=extract /build/extracted/spring-boot-loader/ ./
COPY --from=extract /build/extracted/snapshot-dependencies/ ./
COPY --from=extract /build/extracted/application/ ./
COPY build/reports/cyclonedx-direct/bom.json /app/sbom/bom.json

# JVM flags of the AOT training run below and of every start: the cache is rejected when the GC or
# the compressed-oops mode differs (ZGC turns compressed oops off). Production default per spec
# 20.1; the dev stack builds with Serial GC (build arg JVM_OPTS in deploy/docker-compose.yml) and
# adds only sizes at run time (JDK_JAVA_OPTIONS), never another JAVA_TOOL_OPTIONS.
ARG JVM_OPTS="-XX:+UseZGC -XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENV JAVA_TOOL_OPTIONS="${JVM_OPTS}"

# AOT cache training run: start the context once, then exit. No database or Kafka exists at
# build time: Flyway is skipped here only (it runs on every real start-up, 16.4); the pool
# and Kafka clients connect lazily. The search hash key (a secret) comes from OpenBao at run
# time and is validated at start-up, so the training run (only) gets an obviously fake key; the
# image sets none and a real start without the OpenBao value still fails fast.
# MW_AOT=0 skips the training (deploy/docker-compose.yml x-jvm-build-args: the laptop dev stack); the
# ENTRYPOINT then starts without a cache.
ARG MW_AOT=1
RUN if [ "$MW_AOT" = 1 ]; then \
      MW_ORCHESTRATOR_SEARCH_HASHKEY=aot-training-only-fake-search-hash-key-not-a-secret \
      java -XX:AOTCacheOutput=/app/app.aot -Dspring.context.exit=onRefresh \
      -Dspring.flyway.enabled=false -jar app.jar > /dev/null; fi

USER 10001:10001
EXPOSE 8080

HEALTHCHECK --interval=5s --timeout=3s --start-period=30s --retries=3 \
  CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/8080 && printf 'GET /actuator/health/readiness HTTP/1.1\\r\\nHost: localhost\\r\\nConnection: close\\r\\n\\r\\n' >&3 && grep -q '\"status\":\"UP\"' <&3"]

ENTRYPOINT ["bash", "-c", "if [ -f /app/app.aot ]; then exec java -XX:AOTCache=/app/app.aot -jar /app/app.jar; fi; exec java -jar /app/app.jar"]
