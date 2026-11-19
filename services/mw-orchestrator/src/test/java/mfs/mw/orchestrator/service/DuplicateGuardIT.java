package mfs.mw.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import mfs.mw.orchestrator.TestData;
import mfs.mw.orchestrator.TestInfrastructure;
import mfs.mw.orchestrator.client.IdempotencyLock;
import mfs.mw.orchestrator.dto.TxnRequest;
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
 * Business-duplicate window at intake (FRS PLT-013, ticket 99) against real PostgreSQL; Redis
 * is an in-memory SET NX stand-in.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import({TestInfrastructure.class, DuplicateGuardIT.Store.class})
class DuplicateGuardIT {

  static final class InMemoryStore implements IdempotencyLock {
    final Map<String, String> values = new ConcurrentHashMap<>();

    @Override
    public Result tryAcquire(String key, String value, Duration ttl) {
      String held = values.putIfAbsent(key, value);
      return held == null ? new Result.Acquired() : new Result.Held(held);
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class Store {
    @Bean
    @Primary
    InMemoryStore inMemoryDuplicateStore() {
      return new InMemoryStore();
    }
  }

  private final TransactionService transactions;
  private final InMemoryStore store;
  private final JdbcTemplate jdbc;
  private final JsonMapper json;

  @Autowired
  DuplicateGuardIT(TransactionService transactions, InMemoryStore store, JdbcTemplate jdbc, JsonMapper json) {
    this.transactions = transactions;
    this.store = store;
    this.jdbc = jdbc;
    this.json = json;
  }

  @BeforeEach
  void clean() {
    store.values.clear();
    for (String t : List.of("outbox", "inbox", "saga_timer", "saga_step", "idempotency", "transaction")) {
      jdbc.update("delete from " + t);
    }
  }

  private long count(String table) {
    return jdbc.queryForObject("select count(*) from " + table, Long.class);
  }

  private long coreCommands() {
    return jdbc.queryForObject("select count(*) from outbox where topic like 'core.%.command.v1'", Long.class);
  }

  private JsonNode lastStatus() {
    return json.readTree(
        jdbc.queryForList("select payload::text from outbox where topic = 'mw.txn.status.v1' order by id", String.class)
            .getLast());
  }

  private static TxnRequest on(TxnRequest r, String channel, Boolean confirm) {
    return new TxnRequest(
        r.schemaVersion(), r.txnId(), r.idempotencyKey(), r.correlationId(), channel, r.txnType(),
        r.initiator(), r.debitParty(), r.creditParty(), r.amount(), r.partner(), r.originalTxnId(),
        r.assertion(), r.locale(), r.metadata(), r.attributes(), r.requestedAt(), confirm);
  }

  @Test
  void anAppDuplicateGetsTheConfirmablePromptAndNoCoreCall() {
    transactions.accept(TestData.p2p(TestData.txnId(), "dup-app-1", 5_000), "gw-1");
    long commandsAfterFirst = coreCommands();
    TxnRequest second = TestData.p2p(TestData.txnId(), "dup-app-2", 5_000);

    transactions.accept(second, "gw-1");

    JsonNode answer = lastStatus();
    assertThat(answer.path("txnId").asString()).isEqualTo(second.txnId());
    assertThat(answer.path("status").asString()).isEqualTo("REJECTED");
    assertThat(answer.path("error").path("code").asString()).isEqualTo("MW-VAL-024");
    assertThat(answer.path("error").path("retryable").asBoolean()).isFalse();
    assertThat(count("transaction")).isEqualTo(1);
    assertThat(count("idempotency")).as("the key is not consumed").isEqualTo(1);
    assertThat(coreCommands()).isEqualTo(commandsAfterFirst);
  }

  @Test
  void theConfirmedResendUnderTheSameKeyGoesThrough() {
    transactions.accept(TestData.p2p(TestData.txnId(), "dup-app-3", 5_000), "gw-1");
    transactions.accept(TestData.p2p(TestData.txnId(), "dup-app-4", 5_000), "gw-1");

    TxnRequest confirmed = on(TestData.p2p(TestData.txnId(), "dup-app-4", 5_000), "CUSTOMER_APP", true);
    transactions.accept(confirmed, "gw-1");

    assertThat(count("transaction")).isEqualTo(2);
    JsonNode answer = lastStatus();
    assertThat(answer.path("txnId").asString()).isEqualTo(confirmed.txnId());
    assertThat(answer.path("status").asString()).isEqualTo("VALIDATING");
  }

  @Test
  void aCheckoutDuplicateIsRejectedEvenWhenConfirmed() {
    transactions.accept(on(TestData.p2p(TestData.txnId(), "dup-co-1", 5_000), "WEB_CHECKOUT", null), "co-1");
    long commandsAfterFirst = coreCommands();
    TxnRequest second = on(TestData.p2p(TestData.txnId(), "dup-co-2", 5_000), "WEB_CHECKOUT", true);

    transactions.accept(second, "co-1");

    JsonNode answer = lastStatus();
    assertThat(answer.path("txnId").asString()).isEqualTo(second.txnId());
    assertThat(answer.path("error").path("code").asString()).isEqualTo("MW-VAL-025");
    assertThat(count("transaction")).isEqualTo(1);
    assertThat(coreCommands()).isEqualTo(commandsAfterFirst);
  }

  @Test
  void aSameKeyRepeatIsStillAnsweredByIdempotency() {
    TxnRequest first = TestData.p2p(TestData.txnId(), "dup-same-1", 5_000);
    transactions.accept(first, "gw-1");

    transactions.accept(TestData.p2p(TestData.txnId(), "dup-same-1", 5_000), "gw-1");

    JsonNode answer = lastStatus();
    assertThat(answer.path("txnId").asString()).isEqualTo(first.txnId());
    assertThat(answer.path("status").asString()).isEqualTo("VALIDATING");
  }
}
