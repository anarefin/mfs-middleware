package mfs.mw.crypto.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import mfs.mw.crypto.HsmStack;
import mfs.mw.crypto.security.InternalTokens;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every operation of ticket 63 against the SoftHSM token, over the API of mw-crypto's own image
 * ({@link HsmStack}): PIN derive and verify (match, no match, weak PIN, bad blocks, rotation of the
 * transport and PIN HMAC keys with the re-hash), JWS RS256/PS256/ES256 checked against the JWKS, HMAC,
 * tokenisation, envelope encryption with KEK rotation and tamper checks. Keys of its own keyIds are
 * created here (the bootstrap keys of the PIN, HMAC and KEK purposes are never rotated, so the other
 * HSM ITs see them as the bootstrap left them). Finally no request or answer value reached a log line.
 */
class OperationsIT {

  static final JsonMapper JSON = JsonMapper.builder().build();
  static final String WALLET = "01712345678";
  static final SecureRandom RANDOM = new SecureRandom();
  /** Every secret value sent or received; none may appear in the container log. */
  static final List<String> SECRETS = new ArrayList<>();

  @BeforeAll
  static void keys() throws Exception {
    HsmStack.start();
    for (String[] k : new String[][] {
        {"PIN_TRANSPORT", "it-pin-transport"}, {"PIN_HMAC", "it-pin-hmac"}, {"HMAC_IT", "hmac-it"}, {"KEK_IT", "kek-it"}}) {
      HttpResponse<String> r = admin("/internal/v1/keys", "{\"purpose\":\"" + k[0] + "\",\"keyId\":\"" + k[1] + "\"}");
      assertThat(r.statusCode()).as(r.body()).isIn(201, 409);
    }
    // the bootstrap keys the JWS and token tests use
    for (int i = 0; i < 120; i++) {
      String body = HsmStack.get("/internal/v1/keys", token()).body();
      if (body.contains("\"jwt-sign\"") && body.contains("\"assertion-sign\"") && body.contains("\"tokenise-msisdn\"")) {
        return;
      }
      Thread.sleep(500);
    }
    throw new AssertionError("bootstrap keys never appeared");
  }

  @AfterAll
  static void nothingSecretReachedTheLog() {
    String logs = HsmStack.start().getLogs();
    assertThat(SECRETS).isNotEmpty();
    for (String s : SECRETS) {
      assertThat(logs).as("a request or answer value in the mw-crypto log").doesNotContain(s);
    }
    assertThat(logs).doesNotContain(WALLET);
  }

  static String token() {
    return InternalTokens.forClient("mw-auth");
  }

  static HttpResponse<String> post(String path, String json) {
    return HsmStack.post(path, json, token());
  }

  /** Generate and rotate need the key-admin client (ticket 66). */
  static HttpResponse<String> admin(String path, String json) {
    return HsmStack.post(path, json, InternalTokens.forClient("mw-admin-api"));
  }

  static JsonNode ok(HttpResponse<String> r) {
    assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
    return JSON.readTree(r.body());
  }

  static void error(HttpResponse<String> r, int status, String code) {
    assertThat(r.statusCode()).as(r.body()).isEqualTo(status);
    assertThat(JSON.readTree(r.body()).get("code").asString()).isEqualTo(code);
  }

  static String secret(String value) {
    SECRETS.add(value);
    return value;
  }

  static String b64(byte[] b) {
    return Base64.getEncoder().encodeToString(b);
  }

  static PublicKey transportKey(String keyId, int index) throws Exception {
    JsonNode pub = ok(HsmStack.get("/internal/v1/keys/" + keyId + "/public", token()));
    String pem = pub.get("versions").get(index).get("pem").asString()
        .replace("-----BEGIN PUBLIC KEY-----", "").replace("-----END PUBLIC KEY-----", "").replaceAll("\\s", "");
    return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(pem)));
  }

  static String block(String keyRef, PublicKey key, byte[] clear) throws Exception {
    Cipher c = Cipher.getInstance("RSA/ECB/OAEPPadding");
    c.init(Cipher.ENCRYPT_MODE, key,
        new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT));
    String ct = secret(b64(c.doFinal(clear)));
    return "{\"keyId\":\"" + keyRef + "\",\"ciphertext\":\"" + ct + "\"}";
  }

  static String verify(String block, String stored, String kid) {
    return "{\"walletId\":\"" + WALLET + "\",\"encryptedPin\":" + block + ",\"storedHmac\":\"" + stored
        + "\",\"hmacKeyId\":\"" + kid + "\"}";
  }

  @Test
  void pinDeriveVerifyWeakBadBlocksAndRotationWithRehash() throws Exception {
    PublicKey transportV1 = transportKey("it-pin-transport", 0);
    byte[] pin = "40973".getBytes(StandardCharsets.US_ASCII);

    JsonNode derived = ok(post("/internal/v1/pin/derive", "{\"walletId\":\"" + WALLET + "\",\"encryptedPin\":"
        + block("it-pin-transport", transportV1, pin) + ",\"hmacKeyId\":\"it-pin-hmac\"}"));
    assertThat(derived.get("weak").asBoolean()).isFalse();
    String h1 = secret(derived.get("hmac").asString());
    assertThat(Base64.getDecoder().decode(h1)).hasSize(32);
    assertThat(derived.get("hmacKeyId").asString()).isEqualTo("it-pin-hmac.v1");

    // deterministic: the same PIN derives the same value (a fresh OAEP block each time)
    JsonNode again = ok(post("/internal/v1/pin/derive", "{\"walletId\":\"" + WALLET + "\",\"encryptedPin\":"
        + block("it-pin-transport", transportV1, pin) + ",\"hmacKeyId\":\"it-pin-hmac\"}"));
    assertThat(again.get("hmac").asString()).isEqualTo(h1);

    JsonNode match = ok(post("/internal/v1/pin/verify", verify(block("it-pin-transport", transportV1, pin), h1, "it-pin-hmac.v1")));
    assertThat(match.get("match").asBoolean()).isTrue();
    assertThat(match.has("rehash")).isFalse();
    JsonNode wrong = ok(post("/internal/v1/pin/verify",
        verify(block("it-pin-transport", transportV1, "40974".getBytes(StandardCharsets.US_ASCII)), h1, "it-pin-hmac.v1")));
    assertThat(wrong.get("match").asBoolean()).isFalse();

    JsonNode weak = ok(post("/internal/v1/pin/derive", "{\"walletId\":\"" + WALLET + "\",\"encryptedPin\":"
        + block("it-pin-transport", transportV1, "12345".getBytes(StandardCharsets.US_ASCII))
        + ",\"hmacKeyId\":\"it-pin-hmac\",\"policyInput\":{\"lastHmac\":\"" + h1 + "\",\"lastHmacKeyId\":\"it-pin-hmac.v1\"}}"));
    assertThat(weak.get("weak").asBoolean()).isTrue();
    assertThat(weak.get("rule").asString()).isEqualTo("SEQUENCE");
    assertThat(weak.has("hmac")).isFalse();
    JsonNode last = ok(post("/internal/v1/pin/derive", "{\"walletId\":\"" + WALLET + "\",\"encryptedPin\":"
        + block("it-pin-transport", transportV1, pin)
        + ",\"hmacKeyId\":\"it-pin-hmac\",\"policyInput\":{\"lastHmac\":\"" + h1 + "\",\"lastHmacKeyId\":\"it-pin-hmac.v1\"}}"));
    assertThat(last.get("rule").asString()).isEqualTo("LAST_PIN");

    // bad blocks: not OAEP, wrong length, not 5 digits
    byte[] noise = new byte[384];
    RANDOM.nextBytes(noise);
    noise[0] = 0;
    error(post("/internal/v1/pin/verify",
        verify("{\"keyId\":\"it-pin-transport\",\"ciphertext\":\"" + secret(b64(noise)) + "\"}", h1, "it-pin-hmac.v1")),
        400, "MW-VAL-014");
    error(post("/internal/v1/pin/verify", verify("{\"keyId\":\"it-pin-transport\",\"ciphertext\":\"AAAAAAAAAAAAAA==\"}", h1,
        "it-pin-hmac.v1")), 400, "MW-VAL-014");
    error(post("/internal/v1/pin/verify",
        verify(block("it-pin-transport", transportV1, "4097".getBytes(StandardCharsets.US_ASCII)), h1, "it-pin-hmac.v1")),
        400, "MW-VAL-014");
    error(post("/internal/v1/pin/verify", verify(block("it-pin-transport", transportV1, pin), h1, "pin-transport.v1")),
        400, "MW-VAL-015");

    // rotate both keys: a block under the previous transport version still decrypts, the match under
    // the VERIFY_ONLY HMAC version carries the re-hash under the new ACTIVE one
    assertThat(admin("/internal/v1/keys/it-pin-hmac/rotate", "").statusCode()).isEqualTo(200);
    assertThat(admin("/internal/v1/keys/it-pin-transport/rotate", "").statusCode()).isEqualTo(200);
    JsonNode rotated = ok(post("/internal/v1/pin/verify", verify(block("it-pin-transport.v1", transportV1, pin), h1,
        "it-pin-hmac.v1")));
    assertThat(rotated.get("match").asBoolean()).isTrue();
    assertThat(rotated.get("rehash").get("hmacKeyId").asString()).isEqualTo("it-pin-hmac.v2");
    String h2 = secret(rotated.get("rehash").get("hmac").asString());
    assertThat(h2).isNotEqualTo(h1);
    PublicKey transportV2 = transportKey("it-pin-transport", 1);
    JsonNode underV2 = ok(post("/internal/v1/pin/verify", verify(block("it-pin-transport", transportV2, pin), h2, "it-pin-hmac.v2")));
    assertThat(underV2.get("match").asBoolean()).isTrue();
    assertThat(underV2.has("rehash")).isFalse();
    // new values use the ACTIVE version only
    JsonNode fresh = ok(post("/internal/v1/pin/derive", "{\"walletId\":\"" + WALLET + "\",\"encryptedPin\":"
        + block("it-pin-transport", transportV2, pin) + ",\"hmacKeyId\":\"it-pin-hmac\"}"));
    assertThat(fresh.get("hmac").asString()).isEqualTo(h2);
    assertThat(fresh.get("hmacKeyId").asString()).isEqualTo("it-pin-hmac.v2");
  }

  @Test
  void jwsSignaturesVerifyWithTheJwks() throws Exception {
    JWKSet rsa = JWKSet.parse(HsmStack.get("/internal/v1/jwks/JWT_SIGN", token()).body());
    for (String alg : List.of("RS256", "PS256")) {
      JsonNode signed = ok(post("/internal/v1/jws/sign",
          "{\"purpose\":\"JWT_SIGN\",\"alg\":\"" + alg + "\",\"header\":{\"typ\":\"at+jwt\"},\"payload\":{\"sub\":\"w-it\"}}"));
      String compact = secret(signed.get("jws").asString());
      JWSObject jws = JWSObject.parse(compact);
      assertThat(jws.getHeader().getAlgorithm().getName()).isEqualTo(alg);
      assertThat(jws.getHeader().getKeyID()).isEqualTo(signed.get("kid").asString()).startsWith("jwt-sign.v");
      assertThat(jws.verify(new RSASSAVerifier(rsa.getKeyByKeyId(jws.getHeader().getKeyID()).toRSAKey()))).isTrue();
    }
    JWKSet ec = JWKSet.parse(HsmStack.get("/internal/v1/jwks/ASSERTION_SIGN", token()).body());
    JsonNode es = ok(post("/internal/v1/jws/sign", "{\"keyId\":\"assertion-sign\",\"payload\":{\"txnHash\":\"abc\"}}"));
    JWSObject jws = JWSObject.parse(secret(es.get("jws").asString()));
    assertThat(jws.getHeader().getAlgorithm().getName()).isEqualTo("ES256");
    assertThat(jws.verify(new ECDSAVerifier(ec.getKeyByKeyId(jws.getHeader().getKeyID()).toECKey()))).isTrue();
  }

  @Test
  void hmacAndTokens() {
    String data = secret(b64("webhook-body-it".getBytes(StandardCharsets.UTF_8)));
    JsonNode signed = ok(post("/internal/v1/hmac/sign", "{\"keyId\":\"hmac-it\",\"data\":\"" + data + "\"}"));
    String mac = secret(signed.get("mac").asString());
    assertThat(ok(post("/internal/v1/hmac/verify", "{\"keyId\":\"hmac-it\",\"data\":\"" + data + "\",\"mac\":\"" + mac + "\"}"))
        .get("valid").asBoolean()).isTrue();
    String other = b64("webhook-body-iu".getBytes(StandardCharsets.UTF_8));
    assertThat(ok(post("/internal/v1/hmac/verify", "{\"keyId\":\"hmac-it\",\"data\":\"" + other + "\",\"mac\":\"" + mac + "\"}"))
        .get("valid").asBoolean()).isFalse();
    error(post("/internal/v1/hmac/sign", "{\"keyId\":\"pin-hmac\",\"data\":\"" + data + "\"}"), 400, "MW-VAL-015");

    String a = secret("01799887766");
    String b = secret("01899887766");
    JsonNode t1 = ok(post("/internal/v1/tokens", "{\"domain\":\"MSISDN\",\"values\":[\"" + a + "\",\"" + b + "\",\"" + a + "\"]}"));
    JsonNode t2 = ok(post("/internal/v1/tokens", "{\"domain\":\"MSISDN\",\"values\":[\"" + a + "\"]}"));
    assertThat(t1.get("tokens").get(0).asString()).matches("MSISDN_[A-Z2-7]{52}")
        .isEqualTo(t1.get("tokens").get(2).asString())
        .isEqualTo(t2.get("tokens").get(0).asString())
        .isNotEqualTo(t1.get("tokens").get(1).asString());
    assertThat(t1.get("kid").asString()).startsWith("tokenise-msisdn.v");
  }

  @Test
  void envelopeRoundTripTamperAndKekRotation() {
    String plain = secret(b64("1990123456789-it".getBytes(StandardCharsets.UTF_8)));
    String aad = b64("customer.nid".getBytes(StandardCharsets.UTF_8));
    JsonNode env = ok(post("/internal/v1/envelope/encrypt",
        "{\"kekId\":\"kek-it\",\"plaintext\":\"" + plain + "\",\"aad\":\"" + aad + "\"}"));
    String kid = env.get("kekId").asString();
    String wrapped = secret(env.get("wrappedKey").asString());
    String ct = secret(env.get("ciphertext").asString());
    assertThat(ct).isNotEqualTo(plain);
    String body = JSON.writeValueAsString(env);
    assertThat(ok(post("/internal/v1/envelope/decrypt", body)).get("plaintext").asString()).isEqualTo(plain);

    byte[] w = Base64.getDecoder().decode(wrapped);
    w[w.length - 1] ^= 1;
    error(post("/internal/v1/envelope/decrypt", body.replace(wrapped, b64(w))), 400, "MW-VAL-016");
    error(post("/internal/v1/envelope/decrypt", body.replace(aad, b64("other".getBytes(StandardCharsets.UTF_8)))),
        400, "MW-VAL-016");

    assertThat(admin("/internal/v1/keys/kek-it/rotate", "").statusCode()).isEqualTo(200);
    assertThat(ok(post("/internal/v1/envelope/decrypt", body)).get("plaintext").asString()).isEqualTo(plain);
    JsonNode next = ok(post("/internal/v1/envelope/encrypt", "{\"kekId\":\"kek-it\",\"plaintext\":\"" + plain + "\"}"));
    assertThat(next.get("kekId").asString()).isNotEqualTo(kid);
    secret(next.get("wrappedKey").asString());
    error(post("/internal/v1/envelope/encrypt", "{\"kekId\":\"" + kid + "\",\"plaintext\":\"" + plain + "\"}"),
        400, "MW-VAL-015");
  }
}
