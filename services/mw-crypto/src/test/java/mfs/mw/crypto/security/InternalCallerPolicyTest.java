package mfs.mw.crypto.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.security.cert.X509Certificate;
import java.util.Set;
import javax.security.auth.x500.X500Principal;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.jwt.Jwt;

/** The caller rules: allow-list, azp equals the client certificate CN (mTLS), key-admin gate. */
class InternalCallerPolicyTest {

  static final Set<String> ALLOWED = Set.of("mw-admin-api", "mw-auth", "mw-audit-dw");
  static final Set<String> KEY_ADMIN = Set.of("mw-admin-api");

  static Jwt token(String azp) {
    return Jwt.withTokenValue("t").header("alg", "RS256").claim("azp", azp).build();
  }

  static MockHttpServletRequest withCert(String subject) {
    MockHttpServletRequest req = new MockHttpServletRequest();
    if (subject != null) {
      X509Certificate cert = mock(X509Certificate.class);
      when(cert.getSubjectX500Principal()).thenReturn(new X500Principal(subject));
      req.setAttribute(InternalCallerPolicy.CLIENT_CERTS, new X509Certificate[] {cert});
    }
    return req;
  }

  final InternalCallerPolicy mtls = new InternalCallerPolicy(ALLOWED, KEY_ADMIN, true, true);

  @Test
  void allowsAnAllowedClientWhoseCertificateCnIsItsAzp() {
    assertThat(mtls.allows(token("mw-auth"), withCert("CN=mw-auth"))).isTrue();
    assertThat(mtls.allows(token("mw-auth"), withCert("O=Bits, CN=mw-auth"))).isTrue();
  }

  @Test
  void refusesAClientNotOnTheAllowListEvenWithAMatchingCertificate() {
    assertThat(mtls.allows(token("mw-ussd"), withCert("CN=mw-ussd"))).isFalse();
    assertThat(mtls.allows(Jwt.withTokenValue("t").header("alg", "RS256").claim("sub", "x").build(),
        withCert("CN=mw-auth"))).isFalse();
  }

  @Test
  void refusesATokenThatIsNotTheCertificatesService() {
    assertThat(mtls.allows(token("mw-admin-api"), withCert("CN=mw-auth"))).isFalse();
    assertThat(mtls.allows(token("mw-admin-api"), withCert(null))).isFalse();
  }

  @Test
  void withoutMtlsOnlyTheTokenCounts() {
    InternalCallerPolicy plain = new InternalCallerPolicy(ALLOWED, KEY_ADMIN, false, true);
    assertThat(plain.allows(token("mw-auth"), withCert(null))).isTrue();
    assertThat(plain.allows(token("mw-ussd"), withCert(null))).isFalse();
  }

  @Test
  void onlyKeyAdminClientsGenerateAndRotate() {
    assertThat(mtls.allowsKeyAdmin(token("mw-admin-api"), withCert("CN=mw-admin-api"), true)).isTrue();
    assertThat(mtls.allowsKeyAdmin(token("mw-admin-api"), withCert("CN=mw-admin-api"), false)).isTrue();
    assertThat(mtls.allowsKeyAdmin(token("mw-auth"), withCert("CN=mw-auth"), true)).isFalse();
    assertThat(mtls.allowsKeyAdmin(token("mw-auth"), withCert("CN=mw-auth"), false)).isFalse();
    assertThat(mtls.allowsKeyAdmin(token("mw-admin-api"), withCert("CN=mw-auth"), false)).isFalse();
  }

  @Test
  void generationNeedsTheDevBootstrapFlagRotationDoesNot() {
    InternalCallerPolicy ceremonyOnly = new InternalCallerPolicy(ALLOWED, KEY_ADMIN, true, false);
    assertThat(ceremonyOnly.allowsKeyAdmin(token("mw-admin-api"), withCert("CN=mw-admin-api"), true)).isFalse();
    assertThat(ceremonyOnly.allowsKeyAdmin(token("mw-admin-api"), withCert("CN=mw-admin-api"), false)).isTrue();
  }
}
