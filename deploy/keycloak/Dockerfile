# Keycloak for the single-host stack (ticket 55), built by compose as mfs-mw/keycloak:local.
# `kc.sh build` runs once here with the build-time options, so a start (`--optimized`) skips the
# Quarkus re-augmentation (about 10 s on every start before). Keep the options below equal to
# deploy/compose/platform/env/keycloak.env: an optimized start refuses a changed build option.
# No custom providers or theme; the realms stay a read-only mount (their ${VAR} placeholders are
# resolved from the environment on import), so a realm edit needs no rebuild.
FROM quay.io/keycloak/keycloak:26.8.1@sha256:3f3c5f8e03c614774b4bd9173b88391fefa4153bfc010d8012d59ad825377332 AS builder
ENV KC_DB=postgres \
    KC_HEALTH_ENABLED=true
RUN /opt/keycloak/bin/kc.sh build

FROM quay.io/keycloak/keycloak:26.8.1@sha256:3f3c5f8e03c614774b4bd9173b88391fefa4153bfc010d8012d59ad825377332
COPY --from=builder /opt/keycloak/ /opt/keycloak/
ENTRYPOINT ["/opt/keycloak/bin/kc.sh", "start", "--optimized", "--import-realm"]
