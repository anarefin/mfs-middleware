package mfs.mw.core.mock.controller;

import static org.assertj.core.api.Assertions.assertThat;

import mfs.mw.core.mock.TestInfrastructure;
import mfs.mw.core.mock.service.WalletSeeder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/** The seed API on the real ledger schema (ticket 57): create-if-absent, seed PIN, never a change. */
@SpringBootTest(properties = "mw.coremock.seed-api.enabled=true")
@AutoConfigureMockMvc
@Import(TestInfrastructure.class)
class WalletSeedApiIT {

  private static final String URI = "/core-mock/v1/wallets/";

  private final MockMvcTester mvc;
  private final WalletSeeder seeder;
  private final JdbcTemplate jdbc;

  @Autowired
  WalletSeedApiIT(MockMvcTester mvc, WalletSeeder seeder, JdbcTemplate jdbc) {
    this.mvc = mvc;
    this.seeder = seeder;
    this.jdbc = jdbc;
  }

  @BeforeEach
  void reset() {
    seeder.reset();
  }

  private MockMvcTester.MockMvcRequestBuilder put(String id, String type, long balance) {
    return mvc.put().uri(URI + id).contentType(MediaType.APPLICATION_JSON)
        .content("{\"type\":\"" + type + "\",\"openingBalanceMinor\":" + balance + "}");
  }

  @Test
  void createsTheWalletOnceAndLeavesItAloneAfterwards() {
    assertThat(put("01712000001", "AGENT", 7000)).hasStatus(201).bodyJson()
        .isLenientlyEqualTo("{\"walletId\":\"01712000001\",\"type\":\"AGENT\",\"status\":\"ACTIVE\",\"balanceMinor\":7000}");
    assertThat(mvc.get().uri(URI + "01712000001")).hasStatusOk();
    assertThat(jdbc.queryForObject("select msisdn from wallet where id = ?", String.class, "01712000001")).isEqualTo("01712000001");

    jdbc.update("update wallet set balance_minor = 5 where id = ?", "01712000001");
    assertThat(put("01712000001", "AGENT", 7000)).hasStatus(200).bodyJson().extractingPath("$.balanceMinor").isEqualTo(5);
    assertThat(jdbc.queryForObject("select count(*) from entry where wallet_id = ?", Integer.class, "01712000001")).isZero();
  }

  @Test
  void theHolderNameIsStoredOnCreateServedOnGetAndOnlyFilledWhenMissing() {
    assertThat(mvc.put().uri(URI + "01712000002").contentType(MediaType.APPLICATION_JSON)
            .content("{\"type\":\"CUSTOMER\",\"openingBalanceMinor\":0,\"holderName\":\"Sumaiya Islam\"}"))
        .hasStatus(201).bodyJson().extractingPath("$.holderName").isEqualTo("Sumaiya Islam");
    assertThat(mvc.get().uri(URI + "01712000002")).hasStatusOk().bodyJson().extractingPath("$.holderName").isEqualTo("Sumaiya Islam");

    assertThat(mvc.put().uri(URI + "01712000002").contentType(MediaType.APPLICATION_JSON)
            .content("{\"type\":\"CUSTOMER\",\"openingBalanceMinor\":0,\"holderName\":\"Other Name\"}"))
        .hasStatus(200).bodyJson().extractingPath("$.holderName").isEqualTo("Sumaiya Islam");

    assertThat(put("01712000003", "CUSTOMER", 0)).hasStatus(201).bodyJson().extractingPath("$.holderName").isNull();
    assertThat(mvc.put().uri(URI + "01712000003").contentType(MediaType.APPLICATION_JSON)
            .content("{\"type\":\"CUSTOMER\",\"openingBalanceMinor\":0,\"holderName\":\"Rakibul Hasan\"}"))
        .hasStatus(200).bodyJson().extractingPath("$.holderName").isEqualTo("Rakibul Hasan");
  }

  private long total() {
    return jdbc.queryForObject("select sum(balance_minor) from wallet", Long.class);
  }

  private MockMvcTester.MockMvcRequestBuilder setBalance(String id, String type, long balance) {
    return mvc.put().uri(URI + id).contentType(MediaType.APPLICATION_JSON)
        .content("{\"type\":\"" + type + "\",\"openingBalanceMinor\":0,\"balanceMinor\":" + balance + "}");
  }

  @Test
  void balanceMinorSetsAnExistingWalletsAvailableBalanceWithAnAdjustingDoubleEntry() {
    long before = total();
    long settlement = jdbc.queryForObject("select balance_minor from wallet where id = 'SETTLEMENT'", Long.class);

    assertThat(setBalance("01711000001", "CUSTOMER", 250)).hasStatus(200).bodyJson()
        .isLenientlyEqualTo("{\"walletId\":\"01711000001\",\"balanceMinor\":250,\"heldMinor\":0,\"availableMinor\":250}");

    assertThat(total()).as("money only moves, between the wallet and SETTLEMENT").isEqualTo(before);
    assertThat(jdbc.queryForObject("select balance_minor from wallet where id = 'SETTLEMENT'", Long.class))
        .isEqualTo(settlement + 1_000_000 - 250);
    assertThat(jdbc.queryForList(
            "select wallet_id || '|' || direction || '|' || kind || '|' || amount_minor from entry order by id", String.class))
        .containsExactly("01711000001|DEBIT|ADJUSTMENT|999750", "SETTLEMENT|CREDIT|ADJUSTMENT|999750");

    assertThat(setBalance("01711000001", "CUSTOMER", 5_000)).hasStatus(200).bodyJson()
        .extractingPath("$.balanceMinor").isEqualTo(5_000);
    assertThat(total()).isEqualTo(before);
    assertThat(jdbc.queryForObject("select count(*) from entry", Integer.class)).isEqualTo(4);

    assertThat(setBalance("01711000001", "CUSTOMER", 5_000)).hasStatus(200);
    assertThat(jdbc.queryForObject("select count(*) from entry", Integer.class)).as("already there: no entry").isEqualTo(4);
  }

  @Test
  void balanceMinorIsTheAvailableBalanceSoAHoldStaysCovered() {
    jdbc.update("update wallet set held_minor = 300 where id = ?", "01711000002");

    assertThat(setBalance("01711000002", "CUSTOMER", 1_000)).hasStatus(200).bodyJson()
        .isLenientlyEqualTo("{\"balanceMinor\":1300,\"heldMinor\":300,\"availableMinor\":1000}");
  }

  @Test
  void aNewWalletWithBalanceMinorIsCreatedAtThatBalance() {
    long before = total();

    assertThat(mvc.put().uri(URI + "01712000009").contentType(MediaType.APPLICATION_JSON)
            .content("{\"type\":\"CUSTOMER\",\"openingBalanceMinor\":100,\"balanceMinor\":900}"))
        .hasStatus(201).bodyJson().extractingPath("$.balanceMinor").isEqualTo(900);
    assertThat(total()).as("the opening balance is new money as before, the rest an adjustment").isEqualTo(before + 100);
  }

  @Test
  void withoutBalanceMinorAnExistingWalletKeepsItsBalance() {
    assertThat(put("01711000001", "CUSTOMER", 1)).hasStatus(200).bodyJson().extractingPath("$.balanceMinor").isEqualTo(1000000);
    assertThat(jdbc.queryForObject("select count(*) from entry", Integer.class)).isZero();
  }

  @Test
  void theMocksOwnWalletsAreServedWithTheirNames() {
    assertThat(mvc.get().uri(URI + "01711000001")).hasStatusOk().bodyJson().extractingPath("$.holderName").isEqualTo("Rahim Uddin");
  }

  @Test
  void aSeededWalletOfTheMocksOwnRangeIsAnsweredAsItIs() {
    assertThat(put("01711000001", "CUSTOMER", 1)).hasStatus(200).bodyJson().extractingPath("$.balanceMinor").isEqualTo(1000000);
  }

  @Test
  void anIdThatIsNotAMobileNumberIs400() {
    assertThat(put("SETTLEMENT", "ORG", 1)).hasStatus(400).bodyJson().extractingPath("$.code").isEqualTo("MW-VAL-001");
    assertThat(put("0171200000", "CUSTOMER", 1)).hasStatus(400);
  }
}
