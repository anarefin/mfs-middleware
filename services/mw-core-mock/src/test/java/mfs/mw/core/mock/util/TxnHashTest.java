package mfs.mw.core.mock.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The assertion's {@code txnHash} (spec 18.7, D22): base64url SHA-256 of the RFC 8785 canonical JSON
 * of {@code {txnType, amount, currency, payer, payee, idempotencyKey, channel}}. mw-core-mock
 * recomputes it from the command with its own copy, so the expected values here are literals worked
 * out independently (Python {@code json.dumps(sort_keys=True, separators=(",", ":"))} + hashlib).
 */
class TxnHashTest {

  static final TxnHash.Party PAYER = new TxnHash.Party("WALLET", "01711000001");
  static final TxnHash.Party PAYEE = new TxnHash.Party("WALLET", "01811000002");
  static final String KEY = "6f1c2a9e-3b4d-4e5f-8a7b-1c2d3e4f5a6b";

  @Test
  void theCanonicalFormSortsKeysAndDropsWhitespace() {
    assertThat(TxnHash.canonical("P2P", 50000, "BDT", PAYER, PAYEE, KEY, "CUSTOMER_APP"))
        .isEqualTo("{\"amount\":50000,\"channel\":\"CUSTOMER_APP\",\"currency\":\"BDT\","
            + "\"idempotencyKey\":\"6f1c2a9e-3b4d-4e5f-8a7b-1c2d3e4f5a6b\","
            + "\"payee\":{\"id\":\"01811000002\",\"type\":\"WALLET\"},"
            + "\"payer\":{\"id\":\"01711000001\",\"type\":\"WALLET\"},\"txnType\":\"P2P\"}");
  }

  @Test
  void theHashIsTheKnownValue() {
    assertThat(TxnHash.of("P2P", 50000, "BDT", PAYER, PAYEE, KEY, "CUSTOMER_APP"))
        .isEqualTo("g9hAuJZ1M1HEHFd9l69WbRb86BDVgh6vCTKRjE3JA88");
  }

  @Test
  void theSameTxnInAnotherFieldOrderGivesTheSameCanonicalJson() {
    Map<String, Object> a = new LinkedHashMap<>();
    a.put("txnType", "P2P");
    a.put("amount", 50000L);
    a.put("payer", Map.of("type", "WALLET", "id", "01711000001"));
    Map<String, Object> b = new LinkedHashMap<>();
    b.put("payer", Map.of("id", "01711000001", "type", "WALLET"));
    b.put("amount", 50000);
    b.put("txnType", "P2P");
    assertThat(CanonicalJson.write(a)).isEqualTo(CanonicalJson.write(b))
        .isEqualTo("{\"amount\":50000,\"payer\":{\"id\":\"01711000001\",\"type\":\"WALLET\"},\"txnType\":\"P2P\"}");
  }

  @Test
  void anyChangedFieldChangesTheHash() {
    String base = TxnHash.of("P2P", 50000, "BDT", PAYER, PAYEE, KEY, "CUSTOMER_APP");
    assertThat(List.of(
            TxnHash.of("P2P", 50001, "BDT", PAYER, PAYEE, KEY, "CUSTOMER_APP"),
            TxnHash.of("CASH_OUT", 50000, "BDT", PAYER, PAYEE, KEY, "CUSTOMER_APP"),
            TxnHash.of("P2P", 50000, "BDT", PAYEE, PAYER, KEY, "CUSTOMER_APP"),
            TxnHash.of("P2P", 50000, "BDT", PAYER, new TxnHash.Party("MSISDN", "01811000002"), KEY, "CUSTOMER_APP"),
            TxnHash.of("P2P", 50000, "BDT", PAYER, PAYEE, KEY + "x", "CUSTOMER_APP"),
            TxnHash.of("P2P", 50000, "BDT", PAYER, PAYEE, KEY, "USSD")))
        .doesNotContain(base)
        .doesNotHaveDuplicates();
  }

  @Test
  void stringsAreEscapedAsJcsDoes() {
    assertThat(CanonicalJson.write(Map.of("k", "a\"b\\c\n\u0001é")))
        .isEqualTo("{\"k\":\"a\\\"b\\\\c\\n\\u0001é\"}");
  }

  @Test
  void nonIntegralNumbersAreRefused() {
    assertThatThrownBy(() -> CanonicalJson.write(Map.of("x", 1.5))).isInstanceOf(IllegalArgumentException.class);
  }
}
