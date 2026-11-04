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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * Black-box tests (e2e) read a wallet's balances through the mock's test/admin API to prove money
 * moved exactly once (MVP acceptance criterion 2).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestInfrastructure.class)
class WalletControllerIT {

  private static final String URI = "/core-mock/v1/wallets/";

  private final MockMvcTester mvc;
  private final WalletSeeder seeder;
  private final JdbcTemplate jdbc;

  @Autowired
  WalletControllerIT(MockMvcTester mvc, WalletSeeder seeder, JdbcTemplate jdbc) {
    this.mvc = mvc;
    this.seeder = seeder;
    this.jdbc = jdbc;
  }

  @BeforeEach
  void seed() {
    seeder.reset();
  }

  @Test
  void readsTheSeededBalanceOfACustomerWallet() {
    assertThat(mvc.get().uri(URI + "01711000001"))
        .hasStatusOk()
        .bodyJson()
        .isLenientlyEqualTo(
            """
            {"walletId":"01711000001","type":"CUSTOMER","status":"ACTIVE",
             "balanceMinor":1000000,"heldMinor":0,"availableMinor":1000000}
            """);
  }

  @Test
  void availableIsTheBalanceLessWhatHoldsReserve() {
    jdbc.update("update wallet set held_minor = 2500 where id = ?", "01711000002");

    assertThat(mvc.get().uri(URI + "01711000002"))
        .hasStatusOk()
        .bodyJson()
        .isLenientlyEqualTo("{\"balanceMinor\":1000000,\"heldMinor\":2500,\"availableMinor\":997500}");
  }

  @Test
  void readsASystemWalletById() {
    assertThat(mvc.get().uri(URI + "FEE-POOL"))
        .hasStatusOk()
        .bodyJson()
        .isLenientlyEqualTo("{\"walletId\":\"FEE-POOL\",\"type\":\"SYSTEM\"}");
  }

  @Test
  void anUnknownWalletIsNotFound() {
    assertThat(mvc.get().uri(URI + "01799999999"))
        .hasStatus(400)
        .bodyJson()
        .extractingPath("$.code")
        .isEqualTo("MW-VAL-003");
  }
}
