package mfs.mw.app.gateway.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import mfs.mw.app.gateway.client.CoreRefusedException;
import mfs.mw.app.gateway.client.CoreUnavailableException;
import mfs.mw.app.gateway.client.CoreWalletClient;
import mfs.mw.app.gateway.config.SecurityConfig;
import mfs.mw.app.gateway.dto.Amount;
import mfs.mw.app.gateway.dto.CoreAccount;
import mfs.mw.app.gateway.dto.CoreLimits;
import mfs.mw.app.gateway.dto.CoreStatement;
import mfs.mw.app.gateway.dto.WalletLimits;
import mfs.mw.app.gateway.dto.WalletProfile;
import mfs.mw.app.gateway.security.AppUserResolver;
import mfs.mw.app.gateway.service.AccountReadService;
import mfs.mw.app.gateway.util.MaskingPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Profile, limits and the statement (ticket 107b). The wallet is the token's. A statement longer
 * than 90 days is refused before the core is asked. The owner sees the holder name; a log of the
 * profile keeps the initial.
 */
@WebMvcTest(AccountReadController.class)
@Import({SecurityConfig.class, AppUserResolver.class, AccountReadService.class})
class AccountReadControllerTest {

  static final String MINE = "01711000001";
  static final String OTHER = "01711000002";
  static final String TXN = "019a4f6e-1c2d-7a3b-8c4d-5e6f7a8b9c0d";
  static final Instant FROM = Instant.parse("2026-08-01T00:00:00Z");
  static final Instant NINETY = Instant.parse("2026-10-30T00:00:00Z");
  static final Instant TOO_LONG = Instant.parse("2026-10-30T00:00:01Z");

  private final MockMvcTester mvc;

  @MockitoBean CoreWalletClient core;

  @Autowired
  AccountReadControllerTest(MockMvcTester mvc) {
    this.mvc = mvc;
  }

  static RequestPostProcessor token(String msisdn) {
    return jwt().jwt(j -> j.claim("msisdn", msisdn).claim("role", "customer").claim("amr", List.of("pin")));
  }

  @Test
  void theOwnerSeesTheNameAndAnotherTokenReadsOnlyItsOwnWallet() throws Exception {
    when(core.account(MINE)).thenReturn(new CoreAccount(MINE, MINE, "CUSTOMER", "ACTIVE", "FULL", "Rahim Uddin"));
    when(core.account(OTHER)).thenReturn(new CoreAccount(OTHER, OTHER, "CUSTOMER", "ACTIVE", "BASIC", "Karim Ahmed"));

    MvcTestResult mine = mvc.get().uri("/app/v1/profile").with(token(MINE)).exchange();
    assertThat(mine).hasStatusOk();
    assertThat(mine).bodyJson().extractingPath("$.name").isEqualTo("Rahim Uddin");
    assertThat(mine).bodyJson().extractingPath("$.kycLevel").isEqualTo("FULL");
    assertThat(mine).bodyJson().extractingPath("$.status").isEqualTo("ACTIVE");
    assertThat(mine).bodyJson().extractingPath("$.type").isEqualTo("CUSTOMER");
    assertThat(mine).bodyJson().extractingPath("$.language").isEqualTo("bn");
    assertThat(mine.getResponse().getContentAsString(StandardCharsets.UTF_8)).doesNotContain(MINE);
    verify(core).account(MINE);
    verify(core, never()).account(OTHER);

    MvcTestResult other = mvc.get().uri("/app/v1/profile").with(token(OTHER)).exchange();
    assertThat(other).hasStatusOk().bodyJson().extractingPath("$.name").isEqualTo("Karim Ahmed");
    verify(core).account(OTHER);
  }

  @Test
  void theOwnersNameIsMaskedInALog() {
    String described = MaskingPolicy.describe(new WalletProfile("Rahim Uddin", "CUSTOMER", "ACTIVE", "FULL", "bn"));
    assertThat(described).contains("name=R.").doesNotContain("Rahim");
  }

  @Test
  void limitsAreWhatIsLeftBeforeAOneTakaP2pAndDoNotCarryTheWallet() throws Exception {
    when(core.limits(MINE, "P2P", 100L)).thenReturn(new CoreLimits(MINE, true, null,
        new WalletLimits.Remaining(new Amount(500_000L, "BDT"), new Amount(1_500_000L, "BDT"),
            new Amount(20_000_000L, "BDT"), 500L, 10_000L)));

    MvcTestResult r = mvc.get().uri("/app/v1/limits").with(token(MINE)).exchange();
    assertThat(r).hasStatusOk();
    assertThat(r).bodyJson().extractingPath("$.allowed").isEqualTo(true);
    assertThat(r).bodyJson().extractingPath("$.remaining.daily.valueMinor").isEqualTo(1_500_000);
    assertThat(r).bodyJson().extractingPath("$.remaining.perTxn.currency").isEqualTo("BDT");
    assertThat(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).doesNotContain(MINE);
    verify(core).limits(MINE, "P2P", 100L);
  }

  @Test
  void aStatementOfNinetyDaysListsTheEntryAndALongerRangeIsRefused() throws Exception {
    when(core.statement(eq(MINE), eq(FROM), eq(NINETY), eq(100), eq(0))).thenReturn(new CoreStatement(
        MINE, FROM, NINETY, 0, false, List.of(new CoreStatement.CoreEntry(
            TXN, MINE, "DEBIT", "TRANSFER", new Amount(100_000L, "BDT"), new Amount(900_000L, "BDT"), FROM))));

    MvcTestResult ok = mvc.get().uri("/app/v1/statement?from={from}&to={to}", FROM, NINETY).with(token(MINE)).exchange();
    assertThat(ok).hasStatusOk();
    assertThat(ok).bodyJson().extractingPath("$.entries[0].txnId").isEqualTo(TXN);
    assertThat(ok).bodyJson().extractingPath("$.entries[0].direction").isEqualTo("DEBIT");
    assertThat(ok).bodyJson().extractingPath("$.entries[0].amount.valueMinor").isEqualTo(100_000);
    assertThat(ok).bodyJson().extractingPath("$.entries[0].balanceAfter.valueMinor").isEqualTo(900_000);
    assertThat(ok).bodyJson().extractingPath("$.hasMore").isEqualTo(false);
    assertThat(ok.getResponse().getContentAsString(StandardCharsets.UTF_8)).doesNotContain(MINE);

    MvcTestResult csv = mvc.get().uri("/app/v1/statement?from={from}&to={to}", FROM, NINETY)
        .header("Accept", "text/csv").with(token(MINE)).exchange();
    assertThat(csv).hasStatusOk();
    assertThat(csv.getResponse().getHeader("Content-Type")).contains("text/csv");
    assertThat(csv.getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo(
        "at,txnId,direction,kind,amountMinor,balanceAfterMinor\n"
            + "2026-08-01T00:00:00Z,019a4f6e-1c2d-7a3b-8c4d-5e6f7a8b9c0d,DEBIT,TRANSFER,100000,900000\n");

    MvcTestResult refused = mvc.get().uri("/app/v1/statement?from={from}&to={to}", FROM, TOO_LONG).with(token(MINE)).exchange();
    assertThat(refused).hasStatus(400).bodyJson().extractingPath("$.code").isEqualTo("MW-VAL-001");
    assertThat(refused).bodyJson().extractingPath("$.details[0].field").isEqualTo("to");
    verify(core, never()).statement(eq(MINE), eq(FROM), eq(TOO_LONG), eq(100), eq(0));
  }

  @Test
  void aRangeThatDoesNotMoveForwardIs400() {
    MvcTestResult r = mvc.get().uri("/app/v1/statement?from={from}&to={to}", FROM, FROM).with(token(MINE)).exchange();
    assertThat(r).hasStatus(400).bodyJson().extractingPath("$.details[0].field").isEqualTo("from");
    verify(core, never()).statement(eq(MINE), eq(FROM), eq(FROM), eq(100), eq(0));
  }

  @Test
  void anUnknownWalletIs404AndTheCoreDownIs503() {
    doThrow(new CoreRefusedException(404, "MW-VAL-003")).when(core).account(MINE);
    MvcTestResult missing = mvc.get().uri("/app/v1/profile").with(token(MINE)).exchange();
    assertThat(missing).hasStatus(404).bodyJson().extractingPath("$.code").isEqualTo("MW-VAL-003");

    doThrow(new CoreRefusedException(422, "MW-CORE-WALLET_NOT_FOUND")).when(core).account(MINE);
    MvcTestResult refused = mvc.get().uri("/app/v1/profile").with(token(MINE)).exchange();
    assertThat(refused).hasStatus(422).bodyJson().extractingPath("$.code").isEqualTo("MW-CORE-WALLET_NOT_FOUND");

    for (RuntimeException failure : List.of(
        new CoreUnavailableException("down", null),
        CallNotPermittedException.createCallNotPermittedException(CircuitBreaker.ofDefaults("core")))) {
      doThrow(failure).when(core).limits(MINE, "P2P", 100L);
      MvcTestResult down = mvc.get().uri("/app/v1/limits").with(token(MINE)).exchange();
      assertThat(down).hasStatus(503);
      assertThat(down).bodyJson().extractingPath("$.code").isEqualTo("MW-SYS-015");
      assertThat(down).bodyJson().extractingPath("$.retryable").isEqualTo(true);
    }
  }

  @Test
  void noTokenIs401() {
    assertThat(mvc.get().uri("/app/v1/profile").exchange()).hasStatus(401);
    assertThat(mvc.get().uri("/app/v1/limits").exchange()).hasStatus(401);
    assertThat(mvc.get().uri("/app/v1/statement?from=2026-08-01T00:00:00Z&to=2026-08-02T00:00:00Z").exchange()).hasStatus(401);
  }
}
