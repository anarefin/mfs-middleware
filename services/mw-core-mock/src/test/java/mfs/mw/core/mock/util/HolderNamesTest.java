package mfs.mw.core.mock.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * Display names of the seeded wallets: realistic Bangladeshi names, fixed per index, so every run
 * and every demo shows the same holder for the same number.
 */
class HolderNamesTest {

  @Test
  void theFixturesHaveTheirCatalogueNames() {
    assertThat(HolderNames.of("CUSTOMER", 1)).isEqualTo("Rahim Uddin");
    assertThat(HolderNames.of("CUSTOMER", 2)).isEqualTo("Ayesha Siddiqua");
    assertThat(HolderNames.of("CUSTOMER", 3)).isEqualTo("Kamal Hossain");
    assertThat(HolderNames.of("AGENT", 1)).isEqualTo("Hossain Telecom");
    assertThat(HolderNames.of("MERCHANT", 1)).isEqualTo("Karim Traders");
    assertThat(HolderNames.of("MERCHANT", 2)).isEqualTo("Lalbagh Pharmacy");
    assertThat(HolderNames.of("ORG", 1)).isEqualTo("Meghna Garments Ltd");
    assertThat(HolderNames.of("ORG", 2)).isEqualTo("Padma Foods Ltd");
    assertThat(HolderNames.of("ORGANISATION", 3)).isEqualTo("Karnaphuli Shipping Ltd");
    assertThat(HolderNames.of("DISTRIBUTOR", 1)).isEqualTo("Dhaka Distribution House");
  }

  @Test
  void theSameIndexAlwaysGivesTheSameName() {
    assertThat(HolderNames.of("CUSTOMER", 417)).isEqualTo(HolderNames.of("CUSTOMER", 417));
    assertThat(HolderNames.of("AGENT", 33)).isEqualTo(HolderNames.of("AGENT", 33));
  }

  @Test
  void everyGeneratedWalletGetsAPlausibleName() {
    IntStream.rangeClosed(1, 1000).forEach(n -> assertThat(HolderNames.of("CUSTOMER", n)).matches("[A-Z][a-z]+ [A-Z][a-z]+"));
    IntStream.rangeClosed(1, 50).forEach(n -> assertThat(HolderNames.of("AGENT", n)).matches("[A-Z][a-z]+ [A-Z][A-Za-z ]+"));
    IntStream.rangeClosed(1, 20).forEach(n -> assertThat(HolderNames.of("MERCHANT", n)).isNotBlank());
  }

  @Test
  void neighbouringCustomersAndAllMerchantsAndAgentsAreDistinct() {
    Set<String> customers = new HashSet<>();
    IntStream.rangeClosed(1, 300).forEach(n -> customers.add(HolderNames.of("CUSTOMER", n)));
    assertThat(customers).hasSize(300);
    Set<String> agents = new HashSet<>();
    IntStream.rangeClosed(1, 50).forEach(n -> agents.add(HolderNames.of("AGENT", n)));
    assertThat(agents).hasSize(50);
    Set<String> merchants = new HashSet<>();
    IntStream.rangeClosed(1, 20).forEach(n -> merchants.add(HolderNames.of("MERCHANT", n)));
    assertThat(merchants).hasSize(20);
  }

  @Test
  void anUnknownTypeOrIndexHasNoName() {
    assertThat(HolderNames.of("SYSTEM", 1)).isNull();
    assertThat(HolderNames.of("CUSTOMER", 0)).isNull();
  }
}
