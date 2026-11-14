package mfs.mw.crypto.contracts;

import static mfs.mw.crypto.contracts.OperationContractsTest.invalidRequest;
import static mfs.mw.crypto.contracts.OperationContractsTest.validRequest;
import static mfs.mw.crypto.contracts.OperationContractsTest.validResponse;
import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.Schema;
import mfs.mw.crypto.client.PinBlockFormat;
import mfs.mw.crypto.dto.PvvRequest;
import mfs.mw.crypto.dto.PvvView;
import mfs.mw.crypto.dto.TranslatePinBlockRequest;
import mfs.mw.crypto.dto.TranslatedPinBlock;
import org.junit.jupiter.api.Test;

/**
 * The payShield endpoints of {@code api/mw-crypto/crypto-internal.v1} (ticket 64): the fixtures the
 * ATM and interop adapters will send validate against the request schemas and the DTOs' Bean
 * Validation, invalid ones fail both, and every response DTO validates against its schema; the new
 * purposes and algorithms are in the key schemas.
 */
class PayShieldContractsTest {

  static final String ACCOUNT = "4000001234562000";

  static String block(String keyId, String format, String hex) {
    return "{\"keyId\":\"" + keyId + "\",\"format\":\"" + format + "\",\"block\":\"" + hex + "\"}";
  }

  @Test
  void translate() {
    String path = "/internal/v1/pinblock/translate";
    validRequest(path, TranslatePinBlockRequest.class, "{\"source\":" + block("tpk", "ISO0", "0123456789ABCDEF")
        + ",\"destination\":{\"keyId\":\"zpk.v2\",\"format\":\"ISO0\"},\"account\":\"" + ACCOUNT + "\"}");
    validRequest(path, TranslatePinBlockRequest.class, "{\"source\":" + block("zpk", "ISO3", "0123456789abcdef")
        + ",\"destination\":{\"keyId\":\"zpk-aes\",\"format\":\"ISO4\"},\"account\":\"400000123456\"}");
    // an unknown format: the schema refuses it (the DTO cannot even read it: 400 MW-VAL-002)
    assertThat(OperationContractsTest.request(path).validate(ProducedContractsTest.JSON.readTree("{\"source\":"
        + block("tpk", "ISO2", "0123456789ABCDEF") + ",\"destination\":{\"keyId\":\"zpk\",\"format\":\"ISO0\"},"
        + "\"account\":\"" + ACCOUNT + "\"}"))).isNotEmpty();
    invalidRequest(path, TranslatePinBlockRequest.class, "{\"source\":" + block("tpk", "ISO0", "0123456789ABCDEG")
        + ",\"destination\":{\"keyId\":\"zpk\",\"format\":\"ISO0\"},\"account\":\"" + ACCOUNT + "\"}");
    invalidRequest(path, TranslatePinBlockRequest.class, "{\"source\":" + block("tpk", "ISO0", "0123456789ABCDEF")
        + ",\"destination\":{\"keyId\":\"zpk\",\"format\":\"ISO0\"},\"account\":\"12345\"}");
    validResponse(path, new TranslatedPinBlock("FEDCBA9876543210", PinBlockFormat.ISO0, "zpk.v1"));
    validResponse(path, new TranslatedPinBlock("A".repeat(32), PinBlockFormat.ISO4, "zpk-aes.v1"));
  }

  @Test
  void pvv() {
    String gen = "/internal/v1/pvv/generate";
    String req = "{\"pvkId\":\"pvk\",\"pvki\":1,\"pinBlock\":" + block("tpk", "ISO0", "0123456789ABCDEF")
        + ",\"account\":\"" + ACCOUNT + "\"";
    validRequest(gen, PvvRequest.class, req + "}");
    invalidRequest(gen, PvvRequest.class, req.replace("\"pvki\":1", "\"pvki\":10") + "}");
    validResponse(gen, PvvView.generated("4821", "pvk.v1", 1));

    String verify = "/internal/v1/pvv/verify";
    validRequest(verify, PvvRequest.class, req + ",\"pvv\":\"4821\"}");
    invalidRequest(verify, PvvRequest.class, req + ",\"pvv\":\"482\"}");
    validResponse(verify, PvvView.verified(false, "pvk.v1"));
    validResponse(verify, PvvView.verified(true, "pvk.v2"));
  }

  @Test
  void keySchemasKnowThePayShieldPurposesAndAlgorithms() {
    Schema purpose = OpenApiSchemas.component(OperationContractsTest.API, "Purpose");
    for (String p : new String[] {"ZPK", "TPK", "ZPK_AES", "TPK_AES", "PVK"}) {
      assertThat(purpose.validate(ProducedContractsTest.JSON.valueToTree(p))).as(p).isEmpty();
    }
    Schema key = OpenApiSchemas.component(OperationContractsTest.API, "Key");
    String json = "{\"keyId\":\"zpk\",\"purpose\":\"ZPK\",\"algorithm\":\"TDES_2KEY\",\"provider\":\"payshield\","
        + "\"versions\":[{\"version\":1,\"kid\":\"zpk.v1\",\"state\":\"ACTIVE\",\"createdAt\":\"2026-11-14T00:00:00Z\"}]}";
    assertThat(key.validate(ProducedContractsTest.JSON.readTree(json))).isEmpty();
  }
}
