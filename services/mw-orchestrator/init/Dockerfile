# One-shot init image for mw-orchestrator: psql (PostgreSQL client) and bao (OpenBao CLI).
# Same base images as the platform (pinned by digest, 20.1). Dev/MVP only.
FROM openbao/openbao:2.7.2@sha256:5046911075e137e154ce49ab051502f4ec7cf367d484ae4a4dce665d9b90561f AS bao

FROM postgres:18.6-alpine@sha256:8a334ab9502c8ccb8d7dec4e83b36b5ce8ed7b396e585f1c86b0e69c416f4fca
# Security pass (ticket 46): OS fixes newer than the base image, and no gosu (the base
# entrypoint's privilege drop, built with an old Go; this one-shot never uses it).
RUN apk upgrade --no-cache && rm -f /usr/local/bin/gosu
COPY --from=bao /usr/bin/bao /usr/local/bin/bao
COPY init.sh /init.sh
ENTRYPOINT ["/bin/sh", "/init.sh"]
