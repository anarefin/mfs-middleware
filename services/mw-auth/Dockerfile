# syntax=docker/dockerfile:1
# Image for this service only (spec 20.1). Build the jar first, from the repo root:
#   ./gradlew :mw-auth:bootJar && docker build -t mw-auth services/mw-auth
# Base: eclipse-temurin 25 JRE. Runs as a non-root user; Compose adds a read-only root
# FS and a tmpfs /tmp (20.2).

ARG JRE_IMAGE=eclipse-temurin:25.0.5_8-jre-noble

FROM ${JRE_IMAGE} AS extract
WORKDIR /build
# The fixed name bootJar writes (build.gradle.kts), so a stale jar is never picked up.
COPY build/libs/mw-auth.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --destination extracted

FROM ${JRE_IMAGE}
WORKDIR /app
COPY --from=extract /build/extracted/dependencies/ ./
COPY --from=extract /build/extracted/spring-boot-loader/ ./
COPY --from=extract /build/extracted/snapshot-dependencies/ ./
COPY --from=extract /build/extracted/application/ ./

# JVM flags of every start. Production default per spec 20.1; the dev stack builds with Serial GC
# (build arg JVM_OPTS in deploy/docker-compose.yml) and adds only sizes at run time
# (JDK_JAVA_OPTIONS), never another JAVA_TOOL_OPTIONS.
ARG JVM_OPTS="-XX:+UseZGC -XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENV JAVA_TOOL_OPTIONS="${JVM_OPTS}"

USER 10001:10001
# 8080 public /auth/v1/** (behind APISIX, tickets 68-69), 8443 internal /internal/v1/** (mTLS, D24),
# 8081 actuator (plain HTTP inside the network, never published).
EXPOSE 8080 8443 8081

HEALTHCHECK --interval=5s --timeout=3s --start-period=30s --retries=3 \
  CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/8081 && printf 'GET /actuator/health/readiness HTTP/1.1\\r\\nHost: localhost\\r\\nConnection: close\\r\\n\\r\\n' >&3 && grep -q '\"status\":\"UP\"' <&3"]

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
