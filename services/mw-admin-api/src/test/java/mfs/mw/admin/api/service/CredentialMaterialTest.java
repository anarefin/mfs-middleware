package mfs.mw.admin.api.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import mfs.mw.admin.api.dto.CredentialChangeBody;
import mfs.mw.admin.api.dto.CredentialChangeBody.CredentialAction;
import mfs.mw.admin.api.dto.CredentialType;
import mfs.mw.admin.api.dto.ErrorDetail;
import org.junit.jupiter.api.Test;

/**
 * An uploaded credential is checked and turned into the fields of the partner's Vault secret
 * (ticket 131): a secret as one field, a client certificate as the PKCS#12 key store and its
 * password the adapters' mTLS contexts read ({@code <name>-keystore}, {@code <name>-keystore-password}).
 * Only metadata comes out besides the fields; no problem message repeats what was uploaded.
 */
class CredentialMaterialTest {

  static final Instant NOW = Instant.now();
  static final String SECRET = "fake-hmac-secret-0123456789abcdef";
  final SecureRandom random = new SecureRandom();

  static CredentialChangeBody secret(CredentialType type, String value, Instant notAfter) {
    return new CredentialChangeBody(CredentialAction.UPLOAD, "rotate", type, value, null, null, null, null, notAfter);
  }

  static CredentialChangeBody pem(String cert, String key) {
    return new CredentialChangeBody(CredentialAction.UPLOAD, "rotate", CredentialType.CLIENT_CERT, null, cert, key, null, null, null);
  }

  static CredentialChangeBody pkcs12(String store, String password) {
    return new CredentialChangeBody(CredentialAction.UPLOAD, "rotate", CredentialType.CLIENT_CERT, null, null, null, store, password, null);
  }

  CredentialMaterial.Prepared prepare(String name, CredentialChangeBody body, List<ErrorDetail> problems) {
    return CredentialMaterial.prepare(name, body, NOW, random, problems);
  }

  @Test
  void aSecretIsOneFieldNamedAfterTheCredentialWithItsOptionalExpiry() {
    List<ErrorDetail> problems = new ArrayList<>();
    Instant expiry = NOW.plus(Duration.ofDays(90));
    CredentialMaterial.Prepared p = prepare("hmac-secret", secret(CredentialType.HMAC_SECRET, SECRET, expiry), problems);
    assertThat(problems).isEmpty();
    assertThat(p.fields()).containsExactly(java.util.Map.entry("hmac-secret", SECRET));
    assertThat(p.meta().notAfter()).isEqualTo(expiry);
    assertThat(p.meta().fingerprint()).isNull();
    assertThat(p.meta().subject()).isNull();
    assertThat(p.toString()).doesNotContain(SECRET);
  }

  @Test
  void aSecretMustBeLongEnoughAndCannotCarryCertificateFieldsOrAPastExpiry() {
    List<ErrorDetail> problems = new ArrayList<>();
    prepare("api-key", secret(CredentialType.API_KEY, "short", NOW.minusSeconds(1)), problems);
    assertThat(problems).extracting(ErrorDetail::field).containsExactlyInAnyOrder("secret", "notAfter");
    problems.clear();
    prepare("api-key", new CredentialChangeBody(CredentialAction.UPLOAD, "r", CredentialType.API_KEY, SECRET,
        "-----BEGIN CERTIFICATE-----", null, null, null, null), problems);
    assertThat(problems).extracting(ErrorDetail::field).containsExactly("certificatePem");
    assertThat(problems.toString()).doesNotContain("short").doesNotContain(SECRET);
  }

  @Test
  void aPemCertificateAndKeyBecomeAPkcs12KeyStoreWithAFreshPasswordAndCertificateMetadata() throws Exception {
    TestCertificates.Generated g = TestCertificates.generate("billagg-client", "EC", 30);
    List<ErrorDetail> problems = new ArrayList<>();
    CredentialMaterial.Prepared p = prepare("billagg-mtls", pem(g.certificatePem(), g.privateKeyPem()), problems);
    assertThat(problems).isEmpty();
    assertThat(p.fields()).containsOnlyKeys("billagg-mtls-keystore", "billagg-mtls-keystore-password");
    String password = p.fields().get("billagg-mtls-keystore-password");
    assertThat(password).hasSizeGreaterThanOrEqualTo(32);
    KeyStore store = KeyStore.getInstance("PKCS12");
    store.load(new ByteArrayInputStream(Base64.getDecoder().decode(p.fields().get("billagg-mtls-keystore"))),
        password.toCharArray());
    String alias = store.aliases().nextElement();
    assertThat(store.isKeyEntry(alias)).isTrue();
    X509Certificate cert = (X509Certificate) store.getCertificate(alias);
    assertThat(p.meta().subject()).isEqualTo(cert.getSubjectX500Principal().getName()).contains("CN=billagg-client");
    assertThat(p.meta().notAfter()).isEqualTo(cert.getNotAfter().toInstant());
    assertThat(p.meta().fingerprint()).matches("^sha256:[0-9a-f]{64}$");
    assertThat(p.meta().serialNumber()).isEqualTo(cert.getSerialNumber().toString(16));
    assertThat(p.toString()).doesNotContain(password).doesNotContain(p.fields().get("billagg-mtls-keystore"));
  }

  @Test
  void anRsaPkcs12StoreIsKeptAsUploadedWithItsPassword() {
    TestCertificates.Generated g = TestCertificates.generate("bank-client", "RSA", 7);
    List<ErrorDetail> problems = new ArrayList<>();
    CredentialMaterial.Prepared p = prepare("c2w-mtls", pkcs12(g.pkcs12(), TestCertificates.PASSWORD), problems);
    assertThat(problems).isEmpty();
    assertThat(p.fields()).containsEntry("c2w-mtls-keystore", g.pkcs12())
        .containsEntry("c2w-mtls-keystore-password", TestCertificates.PASSWORD);
    assertThat(p.meta().subject()).contains("CN=bank-client");
  }

  @Test
  void aKeyOfAnotherCertificateAWrongPasswordGarbageAndAnExpiredCertificateAreRefused() {
    TestCertificates.Generated a = TestCertificates.generate("a", "EC", 30);
    TestCertificates.Generated b = TestCertificates.generate("b", "EC", 30);
    List<ErrorDetail> problems = new ArrayList<>();
    prepare("mtls", pem(a.certificatePem(), b.privateKeyPem()), problems);
    assertThat(problems).extracting(ErrorDetail::field).containsExactly("privateKeyPem");
    problems.clear();
    prepare("mtls", pkcs12(a.pkcs12(), "not-the-password"), problems);
    assertThat(problems).extracting(ErrorDetail::field).containsExactly("pkcs12");
    problems.clear();
    prepare("mtls", pem("garbage", "more garbage"), problems);
    assertThat(problems).extracting(ErrorDetail::field).contains("certificatePem");
    problems.clear();
    prepare("mtls", new CredentialChangeBody(CredentialAction.UPLOAD, "r", CredentialType.CLIENT_CERT, null,
        a.certificatePem(), a.privateKeyPem(), a.pkcs12(), TestCertificates.PASSWORD, null), problems);
    assertThat(problems).extracting(ErrorDetail::field).containsExactly("pkcs12");
    problems.clear();
    CredentialMaterial.prepare("mtls", pem(a.certificatePem(), a.privateKeyPem()), NOW.plus(Duration.ofDays(31)),
        random, problems);
    assertThat(problems).extracting(ErrorDetail::field).containsExactly("certificatePem");
    assertThat(problems.toString()).doesNotContain("PRIVATE KEY").doesNotContain("not-the-password");
  }

  @Test
  void aCertificateNameLeavesRoomForTheKeyStoreFieldSuffixes() {
    List<ErrorDetail> problems = new ArrayList<>();
    TestCertificates.Generated a = TestCertificates.generate("a", "EC", 30);
    prepare("x".repeat(60), pem(a.certificatePem(), a.privateKeyPem()), problems);
    assertThat(problems).extracting(ErrorDetail::field).containsExactly("name");
  }
}
