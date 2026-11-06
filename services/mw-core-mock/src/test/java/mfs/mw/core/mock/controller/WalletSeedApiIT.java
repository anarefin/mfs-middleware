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
import org.springframework.security.crypto.password.PasswordEncoder;
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
  private final PasswordEncoder encoder;

  @Autowired
  WalletSeedApiIT(MockMvcTester mvc, WalletSeeder seeder, JdbcTemplate jdbc, PasswordEncoder encoder) {
    this.mvc = mvc;
    this.seeder = seeder;
    this.jdbc = jdbc;
    this.encoder = encoder;
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
  void createsTheWalletOnceWithTheSeedPinAndLeavesItAloneAfterwards() {
    assertThat(put("01712000001", "AGENT", 7000)).hasStatus(201).bodyJson()
        .isLenientlyEqualTo("{\"walletId\":\"01712000001\",\"type\":\"AGENT\",\"status\":\"ACTIVE\",\"balanceMinor\":7000}");
    assertThat(mvc.get().uri(URI + "01712000001")).hasStatusOk();
    String hash = jdbc.queryForObject("select pin_hash from wallet where id = ?", String.class, "01712000001");
    assertThat(encoder.matches("1234", hash)).isTrue();
    assertThat(jdbc.queryForObject("select msisdn from wallet where id = ?", String.class, "01712000001")).isEqualTo("01712000001");

    jdbc.update("update wallet set balance_minor = 5 where id = ?", "01712000001");
    assertThat(put("01712000001", "AGENT", 7000)).hasStatus(200).bodyJson().extractingPath("$.balanceMinor").isEqualTo(5);
    assertThat(jdbc.queryForObject("select count(*) from entry where wallet_id = ?", Integer.class, "01712000001")).isZero();
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
