package mfs.mw.audit.dw.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import mfs.mw.audit.dw.MutableClock;
import mfs.mw.audit.dw.TestInfrastructure;
import mfs.mw.audit.dw.dto.AuditEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * The audit trail (spec 14.3) through {@link AuditService#record} and {@link AuditChainVerifier}
 * against real PostgreSQL. The test connection is the container's superuser, which is what a
 * tamperer with full database access would have.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = "mw.audit-dw.consumers.enabled=false")
@Import({TestInfrastructure.class, AuditTrailIT.TestClock.class})
class AuditTrailIT {

  static final Instant NOV = Instant.parse("2026-11-03T08:15:31.123456789Z");

  @TestConfiguration(proxyBeanMethods = false)
  static class TestClock {
    @Bean
    @Primary
    MutableClock testClock() {
      return new MutableClock(NOV);
    }
  }

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final AuditService audit;
  private final AuditChainVerifier verifier;
  private final MutableClock clock;
  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;

  @Autowired
  AuditTrailIT(AuditService audit, Clock clock, JdbcTemplate jdbc, TransactionTemplate tx) {
    this.audit = audit;
    this.verifier = new AuditChainVerifier(jdbc);
    this.clock = (MutableClock) clock;
    this.jdbc = jdbc;
    this.tx = tx;
  }

  @BeforeEach
  void clean() {
    clock.set(NOV);
    // TRUNCATE does not fire the append-only row trigger; only the superuser test may do it.
    jdbc.execute("truncate event, event_key, chain_head");
  }

  static AuditEvent event(UUID auditId, String from, String to) {
    String txnId = "01933b8e-7c1a-7b2e-9f3d-2a4c5e6f7a8b";
    return JSON.readValue(
        """
        {"schemaVersion":1,"auditId":"%s","txnId":"%s","correlationId":"corr-1",
         "actor":{"type":"system","id":"mw-orchestrator"},"action":"txn.state.changed",
         "resource":"txn/%s","before":{"status":"%s"},"after":{"status":"%s"},
         "outcome":"SUCCESS","at":"2026-11-03T08:15:31.5Z","service":"mw-orchestrator"}
        """
            .formatted(auditId, txnId, txnId, from, to),
        AuditEvent.class);
  }

  private void recordStates(String... states) {
    for (int i = 1; i < states.length; i++) {
      audit.record(event(UUID.randomUUID(), states[i - 1], states[i]));
    }
  }

  private void asSuperuserBypassingTriggers(String sql, Object... args) {
    tx.executeWithoutResult(
        s -> {
          jdbc.execute("set local session_replication_role = replica");
          jdbc.update(sql, args);
        });
  }

  @Test
  void eachEventBecomesTheNextRowOfTheMonthsChain() {
    recordStates("RECEIVED", "VALIDATING", "POSTING", "COMPLETED");

    List<Map<String, Object>> rows =
        jdbc.queryForList("select seq, before->>'status' b, after->>'status' a from event where month = '2026-11' order by seq");
    assertThat(rows).extracting(r -> r.get("seq")).containsExactly(1L, 2L, 3L);
    assertThat(rows).extracting(r -> r.get("a")).containsExactly("VALIDATING", "POSTING", "COMPLETED");

    ChainVerification v = verifier.verify("2026-11");
    assertThat(v.intact()).as(v.toString()).isTrue();
    assertThat(v.rows()).isEqualTo(3);
  }

  @Test
  void theSameAuditIdDeliveredTwiceIsStoredOnce() {
    UUID id = UUID.randomUUID();
    assertThat(audit.record(event(id, "RECEIVED", "VALIDATING"))).isTrue();
    assertThat(audit.record(event(id, "RECEIVED", "VALIDATING"))).isFalse();

    assertThat(jdbc.queryForObject("select count(*) from event where audit_id = ?", Long.class, id)).isEqualTo(1);
    assertThat(verifier.verify("2026-11").intact()).isTrue();
  }

  /**
   * A txn id whose last group holds a 10-digit run (about one UUID in 40) is kept whole in the
   * scrubbed resource, while an NID or MSISDN next to it in free text is still masked.
   */
  @Test
  void aTxnResourceKeepsItsUuidWholeThroughTheScrub() {
    String txnId = "01a1aa26-3303-7169-8282-0e6132984047";
    audit.record(
        JSON.readValue(
            """
            {"schemaVersion":1,"auditId":"%s","txnId":"%s","correlationId":"%s",
             "actor":{"type":"system","id":"mw-orchestrator"},"action":"txn.state.changed",
             "resource":"txn/%s","before":{"status":"RECEIVED"},
             "after":{"status":"FAILED","reason":"nid 2345678901234 msisdn 01712345789"},
             "outcome":"SUCCESS","at":"2026-11-03T08:15:31.5Z","service":"mw-orchestrator"}
            """
                .formatted(UUID.randomUUID(), txnId, txnId, txnId),
            AuditEvent.class));

    Map<String, Object> row =
        jdbc.queryForMap("select resource, correlation_id, after->>'reason' reason from event");
    assertThat(row)
        .containsEntry("resource", "txn/" + txnId)
        .containsEntry("correlation_id", txnId)
        .containsEntry("reason", "nid ****234 msisdn 0171****789");
  }

  @Test
  void aRedeliveryMonthsLaterIsStillRefused() {
    UUID id = UUID.randomUUID();
    audit.record(event(id, "RECEIVED", "VALIDATING"));
    clock.set(Instant.parse("2027-03-01T00:00:00Z"));

    assertThat(audit.record(event(id, "RECEIVED", "VALIDATING"))).isFalse();
    assertThat(jdbc.queryForObject("select count(*) from event where audit_id = ?", Long.class, id)).isEqualTo(1);
  }

  @Test
  void aNewMonthGetsItsOwnPartitionAndChain() {
    clock.set(Instant.parse("2026-11-30T23:59:59.999Z"));
    recordStates("RECEIVED", "VALIDATING");
    clock.set(Instant.parse("2026-12-01T00:00:00Z"));
    recordStates("VALIDATING", "POSTING", "COMPLETED");

    assertThat(jdbc.queryForList(
            "select c.relname from pg_inherits i join pg_class c on c.oid = i.inhrelid "
                + "where i.inhparent = 'event'::regclass order by 1",
            String.class))
        .contains("event_2026_11", "event_2026_12");
    assertThat(jdbc.queryForObject("select count(*) from event_2026_12", Long.class)).isEqualTo(2);
    assertThat(jdbc.queryForList("select seq from event where month = '2026-12' order by seq", Long.class))
        .containsExactly(1L, 2L);
    assertThat(verifier.verify("2026-11").intact()).isTrue();
    assertThat(verifier.verify("2026-12").intact()).isTrue();
  }

  /** S6: the adapters' partner-call audit (spec 17) is stored and chained like any other event. */
  @Test
  void aPartnerCallAuditFromAnAdapterIsStoredInTheChain() {
    String txnId = "01933b8e-7c1a-7b2e-9f3d-2a4c5e6f7a8b";
    AuditEvent e =
        JSON.readValue(
            """
            {"schemaVersion":1,"auditId":"%s","txnId":"%s","correlationId":"corr-3",
             "actor":{"type":"system","id":"mw-adapter-telco"},"action":"partner.call.completed",
             "resource":"partner/gp/PAY","after":{"partnerInstance":"ocs","httpStatus":"200",
             "partnerOutcome":"SUCCESS","durationMs":"42","request":"msisdn 0171****001",
             "response":"status SUCCESS code 0000"},
             "outcome":"SUCCESS","at":"2026-11-03T08:15:31Z","service":"mw-adapter-telco"}
            """
                .formatted(UUID.randomUUID(), txnId),
            AuditEvent.class);

    assertThat(audit.record(e)).isTrue();

    Map<String, Object> row =
        jdbc.queryForMap(
            "select action, service, resource, after->>'partnerOutcome' po, after->>'request' req from event");
    assertThat(row.get("action")).isEqualTo("partner.call.completed");
    assertThat(row.get("service")).isEqualTo("mw-adapter-telco");
    assertThat(row.get("resource")).isEqualTo("partner/gp/PAY");
    assertThat(row.get("po")).isEqualTo("SUCCESS");
    assertThat((String) row.get("req")).contains("0171****001");
    assertThat(verifier.verify("2026-11").intact()).isTrue();
  }

  @Test
  void personalDataIsMaskedBeforeItIsStored() {
    AuditEvent e =
        JSON.readValue(
            """
            {"schemaVersion":1,"auditId":"%s","correlationId":"corr-2",
             "actor":{"type":"user","id":"01712345789"},"action":"wallet.viewed",
             "resource":"wallet/01712345789","after":{"msisdn":"8801712345789","note":["call 01812345678"]},
             "outcome":"SUCCESS","at":"2026-11-03T08:15:31Z","service":"mw-admin-api"}
            """
                .formatted(UUID.randomUUID()),
            AuditEvent.class);
    audit.record(e);

    Map<String, Object> row = jdbc.queryForMap("select actor_id, resource, after::text after from event");
    assertThat(row.get("actor_id")).isEqualTo("0171****789");
    assertThat(row.get("resource")).isEqualTo("wallet/0171****789");
    assertThat((String) row.get("after")).contains("880171****789").contains("0181****678").doesNotContain("12345");
    assertThat(verifier.verify("2026-11").intact()).isTrue();
  }

  @Test
  void numbersAndPropertyNamesAreMaskedBeforeTheyAreStored() {
    AuditEvent e =
        JSON.readValue(
            """
            {"schemaVersion":1,"auditId":"%s","correlationId":"corr-3",
             "actor":{"type":"user","id":"u-1"},"action":"wallet.linked","resource":"wallet",
             "before":{"msisdn":8801712345789,"amount":500},
             "after":{"account":123456789012345,"01812345678":{"linked":true},"ids":[1990123456789]},
             "outcome":"SUCCESS","at":"2026-11-03T08:15:31Z","service":"mw-admin-api"}
            """
                .formatted(UUID.randomUUID()),
            AuditEvent.class);
    audit.record(e);

    Map<String, Object> row =
        jdbc.queryForMap("select before::text before, after::text after from event");
    assertThat((String) row.get("before"))
        .isEqualTo("{\"amount\": 500, \"msisdn\": \"880171****789\"}");
    assertThat((String) row.get("after"))
        .isEqualTo(
            "{\"ids\": [\"****789\"], \"account\": \"****2345\","
                + " \"0181****678\": {\"linked\": true}}");
    assertThat(verifier.verify("2026-11").intact()).isTrue();
  }

  /** Keys that mask alike all survive: a later one gets #2, #3... on its own name, in order. */
  @Test
  void propertyNamesThatMaskAlikeAreKeptApart() {
    AuditEvent e =
        JSON.readValue(
            """
            {"schemaVersion":1,"auditId":"%s","correlationId":"corr-4",
             "actor":{"type":"user","id":"u-1"},"action":"wallet.linked","resource":"wallet",
             "after":{"01711000001":"first","01711999001":"second","0171****001#2":"literal"},
             "outcome":"SUCCESS","at":"2026-11-03T08:15:31Z","service":"mw-admin-api"}
            """
                .formatted(UUID.randomUUID()),
            AuditEvent.class);
    audit.record(e);

    String after = jdbc.queryForObject("select after::text from event", String.class);
    assertThat(after)
        .isEqualTo(
            "{\"0171****001\": \"first\", \"0171****001#2\": \"second\","
                + " \"0171****001#2#2\": \"literal\"}");
    assertThat(verifier.verify("2026-11").intact()).isTrue();
  }

  @Test
  void anEventWithoutItsRequiredFieldsIsRefused() {
    AuditEvent e =
        JSON.readValue(
            """
            {"schemaVersion":1,"auditId":"%s","action":"x","resource":"r","outcome":"SUCCESS",
             "at":"2026-11-03T08:15:31Z","service":"mw-x"}
            """
                .formatted(UUID.randomUUID()),
            AuditEvent.class);
    assertThatThrownBy(() -> audit.record(e)).isInstanceOf(IllegalArgumentException.class);
    assertThat(jdbc.queryForObject("select count(*) from event", Long.class)).isZero();
  }

  @Test
  void theTableRefusesUpdateAndDeleteEvenForItsOwner() {
    recordStates("RECEIVED", "VALIDATING");
    assertThatThrownBy(() -> jdbc.update("update event set outcome = 'FAILURE'"))
        .isInstanceOf(DataAccessException.class)
        .rootCause()
        .hasMessageContaining("append-only");
    assertThatThrownBy(() -> jdbc.update("delete from event"))
        .isInstanceOf(DataAccessException.class)
        .rootCause()
        .hasMessageContaining("append-only");
  }

  // --- tamper detection (superuser, triggers bypassed) -----------------------------------------

  static Stream<Arguments> edits() {
    Stream.Builder<Arguments> b = Stream.builder();
    for (long seq = 1; seq <= 4; seq++) {
      b.add(Arguments.of(seq, "after = '{\"status\":\"FORGED\"}'"));
      b.add(Arguments.of(seq, "actor_id = 'someone-else'"));
      b.add(Arguments.of(seq, "at = at + interval '1 second'"));
      b.add(Arguments.of(seq, "outcome = 'FAILURE'"));
      b.add(Arguments.of(seq, "txn_id = null"));
    }
    return b.build();
  }

  @ParameterizedTest(name = "row {0}: set {1}")
  @MethodSource("edits")
  void changingAnyRowFailsVerificationAtThatRow(long seq, String assignment) {
    recordStates("RECEIVED", "VALIDATING", "POSTING", "UNKNOWN", "COMPLETED");
    assertThat(verifier.verify("2026-11").intact()).isTrue();

    asSuperuserBypassingTriggers("update event set " + assignment + " where month = '2026-11' and seq = ?", seq);

    ChainVerification v = verifier.verify("2026-11");
    assertThat(v.intact()).isFalse();
    assertThat(v.failedSeq()).isEqualTo(seq);
  }

  @ParameterizedTest(name = "row {0}")
  @MethodSource("rows")
  void deletingAnyRowFailsVerificationAtThatRow(long seq) {
    recordStates("RECEIVED", "VALIDATING", "POSTING", "UNKNOWN", "COMPLETED");

    asSuperuserBypassingTriggers("delete from event where month = '2026-11' and seq = ?", seq);

    ChainVerification v = verifier.verify("2026-11");
    assertThat(v.intact()).isFalse();
    assertThat(v.failedSeq()).isEqualTo(seq);
  }

  static Stream<Long> rows() {
    return Stream.of(1L, 2L, 3L, 4L);
  }
}
