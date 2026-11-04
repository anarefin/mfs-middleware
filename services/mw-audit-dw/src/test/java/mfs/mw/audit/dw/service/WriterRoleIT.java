package mfs.mw.audit.dw.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariDataSource;
import java.time.Clock;
import java.util.UUID;
import javax.sql.DataSource;
import mfs.mw.audit.dw.TestInfrastructure;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * The {@code audit_writer} role (spec 14.3, 16.4): the service can do its job with it, and it can
 * neither change nor remove an audit row, nor read one.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = "mw.audit-dw.consumers.enabled=false")
@Import(TestInfrastructure.class)
class WriterRoleIT {

  /** Test-only password for the writer login, set by this test; not a real secret. */
  private static final String FAKE_PASSWORD = "test-only-writer-pw";

  private final JdbcTemplate admin;
  private final JdbcTemplate writer;
  private final AuditService writerService;

  @Autowired
  WriterRoleIT(DataSource dataSource, JsonMapper json, Clock clock) {
    this.admin = new JdbcTemplate(dataSource);
    admin.execute("alter role audit_writer login password '" + FAKE_PASSWORD + "'");
    String url = ((HikariDataSource) dataSource).getJdbcUrl();
    DriverManagerDataSource ds =
        new DriverManagerDataSource(
            url + (url.contains("?") ? "&" : "?") + "currentSchema=audit", "audit_writer", FAKE_PASSWORD);
    this.writer = new JdbcTemplate(ds);
    TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
    this.writerService = new AuditService(writer, tx, json, clock);
  }

  @BeforeEach
  void oneRow() {
    admin.execute("truncate audit.event, audit.event_key, audit.chain_head");
    writerService.record(AuditTrailIT.event(UUID.randomUUID(), "RECEIVED", "VALIDATING"));
  }

  @Test
  void theWriterCanAppendAndTheChainHolds() {
    writerService.record(AuditTrailIT.event(UUID.randomUUID(), "VALIDATING", "POSTING"));

    assertThat(admin.queryForObject("select count(*) from audit.event", Long.class)).isEqualTo(2);
    ChainVerification v = new AuditChainVerifier(admin).verify(currentMonth());
    assertThat(v.intact()).as(v.toString()).isTrue();
  }

  @Test
  void theWriterCanStartANewMonth() {
    admin.execute("drop table if exists audit.event_2031_01");
    JdbcTemplate w = writer;
    TransactionTemplate tx =
        new TransactionTemplate(new DataSourceTransactionManager(w.getDataSource()));
    AuditService future =
        new AuditService(
            w,
            tx,
            JsonMapper.builder().build(),
            Clock.fixed(java.time.Instant.parse("2031-01-15T10:00:00Z"), java.time.ZoneOffset.UTC));

    future.record(AuditTrailIT.event(UUID.randomUUID(), "RECEIVED", "VALIDATING"));

    assertThat(admin.queryForObject("select count(*) from audit.event_2031_01", Long.class)).isEqualTo(1);
    assertThat(new AuditChainVerifier(admin).verify("2031-01").intact()).isTrue();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "update event set outcome = 'FAILURE'",
        "delete from event",
        "truncate event",
        "select count(*) from event",
        "update %s set outcome = 'FAILURE'",
        "delete from %s",
        "truncate %s",
        "delete from chain_head",
        "delete from event_key",
        "update event_key set recorded_at = now()",
        "drop table %s",
        "alter table event disable trigger event_append_only"
      })
  void theWriterCannot(String statement) {
    String sql = statement.formatted("event_" + currentMonth().replace('-', '_'));
    assertThatThrownBy(() -> writer.execute(sql))
        .as(sql)
        .isInstanceOf(DataAccessException.class)
        .rootCause()
        .satisfies(
            e ->
                assertThat(e.getMessage())
                    .containsAnyOf("permission denied", "must be owner"));
    assertThat(admin.queryForObject("select count(*) from audit.event", Long.class)).isEqualTo(1);
  }

  @Test
  void noRoleHoldsUpdateOrDeleteOnTheAuditTable() {
    assertThat(
            admin.queryForList(
                "select grantee || ':' || privilege_type from information_schema.role_table_grants "
                    + "where table_schema = 'audit' and table_name like 'event%' "
                    + "and privilege_type in ('UPDATE', 'DELETE', 'TRUNCATE') "
                    + "and grantee <> (select tableowner from pg_tables where schemaname = 'audit' and tablename = 'chain_head')",
                String.class))
        .isEmpty();
  }

  private String currentMonth() {
    return admin.queryForObject("select month from audit.chain_head limit 1", String.class);
  }
}
