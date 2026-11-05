package mfs.mw.orchestrator.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HexFormat;
import org.junit.jupiter.api.Test;

/**
 * The keyed search hash (spec 14.3, 16.3 {@code initiator_hash}, {@code payee_hash}): the MSISDN is
 * searched through an HMAC, so the raw number is never indexed. The input is {@code
 * <type>:<canonical id>}, so an id of one type never matches the same text of another (an org id
 * never matches an MSISDN). Expected values were computed independently with {@code printf
 * '<type>:<id>' | openssl dgst -sha256 -hmac '<key>'}.
 */
class SearchHashTest {

  private static final String KEY = "test-only-search-hash-key-not-a-secret-0001";
  private final SearchHash hash = new SearchHash(KEY);

  private static String hex(byte[] b) {
    return HexFormat.of().formatHex(b);
  }

  @Test
  void anMsisdnIsHashedWithTheKeyAndItsTypeInItsLocalForm() {
    assertThat(hex(hash.of("MSISDN", "01711000001")))
        .isEqualTo("51371453f1236eb6d9945810c58dca5fab431e62a503b44b3d0d75f79754751e");
  }

  @Test
  void everyWrittenFormOfOneMsisdnHashesAlike() {
    byte[] local = hash.of("MSISDN", "01711000001");
    assertThat(hash.of("MSISDN", "+8801711000001")).isEqualTo(local);
    assertThat(hash.of("MSISDN", "8801711000001")).isEqualTo(local);
    assertThat(hash.of("MSISDN", " 01711-000001 ")).isEqualTo(local);
    assertThat(hash.of("MSISDN", "+880 1711 000001")).isEqualTo(local);
  }

  @Test
  void otherIdsAreHashedAsTheyAre() {
    assertThat(hex(hash.of("ORG", "ORG001")))
        .isEqualTo("6fd2c40b7f2a2fecd1d3c561b97b7bfc04d4b6256ac82000a067d74075dc0bca");
  }

  @Test
  void theSameIdUnderAnotherTypeHashesDifferently() {
    assertThat(hash.of("ORG", "01711000001")).isNotEqualTo(hash.of("MSISDN", "01711000001"));
    assertThat(hash.of("ORG", "ORG001")).isNotEqualTo(hash.of("MERCHANT", "ORG001"));
  }

  @Test
  void aTypeIsRequired() {
    assertThatThrownBy(() -> hash.of(null, "ORG001")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> hash.of(" ", "ORG001")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void anotherKeyGivesAnotherHash() {
    assertThat(new SearchHash("test-only-other-search-hash-key-not-a-secret").of("MSISDN", "01711000001"))
        .isNotEqualTo(hash.of("MSISDN", "01711000001"));
  }

  @Test
  void aShortKeyIsRefused() {
    assertThatThrownBy(() -> new SearchHash("short")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SearchHash(null)).isInstanceOf(IllegalArgumentException.class);
  }
}
