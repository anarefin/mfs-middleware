package mfs.mw.crypto.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.RSAKey;
import java.net.http.HttpResponse;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import mfs.mw.crypto.HsmStack;
import mfs.mw.crypto.security.InternalTokens;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The PKCS#11 provider against a real token (spec 18.8, D23): mw-crypto's image, logged in to the
 * SoftHSM {@code mw-crypto} token through the p11-kit proxy over TCP, as on the dev stack ({@link
 * HsmStack}). Driven over the key API: the dev bootstrap fills the token with a key of every purpose,
 * the public keys come out of the token, rotation adds a key to it, and an unreachable HSM fails the
 * requests closed and is reported by {@code crypto.hsm.up}, then recovered from.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Pkcs11ProviderIT {

  static final JsonMapper JSON = JsonMapper.builder().build();
  static final List<String> PURPOSES =
      List.of("PIN_TRANSPORT", "PIN_HMAC", "JWT_SIGN", "ASSERTION_SIGN", "AUDIT_ANCHOR_SIGN", "TOKENISE_MSISDN",
          "TOKENISE_NID", "TOKENISE_ACCOUNT", "KEK_COLUMN", "KEK_FILE", "XMLDSIG", "HMAC_ATMTOKEN");

  @BeforeAll
  static void stack() {
    HsmStack.start();
  }

  static String token() {
    return InternalTokens.forClient("mw-admin-api");
  }

  static JsonNode json(HttpResponse<String> r) {
    return JSON.readTree(r.body());
  }

  static JsonNode await(String path, Predicate<HttpResponse<String>> done) throws InterruptedException {
    return json(awaitResponse(path, done));
  }

  static HttpResponse<String> awaitResponse(String path, Predicate<HttpResponse<String>> done)
      throws InterruptedException {
    HttpResponse<String> last = null;
    for (int i = 0; i < 120; i++) {
      last = HsmStack.get(path, token());
      if (done.test(last)) {
        return last;
      }
      Thread.sleep(500);
    }
    throw new AssertionError(path + " never got there; last: " + last.statusCode() + " " + last.body());
  }

  /** The bootstrap's keys (default keyIds); other HSM ITs add keys of their own keyIds beside them. */
  static List<JsonNode> bootstrapKeys(JsonNode page) {
    List<JsonNode> out = new ArrayList<>();
    page.get("items").forEach(k -> {
      String purpose = k.get("purpose").asString();
      if (PURPOSES.contains(purpose) && k.get("keyId").asString().equals(purpose.toLowerCase(Locale.ROOT).replace('_', '-'))) {
        out.add(k);
      }
    });
    return out;
  }

  @Test
  @Order(1)
  void theBootstrapCreatesAKeyOfEveryPurposeInTheToken() throws Exception {
    JsonNode page =
        await("/internal/v1/keys", r -> r.statusCode() == 200 && bootstrapKeys(json(r)).size() == PURPOSES.size());
    List<JsonNode> keys = bootstrapKeys(page);
    assertThat(keys).extracting(k -> k.get("purpose").asString()).containsExactlyInAnyOrderElementsOf(PURPOSES);
    keys.forEach(k -> {
      assertThat(k.get("provider").asString()).isEqualTo("pkcs11");
      assertThat(k.get("versions").get(0).get("state").asString()).isEqualTo("ACTIVE");
    });
  }

  @Test
  @Order(2)
  void publicKeysComeOutOfTheToken() throws Exception {
    JsonNode jwt = json(HsmStack.get("/internal/v1/keys/jwt-sign/public", token()));
    RSAKey rsa = JWK.parse(JSON.writeValueAsString(jwt.get("versions").get(0).get("jwk"))).toRSAKey();
    assertThat(rsa.getKeyID()).isEqualTo("jwt-sign.v1");
    assertThat(rsa.getAlgorithm().getName()).isEqualTo("RS256");
    assertThat(((RSAPublicKey) rsa.toPublicKey()).getModulus().bitLength()).isEqualTo(3072);
    assertThat(rsa.isPrivate()).isFalse();
    assertThat(jwt.get("versions").get(0).get("pem").asString()).startsWith("-----BEGIN PUBLIC KEY-----\n");

    JsonNode assertion = json(HsmStack.get("/internal/v1/keys/assertion-sign/public", token()));
    ECKey ec = JWK.parse(JSON.writeValueAsString(assertion.get("jwks").get("keys").get(0))).toECKey();
    assertThat(ec.getCurve().getName()).isEqualTo("P-256");
    assertThat(ec.getAlgorithm().getName()).isEqualTo("ES256");

    HttpResponse<String> secret = HsmStack.get("/internal/v1/keys/pin-hmac/public", token());
    assertThat(secret.statusCode()).isEqualTo(400);
    assertThat(json(secret).get("code").asString()).isEqualTo("MW-VAL-013");
  }

  @Test
  @Order(3)
  void generateAndRotatePutNewKeysInTheToken() throws Exception {
    HttpResponse<String> created =
        HsmStack.post("/internal/v1/keys", "{\"purpose\":\"XMLDSIG\",\"keyId\":\"xmldsig-npsb\"}", token());
    assertThat(created.statusCode()).as(created.body()).isEqualTo(201);

    HttpResponse<String> rotated = HsmStack.post("/internal/v1/keys/xmldsig-npsb/rotate", "", token());
    assertThat(rotated.statusCode()).as(rotated.body()).isEqualTo(200);
    JsonNode versions = json(rotated).get("versions");
    assertThat(versions.get(0).get("state").asString()).isEqualTo("VERIFY_ONLY");
    assertThat(versions.get(1).get("state").asString()).isEqualTo("ACTIVE");

    JsonNode pub = json(HsmStack.get("/internal/v1/keys/xmldsig-npsb/public", token()));
    assertThat(pub.get("versions")).hasSize(2);
    assertThat(pub.get("versions").get(0).get("jwk").get("n").asString())
        .isNotEqualTo(pub.get("versions").get(1).get("jwk").get("n").asString());

    HttpResponse<String> again = HsmStack.post("/internal/v1/keys", "{\"purpose\":\"KEK_COLUMN\"}", token());
    assertThat(again.statusCode()).isEqualTo(409);
  }

  @Test
  @Order(4)
  void theHsmIsReportedUp() throws Exception {
    awaitResponse("/actuator/prometheus", r -> r.body().contains("crypto_hsm_up{provider=\"pkcs11\"} 1.0"));
    String metrics = HsmStack.get("/actuator/prometheus", null).body();
    assertThat(metrics).contains("crypto_op_duration_seconds_count{op=\"generate\",outcome=\"success\",provider=\"pkcs11\"}");
  }

  @Test
  @Order(5)
  void anUnreachableHsmFailsClosedAndIsRecoveredFrom() throws Exception {
    HsmStack.stopHsm();
    try {
      awaitResponse("/actuator/prometheus", r -> r.body().contains("crypto_hsm_up{provider=\"pkcs11\"} 0.0"));
      HttpResponse<String> down = HsmStack.get("/internal/v1/keys/jwt-sign/public", token());
      assertThat(down.statusCode()).isEqualTo(503);
      assertThat(json(down).get("code").asString()).isEqualTo("MW-SYS-010");
      assertThat(json(down).get("retryable").asBoolean()).isTrue();
      assertThat(down.body()).doesNotContain(HsmStack.USER_PIN);
    } finally {
      HsmStack.startHsm();
    }
    JsonNode back = await("/internal/v1/keys/jwt-sign/public", r -> r.statusCode() == 200);
    assertThat(back.get("versions").get(0).get("kid").asString()).isEqualTo("jwt-sign.v1");
    assertThat(HsmStack.SOFTHSM.getLogs()).doesNotContain(HsmStack.USER_PIN);
  }
}
