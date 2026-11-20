package mfs.mw.audit.dw.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import mfs.mw.audit.dw.MutableClock;
import mfs.mw.audit.dw.TestInfrastructure;
import mfs.mw.audit.dw.dto.AuditEvent;
import mfs.mw.audit.dw.scheduler.AuditChainVerifyJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Ticket 130 (FRS AUD-01 with AUD-02 kept): an event's hop facts and payload rows are part of its
 * month's hash chain, so changing a stored body, its digest or a hop fact, or deleting a payload
 * row, is caught by the scheduled {@link AuditChainVerifyJob}. Rows are tampered with as the
 * container's superuser.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {"mw.audit-dw.consumers.enabled=false", "mw.audit-dw.anchor.enabled=false"})
@Import({TestInfrastructure.class, AuditTrailIT.TestClock.class})
class HashChainWithPayloadIT {

  private static final String FAKE_READER_PASSWORD = "test-only-reader-pw";
  private static final String TXN = "01933b8e-7c1a-7b2e-9f3d-2a4c5e6f7a8b";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final AuditService audit;
  private final AuditChainVerifyJob job;
  private final MutableClock clock;
  private final JdbcTemplate admin;
  private final TransactionTemplate tx;
  private final MeterRegistry meters;

  @Autowired
  HashChainWithPayloadIT(
      AuditService audit, AuditChainVerifyJob job, Clock clock, DataSource dataSource, TransactionTemplate tx,
      MeterRegistry meters) {
    this.audit = audit;
    this.job = job;
    this.clock = (MutableClock) clock;
    this.admin = new JdbcTemplate(dataSource);
    this.tx = tx;
    this.meters = meters;
    admin.execute("alter role audit_reader login password '" + FAKE_READER_PASSWORD + "'");
  }

  @BeforeEach
  void clean() {
    clock.set(AuditTrailIT.NOV);
    admin.execute("truncate audit.event, audit.event_key, audit.chain_head, audit.payload");
    audit.record(AuditTrailIT.event(UUID.randomUUID(), "RECEIVED", "PARTNER_PENDING"));
    audit.record(partnerCall());
    audit.record(AuditTrailIT.event(UUID.randomUUID(), "PARTNER_PENDING", "COMPLETED"));
  }

  static AuditEvent partnerCall() {
    return JSON.readValue(
        """
        {"schemaVersion":1,"auditId":"%s","txnId":"%s","correlationId":"corr-1",
         "actor":{"type":"system","id":"mw-adapter-telco"},"action":"partner.call.completed",
         "resource":"partner/ocs/PAY","outcome":"SUCCESS","at":"2026-11-03T08:15:31.7Z",
         "service":"mw-adapter-telco","hop":"PARTNER_CALL","direction":"OUT","durationMs":184,
         "payload":[
           {"direction":"OUT","contentType":"application/json","body":"{\\"msisdn\\":\\"01711234567\\",\\"pin\\":\\"1234\\"}"},
           {"direction":"IN","contentType":"application/json","body":"{\\"status\\":\\"OK\\"}","payloadRef":"partner-payload/6f1c2a3b-4d5e-4f60-8a7b-9c0d1e2f3a4b"}]}
        """
            .formatted(UUID.randomUUID(), TXN),
        AuditEvent.class);
  }

  private double brokenGauge() {
    return meters.get("mw.audit.chain.broken").gauge().value();
  }

  private void tamper(String sql) {
    tx.executeWithoutResult(
        s -> {
          admin.execute("set local session_replication_role = replica");
          admin.update(sql);
        });
  }

  @Test
  void payloadsAreStoredScrubbedWithTheirDigestAndTheChainIsIntact() {
    List<Map<String, Object>> rows =
        admin.queryForList("select ordinal, direction, body, payload_ref, size, sha256 from audit.payload order by ordinal");

    assertThat(rows).hasSize(2);
    assertThat((String) rows.get(0).get("body")).doesNotContain("01711234567", "1234").contains("0171****567");
    assertThat(rows.get(1).get("payload_ref")).isEqualTo("partner-payload/6f1c2a3b-4d5e-4f60-8a7b-9c0d1e2f3a4b");
    assertThat((byte[]) rows.get(0).get("sha256")).hasSize(32);
    assertThat(admin.queryForObject(
            "select hop || '/' || direction || '/' || duration_ms from audit.event where seq = 2", String.class))
        .isEqualTo("PARTNER_CALL/OUT/184");

    job.verify();

    assertThat(brokenGauge()).isZero();
  }

  @Test
  void aChangedPayloadBodyBreaksTheChain() {
    tamper("update audit.payload set body = '{\"status\":\"FAILED\"}' where ordinal = 2");

    job.verify();

    assertThat(brokenGauge()).isEqualTo(1.0);
  }

  @Test
  void aBodyAndDigestRewrittenTogetherStillBreakTheChain() {
    tamper(
        "update audit.payload set body = '{\"status\":\"FAILED\"}',"
            + " sha256 = sha256(convert_to('{\"status\":\"FAILED\"}', 'UTF8')) where ordinal = 2");

    job.verify();

    assertThat(brokenGauge()).isEqualTo(1.0);
  }

  @Test
  void aDeletedPayloadRowOrAChangedHopFactBreaksTheChain() {
    tamper("delete from audit.payload where ordinal = 1");
    job.verify();
    assertThat(brokenGauge()).isEqualTo(1.0);

    clean();
    tamper("update audit.event set duration_ms = 5 where seq = 2");
    job.verify();
    assertThat(brokenGauge()).isEqualTo(1.0);
  }

  @Test
  void payloadsAreAppendOnlyForEveryone() {
    assertThatThrownBy(() -> admin.update("update audit.payload set body = 'x'"))
        .isInstanceOf(DataAccessException.class)
        .rootCause()
        .hasMessageContaining("append-only");
    assertThatThrownBy(() -> admin.update("delete from audit.payload"))
        .isInstanceOf(DataAccessException.class)
        .rootCause()
        .hasMessageContaining("append-only");
  }
}
