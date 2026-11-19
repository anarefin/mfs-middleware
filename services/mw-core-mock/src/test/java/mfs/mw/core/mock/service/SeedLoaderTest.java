package mfs.mw.core.mock.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * The versioned seed files (FRS 7.3, MCK-08, ticket 103): v1 has the counts of ticket 94 per type, and a
 * version is just another folder ({@code seed/t1/} on the test classpath), so a reset can load it.
 */
class SeedLoaderTest {

  private static Map<String, Integer> countsByType(SeedFiles files) {
    Map<String, Integer> counts = new TreeMap<>();
    files.wallets().forEach(r -> counts.merge(r.type(), r.last() - r.first() + 1, Integer::sum));
    return counts;
  }

  @Test
  void versionOneHasTheFrsCountsPerType() {
    SeedFiles v1 = new SeedFiles("v1");

    assertThat(countsByType(v1)).containsExactlyEntriesOf(new TreeMap<>(Map.of(
        "AGENT", 50, "CUSTOMER", 1000, "DISTRIBUTOR", 10, "MERCHANT", 20, "ORGANISATION", 3)));
    assertThat(v1.systemWallets()).extracting(SeedFiles.SystemWallet::id)
        .contains("FEE_INCOME", "VAT_PAYABLE", "COMMISSION_PAYABLE", "SETTLEMENT", "SETTLEMENT_NPSB");
    assertThat(v1.tariff()).isNotEmpty();
    assertThat(v1.limits()).isNotEmpty();
  }

  @Test
  void anotherVersionIsAnotherFolder() {
    SeedFiles t1 = new SeedFiles("t1");

    assertThat(t1.version()).isEqualTo("t1");
    assertThat(countsByType(t1)).containsExactlyEntriesOf(Map.of("CUSTOMER", 3));
    assertThat(t1.wallets().get(0).id(1)).isEqualTo("01719000001");
  }

  @Test
  void aMissingVersionFailsLoudly() {
    assertThatThrownBy(() -> new SeedFiles("v404").wallets()).isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("seed/v404/wallets.csv");
  }
}
