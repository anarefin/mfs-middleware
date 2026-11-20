package mfs.mw.app.gateway.service;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.util.List;
import java.util.Map;
import mfs.mw.app.gateway.TestInfrastructure;
import mfs.mw.app.gateway.client.ServiceTokenClient;
import mfs.mw.app.gateway.exception.MwException;
import mfs.mw.app.gateway.util.AccountCipher;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Linking a bank account (ticket 117b, FRS FL-08, UC-14) against PostgreSQL and a WireMock
 * mw-adapter-bank: a valid AVL links the account, stored encrypted with its last 4 shown; a refused
 * AVL links nothing and passes the bank's code through.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {"mw.app-gateway.consumers.enabled=false", "mw.outbox.relay.enabled=false"})
@Import(TestInfrastructure.class)
class LinkBankAccountIT {

  static final String WALLET = "01711000001";
  static final String ACCOUNT = "1234567890";
  static final String KEY = "test-only-linked-account-key-not-a-secret";
  static final WireMockServer BANK = new WireMockServer(options().dynamicPort());

  static {
    BANK.start();
  }

  @DynamicPropertySource
  static void bank(DynamicPropertyRegistry r) {
    r.add("mw.app-gateway.bank.base-url", BANK::baseUrl);
  }

  @AfterAll
  static void stop() {
    BANK.stop();
  }

  @MockitoBean ServiceTokenClient tokens;

  private final LinkedAccountService service;
  private final JdbcTemplate jdbc;

  @Autowired
  LinkBankAccountIT(LinkedAccountService service, JdbcTemplate jdbc) {
    this.service = service;
    this.jdbc = jdbc;
  }

  @BeforeEach
  void reset() {
    BANK.resetAll();
    jdbc.update("delete from linked_bank_account");
    when(tokens.token()).thenReturn("svc-token");
  }

  @Test
  void aValidAccountIsLinkedEncryptedWithItsLastFourShown() {
    BANK.stubFor(post(urlEqualTo("/bank/v1/accounts/validate"))
        .willReturn(okJson("{\"bankCode\":\"dbbl\",\"accountLast4\":\"7890\",\"holderName\":\"R.\"}")));

    var linked = service.link(WALLET, "dbbl", ACCOUNT);

    assertThat(linked.accountLast4()).isEqualTo("7890");
    assertThat(linked.holderName()).isEqualTo("R.");
    BANK.verify(postRequestedFor(urlEqualTo("/bank/v1/accounts/validate"))
        .withHeader("Authorization", equalTo("Bearer svc-token"))
        .withRequestBody(equalToJson("{\"bankCode\":\"dbbl\",\"accountNumber\":\"1234567890\"}")));

    Map<String, Object> row = jdbc.queryForMap("select * from linked_bank_account where wallet_id = ?", WALLET);
    assertThat(row.values()).allSatisfy(v -> assertThat(String.valueOf(v)).doesNotContain(ACCOUNT));
    assertThat(row.get("account_last4")).isEqualTo("7890");
    assertThat(new AccountCipher(KEY).decrypt((String) row.get("account_ciphertext"), WALLET)).isEqualTo(ACCOUNT);

    assertThat(service.list(WALLET).accounts()).extracting(a -> a.accountLast4()).containsExactly("7890");
    assertThat(service.list("01711000002").accounts()).isEmpty();
  }

  @Test
  void theSameAccountTwiceIsAConflict() {
    BANK.stubFor(post(urlEqualTo("/bank/v1/accounts/validate"))
        .willReturn(okJson("{\"bankCode\":\"dbbl\",\"accountLast4\":\"7890\",\"holderName\":\"R.\"}")));
    service.link(WALLET, "dbbl", ACCOUNT);

    assertThatThrownBy(() -> service.link(WALLET, "dbbl", ACCOUNT))
        .isInstanceOfSatisfying(MwException.class, e -> assertThat(e.code()).isEqualTo("MW-IDEM-001"));
  }

  @Test
  void aRefusedAccountLinksNothing() {
    BANK.stubFor(post(urlEqualTo("/bank/v1/accounts/validate"))
        .willReturn(aResponse().withStatus(502).withHeader("Content-Type", "application/json")
            .withBody("{\"code\":\"MW-PTR-BANK_DBBL-ACCOUNT_NOT_FOUND\",\"message\":\"Account not found\","
                + "\"correlationId\":\"c\",\"retryable\":false,\"details\":[]}")));

    assertThatThrownBy(() -> service.link(WALLET, "dbbl", ACCOUNT))
        .isInstanceOfSatisfying(MwException.class, e -> {
          assertThat(e.code()).isEqualTo("MW-PTR-BANK_DBBL-ACCOUNT_NOT_FOUND");
          assertThat(e.status()).isEqualTo(HttpStatus.BAD_GATEWAY);
        });
    assertThat(jdbc.queryForObject("select count(*) from linked_bank_account", Integer.class)).isZero();
  }

  @Test
  void anUnreachableAdapterIs503AndLinksNothing() {
    BANK.stubFor(post(urlEqualTo("/bank/v1/accounts/validate")).willReturn(aResponse().withStatus(500)));

    assertThatThrownBy(() -> service.link(WALLET, "dbbl", ACCOUNT))
        .isInstanceOfSatisfying(MwException.class, e -> {
          assertThat(e.code()).isEqualTo("MW-SYS-016");
          assertThat(e.retryable()).isTrue();
        });
    assertThat(jdbc.queryForList("select id from linked_bank_account")).isEqualTo(List.of());
  }

  @Test
  void anUnlinkOfAnotherWalletsAccountIsNotFound() {
    BANK.stubFor(post(urlEqualTo("/bank/v1/accounts/validate"))
        .willReturn(okJson("{\"bankCode\":\"dbbl\",\"accountLast4\":\"7890\",\"holderName\":\"R.\"}")));
    var linked = service.link(WALLET, "dbbl", ACCOUNT);

    assertThatThrownBy(() -> service.unlink("01711000002", linked.id()))
        .isInstanceOfSatisfying(MwException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND));
    service.unlink(WALLET, linked.id());
    assertThat(service.list(WALLET).accounts()).isEmpty();
  }

  @Test
  void aLinkedAccountResolvesToItsNumberForItsOwnWalletOnly() {
    BANK.stubFor(post(urlEqualTo("/bank/v1/accounts/validate"))
        .willReturn(okJson("{\"bankCode\":\"dbbl\",\"accountLast4\":\"7890\",\"holderName\":\"R.\"}")));
    var linked = service.link(WALLET, "dbbl", ACCOUNT);

    var resolved = service.resolve(WALLET, linked.id()).orElseThrow();
    assertThat(resolved.accountNumber()).isEqualTo(ACCOUNT);
    assertThat(resolved.partnerId()).isEqualTo("bank-dbbl");
    assertThat(resolved.toString()).doesNotContain(ACCOUNT).contains("****7890");
    assertThat(service.resolve("01711000002", linked.id())).isEmpty();
    assertThat(service.resolve(WALLET, "not-a-uuid")).isEmpty();
    service.unlink(WALLET, linked.id());
    assertThat(service.resolve(WALLET, linked.id())).isEmpty();
  }
}
