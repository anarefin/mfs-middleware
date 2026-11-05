# One-shot init image for mw-audit-dw: psql (PostgreSQL client) and bao (OpenBao CLI).
# Same base images as the platform (pinned by digest, 20.1). Dev/MVP only.
FROM openbao/openbao:2.7.2@sha256:5046911075e137e154ce49ab051502f4ec7cf367d484ae4a4dce665d9b90561f AS bao

FROM postgres:18.6-alpine@sha256:10a5c69a83035ab5239f5620d708520e8b28f5aa1f2b20143e6024532edadfb5
COPY --from=bao /usr/bin/bao /usr/local/bin/bao
COPY init.sh /init.sh
ENTRYPOINT ["/bin/sh", "/init.sh"]
