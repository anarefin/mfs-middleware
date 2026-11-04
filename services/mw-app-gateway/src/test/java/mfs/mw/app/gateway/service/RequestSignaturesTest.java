package mfs.mw.app.gateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import mfs.mw.app.gateway.dto.SignedRequest;
import mfs.mw.app.gateway.exception.MwException;
import org.junit.jupiter.api.Test;

/**
 * The device request signature (spec 9.1, 18.6): ECDSA P-256 over method, path, body hash,
 * timestamp, nonce and device id, accepted within ±5 minutes of the gateway's clock.
 */
class RequestSignaturesTest {

  static final Instant NOW = Instant.parse("2026-11-04T10:00:00Z");
  static final String BODY = "{\"txnType\":\"P2P\",\"amount\":{\"valueMinor\":1000}}";
  static final String DEVICE = "device-0001";
  static final String NONCE = "nonce-0123456789abcdef";

  private final RequestSignatures signatures = new RequestSignatures(Duration.ofMinutes(5));

  static KeyPair keyPair() throws Exception {
    KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
    g.initialize(new ECGenParameterSpec("secp256r1"));
    return g.generateKeyPair();
  }

  /** What the app does: hash the body, build the canonical text, sign it with the device key. */
  static SignedRequest signed(KeyPair key, String method, String path, String body, Instant ts) throws Exception {
    String bodyHash = RequestSignatures.sha256Hex(body.getBytes(StandardCharsets.UTF_8));
    String canonical = String.join("\n", method, path, bodyHash, Long.toString(ts.toEpochMilli()), NONCE, DEVICE);
    Signature s = Signature.getInstance("SHA256withECDSA");
    s.initSign(key.getPrivate());
    s.update(canonical.getBytes(StandardCharsets.UTF_8));
    String sig = Base64.getEncoder().encodeToString(s.sign());
    return new SignedRequest(DEVICE, method, path, bodyHash, Long.toString(ts.toEpochMilli()), NONCE, sig);
  }

  static PublicKey parse(KeyPair key) {
    return RequestSignatures.parsePublicKey(Base64.getEncoder().encodeToString(key.getPublic().getEncoded()));
  }

  @Test
  void aValidSignatureIsAccepted() throws Exception {
    KeyPair key = keyPair();
    SignedRequest req = signed(key, "POST", "/app/v1/transactions", BODY, NOW.minusSeconds(30));
    assertThatCode(() -> signatures.verify(parse(key), req, NOW)).doesNotThrowAnyException();
  }

  @Test
  void aSignatureInP1363FormIsAcceptedToo() throws Exception {
    KeyPair key = keyPair();
    SignedRequest der = signed(key, "POST", "/app/v1/transactions", BODY, NOW);
    String canonical = RequestSignatures.canonical(der);
    Signature s = Signature.getInstance("SHA256withECDSAinP1363Format");
    s.initSign(key.getPrivate());
    s.update(canonical.getBytes(StandardCharsets.UTF_8));
    SignedRequest raw =
        new SignedRequest(DEVICE, der.method(), der.path(), der.bodySha256(), der.timestamp(), NONCE,
            Base64.getEncoder().encodeToString(s.sign()));
    assertThatCode(() -> signatures.verify(parse(key), raw, NOW)).doesNotThrowAnyException();
  }

  @Test
  void aTamperedBodyIsRejected() throws Exception {
    KeyPair key = keyPair();
    SignedRequest req = signed(key, "POST", "/app/v1/transactions", BODY, NOW);
    SignedRequest tampered =
        new SignedRequest(DEVICE, req.method(), req.path(),
            RequestSignatures.sha256Hex(BODY.replace("1000", "9000").getBytes(StandardCharsets.UTF_8)),
            req.timestamp(), NONCE, req.signature());
    assertThatThrownBy(() -> signatures.verify(parse(key), tampered, NOW))
        .isInstanceOfSatisfying(MwException.class, e -> {
          assertThat(e.code()).isEqualTo("MW-AUTH-004");
          assertThat(e.status().value()).isEqualTo(401);
        });
  }

  @Test
  void aTamperedPathOrMethodIsRejected() throws Exception {
    KeyPair key = keyPair();
    SignedRequest req = signed(key, "POST", "/app/v1/transactions", BODY, NOW);
    SignedRequest otherPath =
        new SignedRequest(DEVICE, "POST", "/app/v1/sessions", req.bodySha256(), req.timestamp(), NONCE, req.signature());
    assertThatThrownBy(() -> signatures.verify(parse(key), otherPath, NOW))
        .isInstanceOfSatisfying(MwException.class, e -> assertThat(e.code()).isEqualTo("MW-AUTH-004"));
  }

  @Test
  void aSignatureByAnotherKeyIsRejected() throws Exception {
    SignedRequest req = signed(keyPair(), "POST", "/app/v1/transactions", BODY, NOW);
    assertThatThrownBy(() -> signatures.verify(parse(keyPair()), req, NOW))
        .isInstanceOfSatisfying(MwException.class, e -> assertThat(e.code()).isEqualTo("MW-AUTH-004"));
  }

  @Test
  void aGarbageSignatureIsRejected() throws Exception {
    KeyPair key = keyPair();
    SignedRequest req = signed(key, "POST", "/app/v1/transactions", BODY, NOW);
    for (String bad : new String[] {"not base64!", "AAAA", ""}) {
      SignedRequest garbage =
          new SignedRequest(DEVICE, req.method(), req.path(), req.bodySha256(), req.timestamp(), NONCE, bad);
      assertThatThrownBy(() -> signatures.verify(parse(key), garbage, NOW))
          .as(bad)
          .isInstanceOfSatisfying(MwException.class, e -> assertThat(e.code()).isEqualTo("MW-AUTH-004"));
    }
  }

  @Test
  void aStaleOrFutureTimestampIsRejected() throws Exception {
    KeyPair key = keyPair();
    for (Instant ts : new Instant[] {NOW.minusSeconds(301), NOW.plusSeconds(301)}) {
      SignedRequest req = signed(key, "POST", "/app/v1/transactions", BODY, ts);
      assertThatThrownBy(() -> signatures.verify(parse(key), req, NOW))
          .as(ts.toString())
          .isInstanceOfSatisfying(MwException.class, e -> {
            assertThat(e.code()).isEqualTo("MW-AUTH-005");
            assertThat(e.status().value()).isEqualTo(401);
          });
    }
  }

  @Test
  void theEdgesOfTheWindowAreAccepted() throws Exception {
    KeyPair key = keyPair();
    for (Instant ts : new Instant[] {NOW.minusSeconds(300), NOW.plusSeconds(300)}) {
      SignedRequest req = signed(key, "POST", "/app/v1/transactions", BODY, ts);
      assertThatCode(() -> signatures.verify(parse(key), req, NOW)).doesNotThrowAnyException();
    }
  }

  @Test
  void aTimestampThatIsNotEpochMillisIsRejected() throws Exception {
    KeyPair key = keyPair();
    SignedRequest req = signed(key, "POST", "/app/v1/transactions", BODY, NOW);
    SignedRequest bad =
        new SignedRequest(DEVICE, req.method(), req.path(), req.bodySha256(), "2026-11-04T10:00:00Z", NONCE, req.signature());
    assertThatThrownBy(() -> signatures.verify(parse(key), bad, NOW))
        .isInstanceOfSatisfying(MwException.class, e -> assertThat(e.code()).isEqualTo("MW-AUTH-005"));
  }

  @Test
  void onlyP256PublicKeysAreAccepted() throws Exception {
    KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
    g.initialize(new ECGenParameterSpec("secp384r1"));
    String p384 = Base64.getEncoder().encodeToString(g.generateKeyPair().getPublic().getEncoded());
    KeyPairGenerator rsa = KeyPairGenerator.getInstance("RSA");
    rsa.initialize(2048);
    String rsaKey = Base64.getEncoder().encodeToString(rsa.generateKeyPair().getPublic().getEncoded());
    for (String bad : new String[] {p384, rsaKey, "bm90IGEga2V5", "%%%"}) {
      assertThatThrownBy(() -> RequestSignatures.parsePublicKey(bad))
          .isInstanceOfSatisfying(MwException.class, e -> assertThat(e.code()).isEqualTo("MW-VAL-001"));
    }
  }

  @Test
  void theCanonicalTextIsTheDocumentedLines() {
    SignedRequest req =
        new SignedRequest("dev-1", "POST", "/app/v1/transactions", "ab12", "1762250400000", "n-1", "sig");
    assertThat(RequestSignatures.canonical(req))
        .isEqualTo("POST\n/app/v1/transactions\nab12\n1762250400000\nn-1\ndev-1");
    // SHA-256 of the empty body (a known value, FIPS 180-2 test vector)
    assertThat(RequestSignatures.sha256Hex(new byte[0]))
        .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
  }
}
