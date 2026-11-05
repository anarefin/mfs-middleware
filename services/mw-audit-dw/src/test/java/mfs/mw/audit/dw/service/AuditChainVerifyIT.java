package mfs.mw.audit.dw.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import mfs.mw.audit.dw.MutableClock;
import mfs.mw.audit.dw.TestInfrastructure;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The scheduled chain verification (ticket 50) through {@link ChainVerifyService}, which reads as
 * the read-only {@code audit_reader}. Rows are tampered with as the container's superuser.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = "mw.audit-dw.consumers.enabled=false")
@Import({TestInfrastructure.class, AuditTrailIT.TestClock.class})
class AuditChainVerifyIT {

  /** Same test-only values as src/test/resources/config/application.yml; not real secrets. */
  private static final String FAKE_READER_PASSWORD = "test-only-reader-pw";
  private static final String FAKE_WRITER_PASSWORD = "test-only-writer-pw";

  private final ChainVerifyService verify;
  private final AuditService audit;
  private final MutableClock clock;
  private final JdbcTemplate admin;
  private final TransactionTemplate tx;
  private final MeterRegistry meters;
  private final String url;

  @Autowired
  AuditChainVerifyIT(
      ChainVerifyService verify,
      AuditService audit,
      Clock clock,
      DataSource dataSource,
      TransactionTemplate tx,
      MeterRegistry meters) {
    this.verify = verify;
    this.audit = audit;
    this.clock = (MutableClock) clock;
    this.admin = new JdbcTemplate(dataSource);
    this.tx = tx;
    this.meters = meters;
    this.url = ((HikariDataSource) dataSource).getJdbcUrl();
    admin.execute("alter role audit_reader login password '" + FAKE_READER_PASSWORD + "'");
    admin.execute("alter role audit_writer login password '" + FAKE_WRITER_PASSWORD + "'");
  }

  @BeforeEach
  void clean() {
    clock.set(AuditTrailIT.NOV);
    admin.execute("truncate audit.event, audit.event_key, audit.chain_head");
    for (String[] step : new String[][] {{"RECEIVED", "VALIDATING"}, {"VALIDATING", "POSTING"}, {"POSTING", "COMPLETED"}}) {
      audit.record(AuditTrailIT.event(UUID.randomUUID(), step[0], step[1]));
    }
  }

  private JdbcTemplate as(String role, String password) {
    return new JdbcTemplate(
        new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=audit", role, password));
  }

  private double brokenGauge() {
    return meters.get("mw.audit.chain.broken").gauge().value();
  }

  @Test
  void anIntactTrailVerifiesAsTheReader() {
    List<ChainVerification> results = verify.verifyAll();

    assertThat(results).extracting(ChainVerification::month).containsExactly("2026-11");
    assertThat(results.get(0).intact()).as(results.toString()).isTrue();
    assertThat(brokenGauge()).isZero();
  }

  @Test
  void aTamperedRowIsDetectedAndRaisesTheMetric() {
    tx.executeWithoutResult(
        s -> {
          admin.execute("set local session_replication_role = replica");
          admin.update("update audit.event set outcome = 'FAILURE' where month = '2026-11' and seq = 2");
        });

    List<ChainVerification> results = verify.verifyAll();

    assertThat(results.get(0).intact()).isFalse();
    assertThat(results.get(0).failedSeq()).isEqualTo(2L);
    assertThat(brokenGauge()).isEqualTo(1.0);
  }

  @Test
  void theReaderCanOnlyRead() {
    JdbcTemplate reader = as("audit_reader", FAKE_READER_PASSWORD);
    assertThat(reader.queryForObject("select count(*) from event", Long.class)).isEqualTo(3);
    for (String sql :
        List.of(
            "update event set outcome = 'FAILURE'",
            "delete from event",
            "delete from chain_head",
            "insert into event_key values (gen_random_uuid(), now())",
            "select count(*) from event_key")) {
      assertThatThrownBy(() -> reader.execute(sql)).as(sql).isInstanceOf(DataAccessException.class);
    }
  }

  @Test
  void theWriterStillCannotSelect() {
    JdbcTemplate writer = as("audit_writer", FAKE_WRITER_PASSWORD);
    assertThatThrownBy(() -> writer.queryForObject("select count(*) from event", Long.class))
        .isInstanceOf(DataAccessException.class)
        .rootCause()
        .hasMessageContaining("permission denied");
  }
}
