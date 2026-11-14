package mfs.mw.crypto.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.Schema;
import mfs.mw.crypto.dto.PayShieldTestKey;
import mfs.mw.crypto.dto.PvvView;
import org.junit.jupiter.api.Test;

/**
 * The dev-only payShield test API {@code api/mw-crypto/crypto-test.v1} (ticket 76): the bodies the
 * Newman suite sends validate against its request schemas, and the DTOs the controller answers with
 * validate against its response schemas.
 */
class PayShieldTestApiContractsTest {

  static final String TEST_API = "api/mw-crypto/crypto-test.v1.openapi.yaml";
  static final String ACCOUNT = "4000001234562000";

  static Schema request(String path) {
    return OpenApiSchemas.pointer(TEST_API,
        "/paths/" + path.replace("/", "~1") + "/post/requestBody/content/application~1json/schema");
  }

  @Test
  void pinKey() {
    ProducedContractsTest.assertValid(
        OpenApiSchemas.response(TEST_API, "/test/v1/payshield/keys/{purpose}", "get", "200"),
        new PayShieldTestKey("tpk", "tpk.v1", "TPK", "U" + "0123456789ABCDEF".repeat(2)));
  }

  @Test
  void pvv() {
    String body = "{\"pvkId\":\"pvk\",\"pvki\":1,\"pinBlock\":{\"keyId\":\"tpk.v1\",\"format\":\"ISO0\","
        + "\"block\":\"0123456789ABCDEF\"},\"account\":\"" + ACCOUNT + "\"";
    assertThat(request("/test/v1/payshield/pvv/generate").validate(ProducedContractsTest.JSON.readTree(body + "}")))
        .isEmpty();
    assertThat(request("/test/v1/payshield/pvv/verify")
        .validate(ProducedContractsTest.JSON.readTree(body + ",\"pvv\":\"4821\"}"))).isEmpty();
    assertThat(request("/test/v1/payshield/pvv/verify").validate(ProducedContractsTest.JSON.readTree(body + "}")))
        .isNotEmpty();
    ProducedContractsTest.assertValid(
        OpenApiSchemas.response(TEST_API, "/test/v1/payshield/pvv/generate", "post", "200"),
        PvvView.generated("4821", "pvk.v1", 1));
    ProducedContractsTest.assertValid(
        OpenApiSchemas.response(TEST_API, "/test/v1/payshield/pvv/verify", "post", "200"),
        PvvView.verified(true, "pvk.v1"));
  }
}
