# Image of the compose one-shot `provision`, the dev stack's one provisioning one-shot: psql (PostgreSQL client), vault
# (HashiCorp Vault CLI, ADR D30), openssl (the core mock's PIN key pair, partner-sim's key) and curl (Keycloak admin
# REST: the partner-sim certificate, ticket 73). Same base images as the platform (pinned
# by digest, 20.1). Dev/MVP only.
FROM hashicorp/vault:2.2.2@sha256:dfa48c2fa2b0099ccb47681ec5c4804bf5aec9d6879ecfee07ad4b92a5427ad6 AS vault

FROM postgres:18.6-alpine@sha256:8a334ab9502c8ccb8d7dec4e83b36b5ce8ed7b396e585f1c86b0e69c416f4fca
# Security pass (ticket 46): OS fixes newer than the base image, and no gosu (the base
# entrypoint's privilege drop, built with an old Go; this one-shot never uses it).
RUN apk upgrade --no-cache && apk add --no-cache openssl curl && rm -f /usr/local/bin/gosu
COPY --from=vault /bin/vault /usr/local/bin/vault
COPY lib.sh services.sh provision.sh /provision/
ENTRYPOINT ["/bin/sh", "/provision/provision.sh"]
