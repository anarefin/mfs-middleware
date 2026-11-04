package mfs.mw.orchestrator.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HexFormat;
import org.junit.jupiter.api.Test;

/**
 * The keyed search hash (spec 14.3, 16.3 {@code initiator_hash}): the MSISDN is searched through
 * an HMAC, so the raw number is never indexed. Expected values were computed independently with
 * {@code printf '<id>' | openssl dgst -sha256 -hmac '<key>'}.
 */
class SearchHashTest {

  private static final String KEY = "test-only-search-hash-key-not-a-secret-0001";
  private final SearchHash hash = new SearchHash(KEY);

  private static String hex(byte[] b) {
    return HexFormat.of().formatHex(b);
  }

  @Test
  void anMsisdnIsHashedWithTheKeyInItsLocalForm() {
    assertThat(hex(hash.of("01711000001")))
        .isEqualTo("bbd357a280eb2e096e87c49a6de75e7388d6ff5d0d830dca11514979f1658d9a");
  }

  @Test
  void everyWrittenFormOfOneMsisdnHashesAlike() {
    byte[] local = hash.of("01711000001");
    assertThat(hash.of("+8801711000001")).isEqualTo(local);
    assertThat(hash.of("8801711000001")).isEqualTo(local);
    assertThat(hash.of(" 01711-000001 ")).isEqualTo(local);
    assertThat(hash.of("+880 1711 000001")).isEqualTo(local);
  }

  @Test
  void otherIdsAreHashedAsTheyAre() {
    assertThat(hex(hash.of("ORG001")))
        .isEqualTo("b72a8875ab211eb39c914106d8bd5da34947a0afbbed129cb6ecb3bad2f1e08f");
  }

  @Test
  void anotherKeyGivesAnotherHash() {
    assertThat(new SearchHash("test-only-other-search-hash-key-not-a-secret").of("01711000001"))
        .isNotEqualTo(hash.of("01711000001"));
  }

  @Test
  void aShortKeyIsRefused() {
    assertThatThrownBy(() -> new SearchHash("short")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SearchHash(null)).isInstanceOf(IllegalArgumentException.class);
  }
}
