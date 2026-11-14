package mfs.mw.crypto.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.Signature;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PSSParameterSpec;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mfs.mw.crypto.dto.HttpComponent;
import mfs.mw.crypto.dto.HttpSignatureSignRequest;
import mfs.mw.crypto.dto.HttpSignatureSignResponse;
import mfs.mw.crypto.dto.HttpSignatureVerifyRequest;
import mfs.mw.crypto.dto.SignatureCheck;
import mfs.mw.crypto.entity.KeyState;
import mfs.mw.crypto.exception.MwException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * RFC 9421 HTTP message signatures (ticket 65, spec 18.8) above the provider SPI: the RFC's own
 * hmac-sha256 example as a known answer, ES256 / RSA-PSS / RSA v1.5 / HMAC round trips checked by the
 * JDK over a signature base written out by hand, tampering, the time window and bad input.
 */
class HttpSignatureServiceTest {

  /** RFC 9421 B.1.5. */
  static final String RFC_SECRET =
      "uzvJfB4u3N0Jy4T7NZ75MDVcr8zSTInedJtkgcu46YW4XByzNJjxBdtjUkdJPBtbmHhIDi6pcl8jsasjlTMtDQ==";
  /** T0 = 2026-11-14T08:00:00Z. */
  static final long NOW = OperationFixture.T0.getEpochSecond();
  static final String DIGEST = "sha-256=:X48E9qOokqqrvdts8nOJRJN3OWDUoyWxBf7kbu9DBPE=:";

  OperationFixture f;
  HttpSignatureService http;

  @BeforeEach
  void setUp() {
    f = new OperationFixture()
        .key("http-sign-ec", 1, "HTTP_SIGN_EC", KeyState.ACTIVE)
        .key("http-sign-rsa", 1, "HTTP_SIGN_RSA", KeyState.VERIFY_ONLY)
        .key("http-sign-rsa", 2, "HTTP_SIGN_RSA", KeyState.ACTIVE)
        .key("hmac-partner", 1, "HMAC_PARTNER", KeyState.ACTIVE)
        .key("hmac-rfc", 1, "HMAC_RFC", KeyState.ACTIVE)
        .key("jwt-sign", 1, "JWT_SIGN", KeyState.ACTIVE)
        .key("pin-hmac", 1, "PIN_HMAC", KeyState.ACTIVE);
    f.hsm.putHmacKey("hmac-rfc.v1", Base64.getDecoder().decode(RFC_SECRET));
    http = new HttpSignatureService(f.keys, f.callers, Clock.fixed(OperationFixture.T0, ZoneOffset.UTC));
  }

  static String code(Throwable e) {
    return ((MwException) e).code();
  }

  static HttpComponent c(String name, String value) {
    return new HttpComponent(name, null, value);
  }

  static List<HttpComponent> request() {
    return List.of(c("@method", "POST"), c("@authority", "bank.example"), c("@path", "/npsb/v1/transfer"),
        c("content-digest", DIGEST));
  }

  static HttpSignatureSignRequest sign(String keyId, String alg) {
    return new HttpSignatureSignRequest(keyId, alg, null, request(), null, null, null, null);
  }

  static HttpSignatureVerifyRequest verify(String keyId, HttpSignatureSignResponse s, List<HttpComponent> components) {
    return new HttpSignatureVerifyRequest(keyId, null, null, null, s.signatureInput(), s.signature(), components,
        null, null, null);
  }

  static byte[] signatureBytes(HttpSignatureSignResponse s) {
    String v = s.signature();
    return Base64.getDecoder().decode(v.substring(v.indexOf(":") + 1, v.length() - 1));
  }

  /** The signature base of {@link #request()} as RFC 9421 2.5 lays it out, written by hand. */
  static String base(String kid, String alg) {
    return "\"@method\": POST\n\"@authority\": bank.example\n\"@path\": /npsb/v1/transfer\n\"content-digest\": " + DIGEST
        + "\n\"@signature-params\": (\"@method\" \"@authority\" \"@path\" \"content-digest\");created=" + NOW
        + ";keyid=\"" + kid + "\";alg=\"" + alg + "\"";
  }

  @Test
  void theRfc9421HmacExampleVerifies() {
    List<HttpComponent> values = List.of(c("date", "Tue, 20 Apr 2021 02:07:55 GMT"), c("@authority", "example.com"),
        c("content-type", "application/json"));
    HttpSignatureVerifyRequest rfc = new HttpSignatureVerifyRequest("hmac-rfc", null, "hmac-sha256", "sig-b25",
        "sig-b25=(\"date\" \"@authority\" \"content-type\");created=1618884473;keyid=\"test-shared-secret\"",
        "sig-b25=:pxcQw6G3AjtMBQjwo8XzkZf/bws5LelbaMk5rGIGtE8=:", values, null, "test-shared-secret", null);
    assertThat(http.verify(rfc)).isEqualTo(SignatureCheck.valid("hmac-rfc.v1"));

    HttpSignatureVerifyRequest otherKeyid = new HttpSignatureVerifyRequest("hmac-rfc", null, "hmac-sha256", "sig-b25",
        rfc.signatureInput(), rfc.signature(), values, null, "another-key", null);
    assertThat(http.verify(otherKeyid)).isEqualTo(SignatureCheck.invalid("KEY_MISMATCH"));
    List<HttpComponent> changed = List.of(values.get(0), c("@authority", "evil.example"), values.get(2));
    assertThat(http.verify(new HttpSignatureVerifyRequest("hmac-rfc", null, "hmac-sha256", null, rfc.signatureInput(),
        rfc.signature(), changed, null, null, null))).isEqualTo(SignatureCheck.invalid("SIGNATURE_MISMATCH"));
  }

  @Test
  void es256SignsTheRfcSignatureBaseInTheHsm() throws Exception {
    HttpSignatureSignResponse s = http.sign(sign("http-sign-ec", null));
    assertThat(s.kid()).isEqualTo("http-sign-ec.v1");
    assertThat(s.alg()).isEqualTo("ecdsa-p256-sha256");
    assertThat(s.label()).isEqualTo("sig1");
    assertThat(s.signatureInput()).isEqualTo("sig1=(\"@method\" \"@authority\" \"@path\" \"content-digest\");created="
        + NOW + ";keyid=\"http-sign-ec.v1\";alg=\"ecdsa-p256-sha256\"");
    assertThat(s.signature()).startsWith("sig1=:").endsWith(":");
    byte[] sig = signatureBytes(s);
    assertThat(sig).hasSize(64);
    Signature jdk = Signature.getInstance("SHA256withECDSAinP1363Format");
    jdk.initVerify(f.hsm.publicKey("http-sign-ec.v1"));
    jdk.update(base("http-sign-ec.v1", "ecdsa-p256-sha256").getBytes(StandardCharsets.US_ASCII));
    assertThat(jdk.verify(sig)).isTrue();

    assertThat(http.verify(verify("http-sign-ec", s, request()))).isEqualTo(SignatureCheck.valid("http-sign-ec.v1"));
  }

  @Test
  void rsaPssSha512AndRsaV15RoundTrip() throws Exception {
    HttpSignatureSignResponse pss = http.sign(sign("http-sign-rsa", null));
    assertThat(pss.alg()).isEqualTo("rsa-pss-sha512");
    Signature jdk = Signature.getInstance("RSASSA-PSS");
    jdk.setParameter(new PSSParameterSpec("SHA-512", "MGF1", MGF1ParameterSpec.SHA512, 64, 1));
    jdk.initVerify(f.hsm.publicKey("http-sign-rsa.v2"));
    jdk.update(base("http-sign-rsa.v2", "rsa-pss-sha512").getBytes(StandardCharsets.US_ASCII));
    assertThat(jdk.verify(signatureBytes(pss))).isTrue();
    assertThat(http.verify(verify("http-sign-rsa", pss, request()))).isEqualTo(SignatureCheck.valid("http-sign-rsa.v2"));

    HttpSignatureSignResponse v15 = http.sign(sign("http-sign-rsa", "rsa-v1_5-sha256"));
    Signature rs = Signature.getInstance("SHA256withRSA");
    rs.initVerify(f.hsm.publicKey("http-sign-rsa.v2"));
    rs.update(base("http-sign-rsa.v2", "rsa-v1_5-sha256").getBytes(StandardCharsets.US_ASCII));
    assertThat(rs.verify(signatureBytes(v15))).isTrue();
    assertThat(http.verify(verify("http-sign-rsa", v15, request())).valid()).isTrue();
    // the alg parameter is signed: claiming the other RSA scheme fails
    HttpSignatureVerifyRequest asPss = new HttpSignatureVerifyRequest("http-sign-rsa", null, "rsa-pss-sha512", null,
        v15.signatureInput(), v15.signature(), request(), null, null, null);
    assertThat(http.verify(asPss)).isEqualTo(SignatureCheck.invalid("ALGORITHM_NOT_ALLOWED"));
    // the previous version still verifies its own signatures; the kid in keyid picks it
    assertThatThrownBy(() -> http.sign(sign("http-sign-rsa.v1", null)))
        .satisfies(e -> assertThat(code(e)).isEqualTo("MW-VAL-015"));
  }

  @Test
  void hmacRoundTripWithAnHmacUseKeyOnly() {
    HttpSignatureSignResponse s = http.sign(sign("hmac-partner", null));
    assertThat(s.alg()).isEqualTo("hmac-sha256");
    byte[] mac = f.hsm.hmac("hmac-partner.v1", List.of(base("hmac-partner.v1", "hmac-sha256").getBytes(StandardCharsets.US_ASCII))).getFirst();
    assertThat(signatureBytes(s)).isEqualTo(mac);
    assertThat(http.verify(verify("hmac-partner", s, request()))).isEqualTo(SignatureCheck.valid("hmac-partner.v1"));

    for (String other : List.of("pin-hmac", "jwt-sign")) {
      assertThatThrownBy(() -> http.sign(sign(other, null))).satisfies(e -> assertThat(code(e)).isEqualTo("MW-VAL-015"));
    }
    assertThatThrownBy(() -> http.sign(sign("hmac-partner", "ecdsa-p256-sha256")))
        .satisfies(e -> assertThat(code(e)).isEqualTo("MW-VAL-001"));
  }

  @Test
  void aChangedHeaderOrComponentListFailsVerify() {
    HttpSignatureSignResponse s = http.sign(sign("http-sign-ec", null));
    List<HttpComponent> changed = List.of(c("@method", "POST"), c("@authority", "bank.example"),
        c("@path", "/npsb/v1/transfer"), c("content-digest", "sha-256=:AAAA:"));
    assertThat(http.verify(verify("http-sign-ec", s, changed))).isEqualTo(SignatureCheck.invalid("SIGNATURE_MISMATCH"));
    HttpSignatureSignResponse dropped = new HttpSignatureSignResponse(s.label(), s.kid(), s.alg(),
        s.signatureInput().replace(" \"content-digest\"", ""), s.signature());
    assertThat(http.verify(verify("http-sign-ec", dropped, request()))).isEqualTo(SignatureCheck.invalid("SIGNATURE_MISMATCH"));
    assertThat(http.verify(verify("http-sign-ec", s, request().subList(0, 3))))
        .isEqualTo(SignatureCheck.invalid("COMPONENT_MISSING"));
    HttpSignatureVerifyRequest required = new HttpSignatureVerifyRequest("http-sign-ec", null, null, null,
        dropped.signatureInput(), dropped.signature(), request(), List.of("@method", "content-digest"), null, null);
    assertThat(http.verify(required)).isEqualTo(SignatureCheck.invalid("REQUIRED_COMPONENT_NOT_COVERED"));
    assertThat(http.verify(verify("http-sign-ec", new HttpSignatureSignResponse("sig1", null, null, "sig1=(", s.signature()),
        request()))).isEqualTo(SignatureCheck.invalid("MALFORMED_SIGNATURE"));
    assertThat(http.verify(new HttpSignatureVerifyRequest("http-sign-ec", null, null, "sig2", s.signatureInput(),
        s.signature(), request(), null, null, null))).isEqualTo(SignatureCheck.invalid("MALFORMED_SIGNATURE"));
  }

  @Test
  void componentParametersAreSignedAndMatchedAsAComponent() {
    Map<String, Object> key = new LinkedHashMap<>();
    key.put("key", "Pet");
    HttpComponent dict = new HttpComponent("example-dict", key, "dog");
    HttpComponent query = new HttpComponent("@query-param", Map.of("name", "Pet"), "dog");
    HttpSignatureSignResponse s = http.sign(new HttpSignatureSignRequest("http-sign-ec", null, "partner",
        List.of(dict, query), NOW - 10, NOW + 300, "n-1", "npsb"));
    assertThat(s.signatureInput()).isEqualTo("partner=(\"example-dict\";key=\"Pet\" \"@query-param\";name=\"Pet\");created="
        + (NOW - 10) + ";expires=" + (NOW + 300) + ";nonce=\"n-1\";keyid=\"http-sign-ec.v1\";alg=\"ecdsa-p256-sha256\";tag=\"npsb\"");
    assertThat(http.verify(verify("http-sign-ec", s, List.of(query, dict))).valid()).isTrue();
    HttpComponent otherKey = new HttpComponent("example-dict", Map.of("key", "Cat"), "dog");
    assertThat(http.verify(verify("http-sign-ec", s, List.of(query, otherKey))))
        .isEqualTo(SignatureCheck.invalid("COMPONENT_MISSING"));
  }

  @Test
  void theTimeWindowIsChecked() {
    HttpSignatureSignResponse old = http.sign(new HttpSignatureSignRequest("http-sign-ec", null, null, request(),
        NOW - 600, NOW - 1, null, null));
    assertThat(http.verify(verify("http-sign-ec", old, request()))).isEqualTo(SignatureCheck.invalid("EXPIRED"));
    HttpSignatureSignResponse aged = http.sign(new HttpSignatureSignRequest("http-sign-ec", null, null, request(),
        NOW - 600, null, null, null));
    assertThat(http.verify(new HttpSignatureVerifyRequest("http-sign-ec", null, null, null, aged.signatureInput(),
        aged.signature(), request(), null, null, 300L))).isEqualTo(SignatureCheck.invalid("TOO_OLD"));
    assertThat(http.verify(verify("http-sign-ec", aged, request())).valid()).isTrue();
    HttpSignatureSignResponse future = http.sign(new HttpSignatureSignRequest("http-sign-ec", null, null, request(),
        NOW + 600, null, null, null));
    assertThat(http.verify(new HttpSignatureVerifyRequest("http-sign-ec", null, null, null, future.signatureInput(),
        future.signature(), request(), null, null, 300L))).isEqualTo(SignatureCheck.invalid("CREATED_IN_FUTURE"));
    HttpSignatureVerifyRequest noCreated = new HttpSignatureVerifyRequest("hmac-rfc", null, null, "s",
        "s=(\"@method\");keyid=\"x\"", "s=:AAAA:", request(), null, null, 300L);
    assertThat(http.verify(noCreated)).isEqualTo(SignatureCheck.invalid("CREATED_MISSING"));
    assertThatThrownBy(() -> http.sign(new HttpSignatureSignRequest("http-sign-ec", null, null, request(), NOW, NOW, null, null)))
        .satisfies(e -> assertThat(code(e)).isEqualTo("MW-VAL-001"));
  }

  @Test
  void verifiesWithAPartnerPublicKeyInPem() throws Exception {
    HttpSignatureSignResponse s = http.sign(sign("http-sign-ec", null));
    String pem = XmlDsigServiceTest.pem(((KeyPair) f.hsm.key("http-sign-ec.v1")).getPublic());
    HttpSignatureVerifyRequest byPem = new HttpSignatureVerifyRequest(null, pem, null, null, s.signatureInput(),
        s.signature(), request(), null, null, null);
    assertThat(http.verify(byPem)).isEqualTo(SignatureCheck.valid(null));
    assertThatThrownBy(() -> http.verify(new HttpSignatureVerifyRequest("http-sign-ec", pem, null, null,
        s.signatureInput(), s.signature(), request(), null, null, null)))
        .satisfies(e -> assertThat(code(e)).isEqualTo("MW-VAL-001"));
  }

  @Test
  void badComponentsAreRefusedBeforeAnyHsmCall() {
    for (List<HttpComponent> bad : List.of(
        List.of(c("@method", "POST\n\"@path\": /admin")),
        List.of(c("@method", "POST"), c("@method", "GET")),
        List.of(c("@signature-params", "x")),
        List.of(new HttpComponent("x", Map.of("unknown", true), "v")),
        List.of(new HttpComponent("x", Map.of("key", "a\"b"), "v")),
        List.<HttpComponent>of())) {
      assertThatThrownBy(() -> http.sign(new HttpSignatureSignRequest("http-sign-ec", null, null, bad, null, null, null, null)))
          .as(bad.toString())
          .satisfies(e -> assertThat(code(e)).isEqualTo("MW-VAL-001"));
    }
    assertThat(f.meters.find("crypto.op.duration").timers()).isEmpty();
  }
}
