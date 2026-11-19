package mfs.mw.core.mock.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import mfs.mw.core.mock.TestInfrastructure;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The ledger is seeded on start (spec 6, "Core mock"): 1,000 customers, 50 agents, 20 merchants and
 * 2 orgs, the same wallets and balances every time, so tests and demos can rely on them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestInfrastructure.class)
class WalletSeederIT {

  private final JdbcTemplate jdbc;
  private final WalletSeeder seeder;

  @Autowired
  WalletSeederIT(JdbcTemplate jdbc, WalletSeeder seeder) {
    this.jdbc = jdbc;
    this.seeder = seeder;
  }

  private Map<String, Long> countsByType() {
    Map<String, Long> out = new java.util.TreeMap<>();
    jdbc.query(
        "select type, count(*) c from wallet group by type",
        rs -> {
          out.put(rs.getString("type"), rs.getLong("c"));
        });
    return out;
  }

  /** The system wallets FRS 7.1 / 7.3 names: charges and one settlement wallet per partner (ticket 94). */
  static final List<String> SYSTEM_WALLETS = List.of("FEE_INCOME", "VAT_PAYABLE", "COMMISSION_PAYABLE", "SETTLEMENT",
      "SETTLEMENT_OCS_GP", "SETTLEMENT_OCS_ROBI", "SETTLEMENT_OCS_AIRTEL", "SETTLEMENT_OCS_TELETALK", "SETTLEMENT_OCS_BL",
      "SETTLEMENT_BILLER_DESCO", "SETTLEMENT_BILLER_DPDC", "SETTLEMENT_BILLER_WASA", "SETTLEMENT_BILLER_TITAS",
      "SETTLEMENT_AGG_BILLAGG", "SETTLEMENT_BANK_UCB", "SETTLEMENT_BANK_BRAC", "SETTLEMENT_BANK_DBBL", "SETTLEMENT_NPSB",
      "SETTLEMENT_CYBERSOURCE", "SETTLEMENT_UCB_IMT", "SETTLEMENT_UCB_ATM");

  @Test
  void seedLoadsOnStartWithTheSpecifiedPopulation() {
    seeder.reset();

    assertThat(countsByType())
        .containsEntry("CUSTOMER", 1000L)
        .containsEntry("AGENT", 50L)
        .containsEntry("DISTRIBUTOR", 10L)
        .containsEntry("MERCHANT", 20L)
        .containsEntry("ORGANISATION", 3L)
        .containsEntry("SYSTEM", (long) SYSTEM_WALLETS.size());
    assertThat(jdbc.queryForList("select id from wallet where type = 'SYSTEM' and msisdn is null", String.class))
        .containsExactlyInAnyOrderElementsOf(SYSTEM_WALLETS);
    assertThat(jdbc.queryForObject("select type from wallet where id = '01611000010'", String.class)).isEqualTo("DISTRIBUTOR");
    assertThat(jdbc.queryForObject("select type from wallet where id = '01311000003'", String.class)).isEqualTo("ORGANISATION");
  }

  @Test
  void theLastHundredCustomersHaveBasicKycAndEveryOtherWalletFull() {
    seeder.reset();

    assertThat(jdbc.queryForObject("select count(*) from wallet where kyc_level = 'BASIC'", Integer.class)).isEqualTo(100);
    assertThat(jdbc.queryForObject("select kyc_level from wallet where id = '01711000901'", String.class)).isEqualTo("BASIC");
    assertThat(jdbc.queryForObject("select kyc_level from wallet where id = '01711000900'", String.class)).isEqualTo("FULL");
  }

  @Test
  void theTariffAndLimitSheetsComeFromTheSeedFiles() {
    seeder.reset();

    assertThat(jdbc.queryForObject("select fee from tariff where txn_type = 'P2P' and slab_min = 100", Long.class)).isEqualTo(500);
    assertThat(jdbc.queryForObject(
        "select commission_split->>'AGENT' from tariff where txn_type = 'CASH_OUT' and slab_min = 100", String.class))
        .isEqualTo("40");
    assertThat(jdbc.queryForObject(
        "select per_txn_max from limit_rule where txn_type = '*' and customer_type = 'CUSTOMER' and kyc_level = 'BASIC'",
        Long.class)).isEqualTo(100_000);
    assertThat(jdbc.queryForObject(
        "select daily_count from limit_rule where customer_type = 'ORGANISATION'", Integer.class)).isNull();
  }

  @Test
  void reseedingIsIdempotent() {
    seeder.reset();
    List<String> wallets = snapshot();
    List<String> sheets = sheets();
    jdbc.update("update wallet set balance_minor = 7 where id = 'VAT_PAYABLE'");

    seeder.seed();
    seeder.seed();

    assertThat(countsByType()).containsEntry("CUSTOMER", 1000L).containsEntry("SYSTEM", (long) SYSTEM_WALLETS.size());
    assertThat(sheets()).isEqualTo(sheets);
    assertThat(jdbc.queryForObject("select balance_minor from wallet where id = 'VAT_PAYABLE'", Long.class))
        .as("an existing wallet is left alone").isEqualTo(7);
    assertThat(snapshot()).hasSameSizeAs(wallets);
  }

  private List<String> sheets() {
    List<String> rows = new java.util.ArrayList<>(jdbc.queryForList(
        "select concat_ws('|', txn_type, channel, customer_type, slab_min, slab_max, fee, vat_pct, commission_split) "
            + "from tariff order by 1", String.class));
    rows.addAll(jdbc.queryForList(
        "select concat_ws('|', txn_type, customer_type, kyc_level, per_txn_min, per_txn_max, daily_count, daily_amount, "
            + "monthly_count, monthly_amount) from limit_rule order by 1", String.class));
    return rows;
  }

  @Test
  void seedIsReproducible() {
    seeder.reset();
    List<String> first = snapshot();
    jdbc.update("update wallet set balance_minor = 1 where id = ?", "01711000001");
    seeder.reset();

    assertThat(snapshot()).isEqualTo(first);
    assertThat(first).contains("01711000001|CUSTOMER|ACTIVE|1000000|Rahim Uddin");
  }

  @Test
  void theLedgerHoldsNoPinMaterial() {
    seeder.reset();
    // ticket 72: PINs live in mw-auth only; the wallet table has no PIN column left
    assertThat(jdbc.queryForObject(
        "select count(*) from information_schema.columns where table_name = 'wallet' and column_name like 'pin%'",
        Integer.class)).isZero();
  }

  @Test
  void seedingTwiceDoesNotDuplicate() {
    seeder.seed();
    seeder.seed();

    assertThat(countsByType()).containsEntry("CUSTOMER", 1000L);
  }

  @Test
  void everySeededWalletHasItsDisplayNameAndTheFixturesTheCatalogueOnes() {
    seeder.reset();

    assertThat(holder("01711000001")).isEqualTo("Rahim Uddin");
    assertThat(holder("01711000002")).isEqualTo("Ayesha Siddiqua");
    assertThat(holder("01711000003")).isEqualTo("Kamal Hossain");
    assertThat(holder("01811000001")).isEqualTo("Hossain Telecom");
    assertThat(holder("01911000001")).isEqualTo("Karim Traders");
    assertThat(holder("01911000002")).isEqualTo("Lalbagh Pharmacy");
    assertThat(holder("01311000001")).isEqualTo("Meghna Garments Ltd");
    assertThat(holder("01311000002")).isEqualTo("Padma Foods Ltd");
    assertThat(holder("01311000003")).isEqualTo("Karnaphuli Shipping Ltd");
    assertThat(holder("01611000001")).isEqualTo("Dhaka Distribution House");
    assertThat(jdbc.queryForObject("select count(*) from wallet where msisdn is not null and holder_name is null", Integer.class))
        .isZero();
    assertThat(holder(WalletSeeder.SETTLEMENT_WALLET)).isNull();
  }

  @Test
  void seedingFillsMissingNamesOfExistingRowsAndKeepsOthers() {
    seeder.reset();
    jdbc.update("update wallet set holder_name = null where id = ?", "01711000002");
    jdbc.update("update wallet set holder_name = 'Someone Else' where id = ?", "01711000003");

    seeder.seed();

    assertThat(holder("01711000002")).isEqualTo("Ayesha Siddiqua");
    assertThat(holder("01711000003")).isEqualTo("Someone Else");
  }

  private String holder(String id) {
    return jdbc.queryForObject("select holder_name from wallet where id = ?", String.class, id);
  }

  private List<String> snapshot() {
    return jdbc.queryForList(
        "select id || '|' || type || '|' || status || '|' || balance_minor || '|' || coalesce(holder_name, '-') from wallet order by id",
        String.class);
  }
}
