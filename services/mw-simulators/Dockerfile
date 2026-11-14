# syntax=docker/dockerfile:1
# Image for this service only (spec 20.1). Build the jar first, from the repo root:
#   ./gradlew :mw-simulators:bootJar && docker build -t mw-simulators services/mw-simulators
# Base: eclipse-temurin 25 JRE on Alpine (as mw-crypto): the payShield simulator keeps its LMK in the
# softhsm paysim token (ticket 64), and the PKCS#11 client side, p11-kit, must speak the same RPC
# protocol version as the softhsm container's `p11-kit remote` (Alpine p11-kit 0.26.2; Ubuntu noble's
# 0.25.3 fails key generation). Runs as a non-root user; Compose adds a read-only root FS and a tmpfs
# /tmp (20.2).

ARG JRE_IMAGE=eclipse-temurin:25.0.5_8-jre-alpine

FROM ${JRE_IMAGE} AS extract
WORKDIR /build
# The fixed name bootJar writes (build.gradle.kts), so a stale jar is never picked up.
COPY build/libs/mw-simulators.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --destination extracted

FROM ${JRE_IMAGE}
# PKCS#11 for the payShield simulator's LMK (deploy/softhsm/README.md, "Client recipes"): SunPKCS11
# loads the p11-kit proxy /usr/lib/libp11-kit.so.0 (sim.payshield.pkcs11.library), which sees exactly
# one module, the paysim token over TCP (`remote: |socat - TCP:softhsm:5658`), so slotListIndex 0 is
# that token. The package's own trust module is removed, or the proxy would show its slot too. bash
# runs the readiness healthcheck below (Alpine has none). Before the jar layers, so a code change
# keeps this layer cached.
RUN apk add --no-cache p11-kit=0.26.2-r0 socat=1.8.1.3-r0 bash=5.3.9-r1 \
    && rm -f /usr/share/p11-kit/modules/*.module \
    && mkdir -p /etc/pkcs11/modules \
    && printf 'remote: |socat - TCP:softhsm:5658,connect-timeout=3,keepalive,keepidle=5,keepintvl=2,keepcnt=3\n' > /etc/pkcs11/modules/paysim.module

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
# 8080: the HTTP simulators and control API; 1500: payShield host commands (internal network only).
EXPOSE 8080 1500

HEALTHCHECK --interval=5s --timeout=3s --start-period=30s --retries=3 \
  CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/8080 && printf 'GET /actuator/health/readiness HTTP/1.1\\r\\nHost: localhost\\r\\nConnection: close\\r\\n\\r\\n' >&3 && grep -q '\"status\":\"UP\"' <&3"]

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
