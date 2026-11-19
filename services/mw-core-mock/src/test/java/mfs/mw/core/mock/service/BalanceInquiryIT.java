package mfs.mw.core.mock.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import mfs.mw.core.mock.TestInfrastructure;
import mfs.mw.core.mock.dto.Amount;
import mfs.mw.core.mock.dto.CoreCommand;
import mfs.mw.core.mock.dto.CoreEvent;
import mfs.mw.core.mock.dto.FaultRequest;
import mfs.mw.core.mock.dto.Fault.FaultMode;
import mfs.mw.core.mock.dto.Party;
import mfs.mw.core.mock.service.mockcontrol.LegacyFaults;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * BalanceInquiry (ADR D26) against a real PostgreSQL ledger: the available balance (balance minus
 * holds) of an active wallet authorised by mw-auth's assertion, never a ledger entry; refusals
 * WALLET_NOT_FOUND, WALLET_NOT_ACTIVE, MW-AUTH-046; idempotent on commandId; REJECT faults apply.
 * Every event is checked against {@code core.event.v1}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestInfrastructure.class)
class BalanceInquiryIT {

  static final String A = "01711000001";
  static final String B = "01711000002";

  private final CoreCommandService service;
  private final LegacyFaults faults;
  private final WalletSeeder seeder;
  private final JdbcTemplate jdbc;
  private final JsonMapper json;
  private final CoreCommandsTestSupport cmds;

  @Autowired
  BalanceInquiryIT(
      CoreCommandService service, LegacyFaults faults, WalletSeeder seeder, JdbcTemplate jdbc, JsonMapper json) {
    this.service = service;
    this.faults = faults;
    this.seeder = seeder;
    this.jdbc = jdbc;
    this.json = json;
    this.cmds = new CoreCommandsTestSupport(json);
  }

  @BeforeEach
  void fresh() {
    faults.reset();
    seeder.reset();
    jdbc.update("delete from outbox");
  }

  @AfterEach
  void clear() {
    faults.reset();
  }

  private CoreEvent handle(CoreCommand c) {
    return cmds.valid(service.handle(c).orElseThrow(), c);
  }

  private long entries() {
    return jdbc.queryForObject("select count(*) from entry", Long.class);
  }

  private JsonNode payload(CoreEvent e) {
    return json.valueToTree(e.payload());
  }

  @Test
  void anActiveWalletGetsItsAvailableBalanceAndNothingMoves() {
    long before = entries();
    CoreCommand c = cmds.balanceInquiry(CoreCommandsTestSupport.txnId(), A);

    CoreEvent e = handle(c);

    assertThat(e.eventType()).isEqualTo("BalanceReported");
    assertThat(e.outcome()).isEqualTo(CoreEvent.Outcome.SUCCESS);
    assertThat(e.commandId()).endsWith(":balance");
    assertThat(payload(e).path("walletId").asString()).isEqualTo(A);
    assertThat(payload(e).path("available").path("valueMinor").asLong()).isEqualTo(1_000_000);
    assertThat(payload(e).path("available").path("currency").asString()).isEqualTo("BDT");
    assertThat(Instant.parse(payload(e).path("asOf").asString())).isNotNull();
    assertThat(entries()).isEqualTo(before);
    assertThat(jdbc.queryForObject("select balance_minor from wallet where id = ?", Long.class, A)).isEqualTo(1_000_000);
  }

  @Test
  void theAvailableBalanceLeavesOutWhatAHoldReserves() {
    handle(cmds.holdFunds(CoreCommandsTestSupport.txnId(), A, 100_000));
    long held = jdbc.queryForObject("select held_minor from wallet where id = ?", Long.class, A);
    assertThat(held).isPositive();

    CoreEvent e = handle(cmds.balanceInquiry(CoreCommandsTestSupport.txnId(), A));

    assertThat(payload(e).path("available").path("valueMinor").asLong()).isEqualTo(1_000_000 - held);
  }

  @Test
  void anInquiryDoesNotUseTheDailyLimit() {
    for (int i = 0; i < 3; i++) {
      handle(cmds.balanceInquiry(CoreCommandsTestSupport.txnId(), A));
    }
    assertThat(jdbc.queryForObject("select count(*) from entry where wallet_id = ?", Long.class, A)).isZero();
  }

  @Test
  void anUnknownWalletIsRefused() {
    CoreEvent e = handle(cmds.balanceInquiry(CoreCommandsTestSupport.txnId(), "01799999997"));
    assertThat(e.eventType()).isEqualTo("BalanceInquiryRejected");
    assertThat(e.outcome()).isEqualTo(CoreEvent.Outcome.FAILURE);
    assertThat(e.reasonCode()).isEqualTo("WALLET_NOT_FOUND");
  }

  @Test
  void aBlockedOrLockedWalletIsRefusedNotActive() {
    jdbc.update("update wallet set status = 'BLOCKED' where id = ?", A);
    CoreEvent blocked = handle(cmds.balanceInquiry(CoreCommandsTestSupport.txnId(), A));
    assertThat(blocked.eventType()).isEqualTo("BalanceInquiryRejected");
    assertThat(blocked.reasonCode()).isEqualTo("WALLET_NOT_ACTIVE");
    assertThat(payload(blocked).path("walletId").asString()).isEqualTo(A);

    jdbc.update("update wallet set status = 'LOCKED' where id = ?", B);
    assertThat(handle(cmds.balanceInquiry(CoreCommandsTestSupport.txnId(), B)).reasonCode())
        .isEqualTo("WALLET_NOT_ACTIVE");
  }

  @Test
  void anAssertionOfAnotherWalletIsRefused() {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("txnType", "BALANCE");
    p.put("debitParty", new Party("WALLET", A, null, "CUSTOMER"));
    p.put("creditParty", new Party("WALLET", A, null, "CUSTOMER"));
    p.put("amount", Amount.bdt(0));
    CoreCommandsTestSupport.authorised(p, B);
    String txn = CoreCommandsTestSupport.txnId();

    CoreEvent e = handle(cmds.command("BalanceInquiry", txn, txn + ":balance", p));

    assertThat(e.eventType()).isEqualTo("BalanceInquiryRejected");
    assertThat(e.reasonCode()).isEqualTo("MW-AUTH-046");
  }

  @Test
  void anAssertionUsedByAnotherTxnIsRefused() {
    String first = CoreCommandsTestSupport.txnId();
    CoreCommand c = cmds.balanceInquiry(first, A);
    assertThat(handle(c).eventType()).isEqualTo("BalanceReported");

    String other = CoreCommandsTestSupport.txnId();
    CoreCommand reused =
        new CoreCommand(1, "BalanceInquiry", other + ":balance", other, "corr-" + other, c.issuedAt(), c.expiresAt(),
            c.payload());
    assertThat(handle(reused).reasonCode()).isEqualTo("MW-AUTH-046");
  }

  @Test
  void theSameCommandIdIsAnsweredFromTheLogEvenAfterTheBalanceChanged() {
    CoreCommand c = cmds.balanceInquiry(CoreCommandsTestSupport.txnId(), A);
    CoreEvent first = handle(c);

    handle(cmds.post(CoreCommandsTestSupport.txnId(), A, B, 10_000));
    CoreEvent replay = handle(c);

    assertThat(replay.eventId()).isEqualTo(first.eventId());
    assertThat(payload(replay).path("available").path("valueMinor").asLong()).isEqualTo(1_000_000);
    assertThat(jdbc.queryForObject("select count(*) from outbox where payload->>'eventType' = 'BalanceReported'",
        Long.class)).isEqualTo(2);
  }

  @Test
  void anInjectedRejectionRefusesWithTheGivenReason() {
    faults.add(new FaultRequest(FaultMode.REJECT, "BalanceInquiry", null, null, "CORE_UNAVAILABLE", null));

    CoreEvent e = handle(cmds.balanceInquiry(CoreCommandsTestSupport.txnId(), A));

    assertThat(e.eventType()).isEqualTo("BalanceInquiryRejected");
    assertThat(e.reasonCode()).isEqualTo("CORE_UNAVAILABLE");
  }
}
