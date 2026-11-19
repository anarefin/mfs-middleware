package mfs.mw.core.mock.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import mfs.mw.core.mock.TestInfrastructure;
import mfs.mw.core.mock.contracts.ContractSchemas;
import mfs.mw.core.mock.dto.CoreCommand;
import mfs.mw.core.mock.dto.CoreEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Hold expiry (ticket 94, FRS 7.3): a hold not committed within {@code mw.coremock.hold-ttl} (30 min)
 * is released by {@link HoldExpiry}, {@code HoldExpired} is published once on {@code
 * core.payment.event.v1} and the available balance is restored. The holds are aged in SQL; the job's
 * schedule is off in tests.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestInfrastructure.class)
class HoldExpiryIT {

  static final String A = "01711000001";

  private final CoreCommandService service;
  private final HoldExpiry expiry;
  private final WalletSeeder seeder;
  private final JdbcTemplate jdbc;
  private final JsonMapper json;
  private final CoreCommandsTestSupport cmds;

  @Autowired
  HoldExpiryIT(CoreCommandService service, HoldExpiry expiry, WalletSeeder seeder, JdbcTemplate jdbc, JsonMapper json) {
    this.service = service;
    this.expiry = expiry;
    this.seeder = seeder;
    this.jdbc = jdbc;
    this.json = json;
    this.cmds = new CoreCommandsTestSupport(json);
  }

  @BeforeEach
  void freshLedger() {
    seeder.reset();
    jdbc.update("delete from outbox");
  }

  private CoreEvent handle(CoreCommand c) {
    return cmds.valid(service.handle(c).orElseThrow(), c);
  }

  private long available(String id) {
    return jdbc.queryForObject("select balance_minor - held_minor from wallet where id = ?", Long.class, id);
  }

  private String hold(String txn, long minor) {
    CoreEvent held = handle(cmds.holdFunds(txn, A, minor));
    assertThat(held.eventType()).isEqualTo("FundsHeld");
    return json.valueToTree(held.payload()).path("holdId").asString();
  }

  private void age(String holdId) {
    jdbc.update("update hold set expires_at = now() - interval '1 second' where id = ?", holdId);
  }

  private List<JsonNode> expiredEvents(String txn) {
    return jdbc.queryForList(
            "select payload::text from outbox where topic = ? and payload->>'eventType' = 'HoldExpired' and msg_key = ?",
            String.class, CoreCommandService.PAYMENT_EVENT_TOPIC, txn)
        .stream().map(json::readTree).toList();
  }

  @Test
  void aHoldLivesThirtyMinutesByDefault() {
    String holdId = hold(CoreCommandsTestSupport.txnId(), 10_000);

    Long seconds = jdbc.queryForObject(
        "select extract(epoch from expires_at - created_at)::bigint from hold where id = ?", Long.class, holdId);
    assertThat(Duration.ofSeconds(seconds)).isBetween(Duration.ofMinutes(29), Duration.ofMinutes(31));
  }

  @Test
  void anExpiredHoldIsReleasedPublishedOnceAndTheAvailableBalanceRestored() {
    long before = available(A);
    String txn = CoreCommandsTestSupport.txnId();
    String holdId = hold(txn, 100_000);
    assertThat(available(A)).isEqualTo(before - 100_000 - 200);
    age(holdId);

    assertThat(expiry.expireDue()).isEqualTo(1);
    assertThat(expiry.expireDue()).as("a second run finds nothing").isZero();

    assertThat(available(A)).isEqualTo(before);
    assertThat(jdbc.queryForObject("select status from hold where id = ?", String.class, holdId)).isEqualTo("EXPIRED");
    assertThat(jdbc.queryForObject("select count(*) from entry where txn_id = ?", Integer.class, txn))
        .as("no money moved").isZero();
    List<JsonNode> events = expiredEvents(txn);
    assertThat(events).hasSize(1);
    JsonNode e = events.getFirst();
    assertThat(ContractSchemas.schema("events/mw-core-mock/core.event.v1.schema.json").validate(e)).isEmpty();
    assertThat(e.path("commandId").asString()).isEqualTo("hold-expiry:" + holdId);
    assertThat(e.path("txnId").asString()).isEqualTo(txn);
    assertThat(e.path("payload").path("holdId").asString()).isEqualTo(holdId);
    assertThat(e.path("payload").path("amount").path("valueMinor").asLong()).isEqualTo(100_000);
    assertThat(e.path("balancesAfter").get(0).path("walletId").asString()).isEqualTo(A);
  }

  @Test
  void aHoldThatIsNotDueOrNoLongerHeldIsLeftAlone() {
    String txn = CoreCommandsTestSupport.txnId();
    String due = hold(txn, 10_000);
    String committedTxn = CoreCommandsTestSupport.txnId();
    String committed = hold(committedTxn, 10_000);
    handle(cmds.commitHold(committedTxn, committed));
    age(committed);

    assertThat(expiry.expireDue()).isZero();
    assertThat(jdbc.queryForObject("select status from hold where id = ?", String.class, due)).isEqualTo("HELD");
    assertThat(jdbc.queryForObject("select status from hold where id = ?", String.class, committed)).isEqualTo("COMMITTED");
  }

  @Test
  void anExpiredHoldCannotBeCommittedReleasesWithoutEffectAndReadsReleased() {
    long before = available(A);
    String txn = CoreCommandsTestSupport.txnId();
    String holdId = hold(txn, 50_000);
    age(holdId);
    expiry.expireDue();

    CoreEvent commit = handle(cmds.commitHold(txn, holdId));
    assertThat(commit.eventType()).isEqualTo("CommitFailed");
    assertThat(commit.reasonCode()).isEqualTo("HOLD_EXPIRED");
    assertThat(handle(cmds.releaseHold(txn, holdId)).eventType()).isEqualTo("HoldReleased");
    assertThat(available(A)).isEqualTo(before);
    CoreEvent status = handle(cmds.query(txn));
    assertThat(json.valueToTree(status.payload()).path("txnStatus").asString()).isEqualTo("RELEASED");
  }
}
