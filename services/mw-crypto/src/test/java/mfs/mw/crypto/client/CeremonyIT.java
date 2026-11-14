package mfs.mw.crypto.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import mfs.mw.crypto.HsmStack;
import mfs.mw.crypto.security.InternalTokens;
import mfs.mw.crypto.util.CeremonyComponents;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The key ceremony against the SoftHSM token (ticket 75), over mw-crypto's own image ({@link
 * HsmStack}): two custodians' components are combined inside the HSM and give the expected KCV;
 * the committed key HMACs exactly as the key computed outside from the two components; a second
 * ceremony rotates it; an aborted one leaves nothing; no component reached the log.
 */
class CeremonyIT {

  static final JsonMapper JSON = JsonMapper.builder().build();
  static final SecureRandom RANDOM = new SecureRandom();
  static final List<String> COMPONENTS = new ArrayList<>();
  static final String KEY = "hmac-ceremony-it";

  @BeforeAll
  static void start() {
    HsmStack.start();
  }

  @AfterAll
  static void noComponentInTheLog() {
    String logs = HsmStack.start().getLogs();
    assertThat(COMPONENTS).isNotEmpty();
    for (String c : COMPONENTS) {
      assertThat(logs).doesNotContainIgnoringCase(c);
    }
  }

  static String admin() {
    return InternalTokens.forClient("mw-admin-api");
  }

  static byte[] component() {
    byte[] c = new byte[32];
    do {
      RANDOM.nextBytes(c);
    } while (!CeremonyComponents.valid(c));
    COMPONENTS.add(HexFormat.of().formatHex(c));
    return c;
  }

  static HttpResponse<String> enter(UUID id, String custodian, byte[] component) {
    String hex = HexFormat.of().formatHex(component);
    return HsmStack.post("/internal/v1/ceremonies/" + id + "/components",
        "{\"keyId\":\"" + KEY + "\",\"purpose\":\"HMAC_IT\",\"custodian\":\"" + custodian + "\",\"component\":\""
            + hex + "\",\"checkValue\":\"" + CeremonyComponents.checkValue(component) + "\"}", admin());
  }

  static JsonNode ok(HttpResponse<String> r) {
    assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
    return JSON.readTree(r.body());
  }

  static String hmacOfZeros(String kid) {
    HttpResponse<String> r = HsmStack.post("/internal/v1/hmac/sign",
        "{\"keyId\":\"" + kid + "\",\"data\":\"" + Base64.getEncoder().encodeToString(new byte[16]) + "\"}",
        InternalTokens.forClient("mw-auth"));
    return ok(r).get("mac").asString();
  }

  static String expectedHmac(byte[] key) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(key, "HmacSHA256"));
    return Base64.getEncoder().encodeToString(mac.doFinal(new byte[16]));
  }

  @Test
  void twoComponentsGiveTheExpectedKeyThenASecondCeremonyRotatesIt() throws Exception {
    UUID id = UUID.randomUUID();
    byte[] c1 = component();
    byte[] c2 = component();
    byte[] expected = CeremonyComponents.combined(c1, c2);

    JsonNode first = ok(enter(id, "seed-ops-secadmin1", c1));
    assertThat(first.get("state").asString()).isEqualTo("OPEN");
    assertThat(first.get("checkValue").isNull()).isTrue();

    // the same custodian cannot give the second share
    assertThat(enter(id, "seed-ops-secadmin1", c2).statusCode()).isEqualTo(409);

    JsonNode combined = ok(enter(id, "seed-ops-secadmin2", c2));
    assertThat(combined.get("state").asString()).isEqualTo("COMBINED");
    assertThat(combined.get("checkValue").asString()).isEqualTo(CeremonyComponents.keyCheckValueOf(expected));
    assertThat(combined.get("shares")).hasSize(2);
    assertThat(combined.toString()).doesNotContainIgnoringCase(HexFormat.of().formatHex(c1));

    // not usable before the approval
    assertThat(HsmStack.post("/internal/v1/hmac/sign",
        "{\"keyId\":\"" + KEY + "\",\"data\":\"AAAA\"}", InternalTokens.forClient("mw-auth")).statusCode()).isEqualTo(404);

    JsonNode key = ok(HsmStack.post("/internal/v1/ceremonies/" + id + "/commit",
        "{\"makerId\":\"seed-ops-secadmin1\",\"checkerId\":\"seed-ops-secadmin2\"}", admin()));
    assertThat(key.get("versions").get(0).get("state").asString()).isEqualTo("ACTIVE");
    assertThat(hmacOfZeros(KEY + ".v1")).isEqualTo(expectedHmac(expected));

    // a second ceremony makes v2 ACTIVE, v1 VERIFY_ONLY
    UUID second = UUID.randomUUID();
    byte[] d1 = component();
    byte[] d2 = component();
    ok(enter(second, "seed-ops-secadmin2", d1));
    ok(enter(second, "seed-ops-secadmin1", d2));
    JsonNode rotated = ok(HsmStack.post("/internal/v1/ceremonies/" + second + "/commit",
        "{\"makerId\":\"seed-ops-secadmin2\",\"checkerId\":\"seed-ops-secadmin1\"}", admin()));
    assertThat(rotated.get("versions")).hasSize(2);
    assertThat(rotated.get("versions").get(1).get("state").asString()).isEqualTo("ACTIVE");
    assertThat(hmacOfZeros(KEY + ".v2")).isEqualTo(expectedHmac(CeremonyComponents.combined(d1, d2)));
  }

  @Test
  void abortedCeremonyLeavesNoKeyAndAWrongCheckValueIsRefused() {
    UUID id = UUID.randomUUID();
    byte[] c1 = component();
    String body = "{\"keyId\":\"hmac-ceremony-abort-it\",\"purpose\":\"HMAC_IT\",\"custodian\":\"a\",\"component\":\""
        + HexFormat.of().formatHex(c1) + "\",\"checkValue\":\"000000\"}";
    HttpResponse<String> wrong = HsmStack.post("/internal/v1/ceremonies/" + id + "/components", body, admin());
    assertThat(wrong.statusCode()).isEqualTo(400);
    assertThat(wrong.body()).contains("MW-VAL-017");

    ok(HsmStack.post("/internal/v1/ceremonies/" + id + "/components", body.replace("000000",
        CeremonyComponents.checkValue(c1)), admin()));
    assertThat(HsmStack.post("/internal/v1/ceremonies/" + id + "/abort", "{}", admin()).statusCode()).isEqualTo(204);
    assertThat(ok(HsmStack.get("/internal/v1/ceremonies/" + id, admin())).get("state").asString()).isEqualTo("ABORTED");
    assertThat(HsmStack.get("/internal/v1/keys", admin()).body()).doesNotContain("hmac-ceremony-abort-it");
  }

  @Test
  void onlyTheKeyAdminClientMayRunACeremony() {
    UUID id = UUID.randomUUID();
    byte[] c = component();
    HttpResponse<String> r = HsmStack.post("/internal/v1/ceremonies/" + id + "/components",
        "{\"keyId\":\"x-it\",\"purpose\":\"HMAC_IT\",\"custodian\":\"a\",\"component\":\"" + HexFormat.of().formatHex(c)
            + "\",\"checkValue\":\"" + CeremonyComponents.checkValue(c) + "\"}", InternalTokens.forClient("mw-auth"));
    assertThat(r.statusCode()).isEqualTo(403);
  }
}
