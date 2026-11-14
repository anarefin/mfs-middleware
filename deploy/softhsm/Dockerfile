# SoftHSMv2 PKCS#11 tokens for dev and test (ticket 61, spec 18.8 and 22.2, ADR D23), built by compose
# as mfs-mw/softhsm:local and by mw-crypto's Pkcs11ProviderIT from a copy in its test resources.
# The tokens are served over TCP as the p11-kit RPC protocol, one port per token (README.md):
# SoftHSM and the token files stay in this container. Never used outside dev and test.
FROM alpine:3.22@sha256:49340b346798875b5592e5d420241f2f9cfba5f47e62ebb1446a1b34734e898f

# softhsm: SoftHSMv2; opensc: pkcs11-tool (healthcheck, manual checks); p11-kit + p11-kit-server:
# `p11-kit remote` and the client proxy; socat: the TCP listeners and the healthcheck's client side.
# The package's own module file is removed so the p11-kit proxy only sees the remote modules.
RUN apk add --no-cache softhsm=2.6.1-r6 opensc=0.26.0-r0 p11-kit=0.26.2-r0 p11-kit-server=0.26.2-r0 \
      socat=1.8.1.3-r0 \
    && rm -f /usr/share/p11-kit/modules/softhsm2.module \
    && addgroup -g 10001 softhsm && adduser -D -H -u 10001 -G softhsm softhsm \
    && mkdir -p /var/lib/softhsm/tokens && chown 10001:10001 /var/lib/softhsm/tokens && chmod 0700 /var/lib/softhsm/tokens

COPY softhsm2.conf /etc/softhsm2.conf
COPY pkcs11-modules/ /etc/pkcs11/modules/
COPY init-tokens.sh serve.sh remote-token.sh healthcheck.sh /opt/softhsm/

USER 10001:10001
VOLUME /var/lib/softhsm/tokens
EXPOSE 5657 5658
HEALTHCHECK --interval=5s --timeout=5s --start-period=10s --start-interval=1s --retries=6 CMD ["/bin/sh", "/opt/softhsm/healthcheck.sh"]
ENTRYPOINT ["/bin/sh", "/opt/softhsm/serve.sh"]
