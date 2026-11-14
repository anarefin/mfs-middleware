package mfs.mw.crypto.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code mw.crypto.test-api}: the dev-only payShield test API (ticket 76; spec 18.8, ticket 64
 * acceptance): lets the host API suite exercise PVV generate and verify on the running stack. Off by
 * default and in every environment but dev (compose {@code MW_CRYPTO_TEST_API_ENABLED}, true only in
 * {@code deploy/dev.env.defaults}). When on, a plain-HTTP connector on {@code port} serves only
 * {@code /test/v1/payshield/**} (APISIX test-only route {@code /test/crypto/v1/*}), and every call runs
 * as the test-only caller {@code client}, which the dev profile alone binds to the TPK and PVK purposes
 * in {@code mw.crypto.authz.purposes}: the caller policy still decides.
 *
 * @param enabled register the connector, the controller and its port filter
 * @param port the plain test connector (8082; never published to the host)
 * @param client the caller id the test API acts as ({@code mw-e2e-crypto})
 */
@ConfigurationProperties("mw.crypto.test-api")
public record TestApiProperties(boolean enabled, int port, String client) {

  public TestApiProperties {
    port = port == 0 ? 8082 : port;
    client = client == null || client.isBlank() ? "mw-e2e-crypto" : client;
  }
}
