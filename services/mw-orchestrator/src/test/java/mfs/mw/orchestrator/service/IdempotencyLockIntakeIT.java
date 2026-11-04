package mfs.mw.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import mfs.mw.orchestrator.TestData;
import mfs.mw.orchestrator.TestInfrastructure;
import mfs.mw.orchestrator.client.IdempotencyLock;
import mfs.mw.orchestrator.dto.TxnRequest;
import mfs.mw.orchestrator.mapper.RequestMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Intake with the Valkey idempotency lock (spec 7.3 rule 4, ticket 50) against real PostgreSQL;
 * the lock itself is an in-memory stand-in that can be switched off like an unreachable Valkey.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import({TestInfrastructure.class, IdempotencyLockIntakeIT.Lock.class})
class IdempotencyLockIntakeIT {

  /** SET NX in memory; {@code down} answers like an unreachable server. */
  static final class InMemoryLock implements IdempotencyLock {
    final Map<String, String> values = new ConcurrentHashMap<>();
    volatile boolean down;

    @Override
    public Result tryAcquire(String key, String value, Duration ttl) {
      if (down) {
        return new Result.Unavailable("RedisConnectionFailureException");
      }
      String held = values.putIfAbsent(key, value);
      return held == null ? new Result.Acquired() : new Result.Held(held);
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class Lock {
    @Bean
    @Primary
    InMemoryLock inMemoryIdempotencyLock() {
      return new InMemoryLock();
    }
  }

  private final TransactionService transactions;
  private final IdempotencyGate gate;
  private final RequestMapper requests;
  private final InMemoryLock lock;
  private final JdbcTemplate jdbc;
  private final JsonMapper json;

  @Autowired
  IdempotencyLockIntakeIT(
      TransactionService transactions,
      IdempotencyGate gate,
      RequestMapper requests,
      InMemoryLock lock,
      JdbcTemplate jdbc,
      JsonMapper json) {
    this.transactions = transactions;
    this.gate = gate;
    this.requests = requests;
    this.lock = lock;
    this.jdbc = jdbc;
    this.json = json;
  }

  @BeforeEach
  void clean() {
    lock.values.clear();
    lock.down = false;
    for (String t : List.of("outbox", "inbox", "saga_timer", "saga_step", "idempotency", "transaction")) {
      jdbc.update("delete from " + t);
    }
  }

  private long count(String table) {
    return jdbc.queryForObject("select count(*) from " + table, Long.class);
  }

  private List<JsonNode> statuses() {
    return jdbc.queryForList("select payload::text from outbox where topic = 'mw.txn.status.v1' order by id", String.class)
        .stream()
        .map(json::readTree)
        .toList();
  }

  @Test
  void aSecondConcurrentFirstRequestIsADuplicateWithoutASecondClaimAttempt() {
    TxnRequest first = TestData.p2p(TestData.txnId(), "key-lock-1", 5_000);
    TxnRequest second = TestData.p2p(TestData.txnId(), "key-lock-1", 5_000);
    // The first request holds the lock and has not committed its claim yet.
    lock.tryAcquire(
        gate.key(first.channel(), first.initiator().id(), first.idempotencyKey()),
        first.txnId() + " " + java.util.HexFormat.of().formatHex(requests.requestHash(first)),
        Duration.ofSeconds(60));

    transactions.accept(second, "gw-1");

    assertThat(count("idempotency")).as("no claim attempt").isZero();
    assertThat(count("transaction")).isZero();
    JsonNode answer = statuses().getLast();
    assertThat(answer.path("txnId").asString()).isEqualTo(first.txnId());
    assertThat(answer.path("status").asString()).isEqualTo("RECEIVED");
    assertThat(answer.path("attributes").path("requestTxnId").asString()).isEqualTo(second.txnId());
    assertThat(answer.path("attributes").path("duplicate").asString()).isEqualTo("true");
  }

  @Test
  void aRepeatWhileTheLockIsHeldGetsTheFirstTransactionsCurrentStatus() {
    TxnRequest first = TestData.p2p(TestData.txnId(), "key-lock-2", 5_000);
    TxnRequest second = TestData.p2p(TestData.txnId(), "key-lock-2", 5_000);

    transactions.accept(first, "gw-1");
    transactions.accept(second, "gw-1");

    assertThat(count("idempotency")).isEqualTo(1);
    assertThat(count("transaction")).isEqualTo(1);
    JsonNode answer = statuses().getLast();
    assertThat(answer.path("txnId").asString()).isEqualTo(first.txnId());
    assertThat(answer.path("status").asString()).isEqualTo("VALIDATING");
    assertThat(answer.path("attributes").path("requestTxnId").asString()).isEqualTo(second.txnId());
  }

  @Test
  void otherContentUnderAHeldKeyIsRefusedWithIdem001() {
    TxnRequest first = TestData.p2p(TestData.txnId(), "key-lock-3", 5_000);
    TxnRequest other = TestData.p2p(TestData.txnId(), "key-lock-3", 6_000);

    transactions.accept(first, "gw-1");
    transactions.accept(other, "gw-1");

    JsonNode answer = statuses().getLast();
    assertThat(answer.path("txnId").asString()).isEqualTo(other.txnId());
    assertThat(answer.path("status").asString()).isEqualTo("REJECTED");
    assertThat(answer.path("error").path("code").asString()).isEqualTo("MW-IDEM-001");
  }

  @Test
  void withValkeyDownRequestsStillSucceedOnThePrimaryKey() {
    lock.down = true;
    TxnRequest first = TestData.p2p(TestData.txnId(), "key-lock-4", 5_000);
    TxnRequest second = TestData.p2p(TestData.txnId(), "key-lock-4", 5_000);

    transactions.accept(first, "gw-1");
    transactions.accept(second, "gw-1");

    assertThat(count("transaction")).isEqualTo(1);
    assertThat(count("idempotency")).isEqualTo(1);
    assertThat(jdbc.queryForObject("select status from transaction where txn_id = ?", String.class, UUID.fromString(first.txnId())))
        .isEqualTo("VALIDATING");
    JsonNode answer = statuses().getLast();
    assertThat(answer.path("txnId").asString()).isEqualTo(first.txnId());
    assertThat(answer.path("attributes").path("requestTxnId").asString()).isEqualTo(second.txnId());
  }
}
