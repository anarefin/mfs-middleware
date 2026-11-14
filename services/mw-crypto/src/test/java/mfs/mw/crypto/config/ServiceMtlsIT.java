package mfs.mw.crypto.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.http.HttpResponse;
import mfs.mw.crypto.HsmStack;
import mfs.mw.crypto.security.InternalTokens;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Mandatory mTLS on mw-crypto (spec 18.2, D24, ticket 66) in its own image ({@link HsmStack}): the
 * service issued its certificate from the OpenBao intermediate at start; its API port refuses a
 * handshake without a client certificate, refuses a certificate of a role not on the allow-list and
 * a token of another client than the certificate, and answers the service certificate plus its own
 * JWT. The expiry metric is on the plain management port.
 */
class ServiceMtlsIT {

  @BeforeAll
  static void stack() {
    HsmStack.start();
  }

  @Test
  void answersAnAllowedServiceWithItsCertificateAndJwt() {
    HttpResponse<String> r = HsmStack.getAs("/internal/v1/keys", InternalTokens.forClient("mw-admin-api"), "mw-admin-api");
    assertThat(r.statusCode()).isEqualTo(200);
    assertThat(HsmStack.getAs("/internal/v1/keys", InternalTokens.forClient("mw-auth"), "mw-auth").statusCode())
        .isEqualTo(200);
  }

  @Test
  void failsTheHandshakeWithoutAClientCertificate() {
    assertThatThrownBy(() -> HsmStack.getAs("/internal/v1/keys", InternalTokens.forClient("mw-admin-api"), null))
        .isInstanceOf(IllegalStateException.class)
        .hasCauseInstanceOf(java.io.IOException.class);
  }

  @Test
  void refusesARoleNotOnTheAllowList() {
    HttpResponse<String> r = HsmStack.getAs("/internal/v1/keys", InternalTokens.forClient("mw-ussd"), "mw-ussd");
    assertThat(r.statusCode()).isEqualTo(403);
    assertThat(r.body()).contains("MW-AUTH-002");
  }

  @Test
  void refusesATokenThatIsNotTheCertificatesService() {
    HttpResponse<String> r = HsmStack.getAs("/internal/v1/keys", InternalTokens.forClient("mw-admin-api"), "mw-ussd");
    assertThat(r.statusCode()).isEqualTo(403);
    assertThat(r.body()).contains("MW-AUTH-002");
    // a certificate alone is not enough either: the JWT stays required
    assertThat(HsmStack.getAs("/internal/v1/keys", null, "mw-admin-api").statusCode()).isEqualTo(401);
  }

  @Test
  void reportsTheCertificateExpiryOnTheManagementPort() {
    String metrics = HsmStack.get("/actuator/prometheus", null).body();
    java.util.regex.Matcher m = java.util.regex.Pattern
        .compile("tls_certificate_expiry_seconds\\{[^}]*service=\"mw-crypto\"[^}]*} ([0-9.E]+)").matcher(metrics);
    assertThat(m.find()).as("expiry gauge in %s", metrics).isTrue();
    // a 72 h certificate (the role's max), issued at start
    assertThat(Double.parseDouble(m.group(1))).isBetween(250_000.0, 259_200.0);
  }
}
