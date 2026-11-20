package mfs.mw.admin.api.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import mfs.mw.admin.api.MutableClock;
import mfs.mw.admin.api.TestInfrastructure;
import mfs.mw.admin.api.contracts.ContractSchemas;
import mfs.mw.admin.api.dto.CredentialType;
import mfs.mw.admin.api.entity.CredentialMeta;
import mfs.mw.admin.api.repository.CredentialMetaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Credential expiry alerts (ticket 131, ticket's {@code CredentialExpiryJobTest}; an IT because the
 * once-per-threshold ledger is a table): credentials expiring in 30, 7 and 1 days each raise exactly
 * one alert, for that threshold, as an ops email on {@code mw.notify.request.v1} (template {@code
 * OPS_CREDENTIAL_EXPIRING}, through the outbox); running again raises nothing; a credential moving
 * past the next threshold raises that one; one without an expiry or further out raises nothing.
 */
@SpringBootTest(properties = {"mw.outbox.relay.enabled=false", "mw.credentials.expiry.ops-email=ops-alerts@bits.example"})
@Import({TestInfrastructure.class, CredentialExpiryJobIT.TestClock.class})
class CredentialExpiryJobIT {

  static final Instant NOW = Instant.parse("2026-11-20T06:00:00Z");
  static final String TOPIC = "mw.notify.request.v1";
  static final String SCHEMA = "events/mw-notify/mw.notify.request.v1.schema.json";

  @TestConfiguration(proxyBeanMethods = false)
  static class TestClock {
    @Bean
    @Primary
    Clock testClock() {
      return new MutableClock(NOW);
    }
  }

  private final CredentialExpiryJob job;
  private final CredentialMetaRepository metas;
  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final JsonMapper json;
  private final MutableClock clock;

  @Autowired
  CredentialExpiryJobIT(
      CredentialExpiryJob job, CredentialMetaRepository metas, JdbcTemplate jdbc, TransactionTemplate tx, JsonMapper json,
      Clock clock) {
    this.job = job;
    this.metas = metas;
    this.jdbc = jdbc;
    this.tx = tx;
    this.json = json;
    this.clock = (MutableClock) clock;
  }

  @BeforeEach
  void reset() {
    clock.set(NOW);
    jdbc.update("delete from outbox");
    jdbc.update("delete from credential_alert");
    jdbc.update("delete from credential_meta");
    store("ocs", "in-30", NOW.plus(Duration.ofDays(30)).minusSeconds(60));
    store("ocs", "in-7", NOW.plus(Duration.ofDays(7)).minusSeconds(60));
    store("billagg", "in-1", NOW.plus(Duration.ofDays(1)).minusSeconds(60));
    store("billagg", "in-40", NOW.plus(Duration.ofDays(40)));
    store("c2wbank", "no-expiry", null);
  }

  private void store(String partner, String name, Instant notAfter) {
    tx.executeWithoutResult(s -> metas.upsert(new CredentialMeta(CredentialMeta.id(partner, name), partner, name,
        "mw-adapter-telco", CredentialType.CLIENT_CERT, List.of(name + "-keystore", name + "-keystore-password"),
        name + "-v1", "sha256:" + "ab".repeat(32), "CN=" + name, "1f", notAfter, 1, "int-maker01", "int-checker01", NOW,
        UUID.randomUUID())));
  }

  private List<JsonNode> alerts() {
    return jdbc.queryForList("select payload::text from outbox where topic = ? order by id", String.class, TOPIC)
        .stream().map(json::readTree).toList();
  }

  @Test
  void eachThresholdRaisesExactlyOneOpsEmail() {
    job.alert();
    List<JsonNode> first = alerts();
    assertThat(first).hasSize(3);
    first.forEach(a -> assertThat(ContractSchemas.schema(SCHEMA).validate(a)).as("%s", a).isEmpty());
    assertThat(first).extracting(a -> a.path("params").path("credential").asString() + "@" + a.path("params").path("threshold").asString())
        .containsExactlyInAnyOrder("ocs/in-30@30", "ocs/in-7@7", "billagg/in-1@1");
    JsonNode a = first.getFirst();
    assertThat(a.path("templateCode").asString()).isEqualTo("OPS_CREDENTIAL_EXPIRING");
    assertThat(a.path("recipient").path("email").asString()).isEqualTo("ops-alerts@bits.example");
    assertThat(a.path("channels").get(0).asString()).isEqualTo("EMAIL");
    assertThat(a.path("params").path("notAfter").asString()).isNotBlank();

    job.alert();
    assertThat(alerts()).hasSize(3);

    // 23 days later the 30-day credential is inside 7 days: its 7-day alert, once
    clock.advance(Duration.ofDays(23));
    job.alert();
    List<JsonNode> later = alerts();
    assertThat(later).extracting(x -> x.path("params").path("credential").asString() + "@" + x.path("params").path("threshold").asString())
        .containsExactlyInAnyOrder("ocs/in-30@30", "ocs/in-7@7", "billagg/in-1@1", "ocs/in-30@7", "ocs/in-7@1",
            "billagg/in-40@30");
    job.alert();
    assertThat(alerts()).hasSize(later.size());
    assertThat(jdbc.queryForList("select distinct payload->>'messageId' from outbox where topic = ?", String.class, TOPIC))
        .hasSize(later.size());
  }

  @Test
  void aRotatedCredentialIsAlertedAgainForItsNewVersion() {
    job.alert();
    store("ocs", "in-7", NOW.plus(Duration.ofDays(5)));
    job.alert();
    assertThat(alerts()).extracting(x -> x.path("params").path("credential").asString() + "@" + x.path("params").path("threshold").asString())
        .containsExactlyInAnyOrder("ocs/in-30@30", "ocs/in-7@7", "billagg/in-1@1", "ocs/in-7@7");
  }
}
