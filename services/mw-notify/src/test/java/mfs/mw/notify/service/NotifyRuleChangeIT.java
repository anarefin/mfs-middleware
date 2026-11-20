package mfs.mw.notify.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;
import mfs.mw.notify.TestInfrastructure;
import mfs.mw.notify.messaging.EventMetadata;
import mfs.mw.notify.messaging.EventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * Ticket 133c (FRS NOT-01, acceptance criterion 2): an approved {@code notify-rule} arrives on
 * {@code mw.config.changed.v1} (configType NOTIFY_RULE) and is applied without a restart: the next
 * matching {@code mw.txn.status.v1} event uses the new channel set. A replayed older version
 * changes nothing; {@code enabled false} stops the rule. Observed at the message table, one row
 * per channel sent. A test-only event type keeps the seeded rules out of the way.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "mw.notify.consumers.enabled=true",
      "mw.notify.smpp.enabled=false",
      "mw.notify.dispatch.enabled=false",
      "mw.kafka.retry.delays=1s,1s,1s"
    })
@Import(TestInfrastructure.class)
@DirtiesContext
class NotifyRuleChangeIT {

  static final String CONFIG = "mw.config.changed.v1";
  static final String STATUS = "mw.txn.status.v1";
  static final String PAYER = "01711000077";
  static final String EVENT_TYPE = "IT_RULE_PAY";
  static final String RULE = "it-rule-pay-success";

  private final EventPublisher publisher;
  private final JdbcTemplate jdbc;
  private final JsonMapper json;

  @Autowired
  NotifyRuleChangeIT(EventPublisher publisher, JdbcTemplate jdbc, JsonMapper json) {
    this.publisher = publisher;
    this.jdbc = jdbc;
    this.json = json;
  }

  @BeforeEach
  void contact() {
    jdbc.update("insert into contact (party_id, email) values (?, ?) on conflict do nothing", PAYER,
        "payer77@example.test");
  }

  static <T> T await(Supplier<T> value, Predicate<T> done) {
    long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
    T v = value.get();
    while (!done.test(v) && System.nanoTime() < deadline) {
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
      v = value.get();
    }
    return v;
  }

  private void publishRule(long version, List<String> channels, boolean enabled) {
    String config =
        """
        {"schemaVersion":1,"configKey":"notify-rule/%s","configType":"NOTIFY_RULE","change":"UPSERT","version":%d,
         "value":{"ruleId":"%s","version":%d,"eventType":"%s","outcome":"SUCCESS","customerType":"CUSTOMER",
                  "recipient":"DEBIT_PARTY","channels":%s,"templateCode":"TXN_BILL_PAY_SUCCESS","enabled":%s},
         "changedAt":"2026-11-20T09:00:00Z","changedBy":"ops-maker01","approvedBy":"ops-checker01"}
        """
            .formatted(RULE, version, RULE, version, EVENT_TYPE,
                channels.stream().map(c -> "\"" + c + "\"").toList(), enabled);
    String key = "notify-rule/" + RULE;
    publisher.publish(CONFIG, key, new EventMetadata("corr-rule", key, key + ":" + version + ":" + UUID.randomUUID(), 1),
        json.readTree(config));
  }

  private String publishSuccess(String status) {
    return publishStatus(EVENT_TYPE, status);
  }

  /** Publishes an event of {@code txnType} paid by {@link #PAYER} and returns its txnId. */
  private String publishStatus(String txnType, String status) {
    String txn = UUID.randomUUID().toString();
    String event =
        """
        {"schemaVersion":1,"txnId":"%s","correlationId":"corr-%s","channel":"CUSTOMER_APP","txnType":"%s",
         "status":"%s","previousStatus":"POSTING",
         "initiator":{"type":"MSISDN","id":"%s"},
         "debitParty":{"type":"WALLET","id":"%s","walletType":"CUSTOMER"},
         "creditParty":{"type":"WALLET","id":"01811000077","walletType":"MERCHANT"},
         "amount":{"valueMinor":50000,"currency":"BDT"},"fee":{"valueMinor":0,"currency":"BDT"},
         "requestedAt":"2026-11-20T09:15:30Z","updatedAt":"2026-11-20T09:15:31Z","locale":"en"}
        """
            .formatted(txn, txn, txnType, status, PAYER, PAYER);
    publisher.publish(STATUS, txn, new EventMetadata("corr-" + txn, txn, txn + ":" + status, 1), json.readTree(event));
    return txn;
  }

  private Set<String> channelsOf(String txnId) {
    return new HashSet<>(jdbc.queryForList("select channel from message where txn_id = ?", String.class, txnId));
  }

  /** The admin rule's enabled channels as mw-notify holds them. */
  private Set<String> ruleChannels() {
    return new HashSet<>(jdbc.queryForList(
        "select channel from rule where enabled and rule_id like ?", String.class, "admin-" + RULE + "-%"));
  }

  /**
   * The channels of {@code txnId} once a later P2P (seeded rule {@code p2p-sent}) on the same
   * single-partition topic has its rows: the earlier event has been handled by then.
   */
  private Set<String> settledChannels(String txnId) {
    String marker = publishStatus("P2P", "COMPLETED");
    assertThat(await(() -> channelsOf(marker), s -> !s.isEmpty())).as("sentinel").isNotEmpty();
    return channelsOf(txnId);
  }

  @Test
  void anApprovedRuleChangeIsUsedByTheNextMatchingEvent() {
    publishRule(1, List.of("SMS"), true);
    await(this::ruleChannels, s -> s.equals(Set.of("SMS")));
    String first = publishSuccess("COMPLETED");
    assertThat(await(() -> channelsOf(first), s -> !s.isEmpty())).containsExactly("SMS");

    publishRule(2, List.of("SMS", "EMAIL"), true);
    await(this::ruleChannels, s -> s.equals(Set.of("SMS", "EMAIL")));
    String second = publishSuccess("COMPLETED");
    assertThat(await(() -> channelsOf(second), s -> s.size() == 2)).containsExactlyInAnyOrder("SMS", "EMAIL");

    publishRule(3, List.of("EMAIL"), true);
    await(this::ruleChannels, s -> s.equals(Set.of("EMAIL")));
    String third = publishSuccess("COMPLETED");
    assertThat(await(() -> channelsOf(third), s -> !s.isEmpty())).as("SMS dropped").containsExactly("EMAIL");

    publishRule(2, List.of("SMS", "EMAIL"), true);
    String failed = publishSuccess("FAILED");
    assertThat(settledChannels(failed)).as("another outcome matches nothing").isEmpty();
    assertThat(ruleChannels()).as("an older version is ignored").isEqualTo(Set.of("EMAIL"));

    publishRule(4, List.of("EMAIL"), false);
    await(this::ruleChannels, Set::isEmpty);
    String fourth = publishSuccess("COMPLETED");
    assertThat(settledChannels(fourth)).as("a disabled rule sends nothing").isEmpty();
  }
}
