package mfs.mw.core.mock.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import mfs.mw.core.mock.TestInfrastructure;
import mfs.mw.core.mock.contracts.ContractSchemas;
import mfs.mw.core.mock.dto.CoreCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code WalletStateChanged} (ticket 67, spec 18.7, D5): the wallet projection feed for mw-auth. One
 * event per wallet with an MSISDN on create (seed, seed API) and on every status change (PIN lock,
 * unlock), written to the outbox in the change's transaction, keyed by walletId, with a version
 * that only grows (also across a ledger reset); and a snapshot of every wallet on request.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestInfrastructure.class)
class WalletStateEventsIT {

  static final String TOPIC = "core.wallet.event.v1";
  static final String CUSTOMER_A = "01711000001";
  static final String CUSTOMER_B = "01711000002";

  private final CoreCommandService service;
  private final WalletSeeder seeder;
  private final WalletSeedService seedApi;
  private final WalletStateEvents events;
  private final JdbcTemplate jdbc;
  private final JsonMapper json;

  @Autowired
  WalletStateEventsIT(
      CoreCommandService service,
      WalletSeeder seeder,
      WalletSeedService seedApi,
      WalletStateEvents events,
      JdbcTemplate jdbc,
      JsonMapper json) {
    this.service = service;
    this.seeder = seeder;
    this.seedApi = seedApi;
    this.events = events;
    this.jdbc = jdbc;
    this.json = json;
  }

  /** A fresh ledger whose seed events are the only ones in the outbox. */
  @BeforeEach
  void freshLedger() {
    jdbc.update("delete from outbox");
    seeder.reset();
  }

  /** The WalletStateChanged rows of one wallet in the outbox, oldest first, each schema-checked. */
  private List<JsonNode> stateEvents(String walletId) {
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "select topic, msg_key, payload::text p from outbox where payload->>'eventType' = 'WalletStateChanged' "
                + "and payload->'payload'->>'walletId' = ? order by id",
            walletId);
    return rows.stream()
        .map(
            r -> {
              assertThat(r.get("topic")).isEqualTo(TOPIC);
              assertThat(r.get("msg_key")).isEqualTo(walletId);
              JsonNode e = json.readTree((String) r.get("p"));
              assertThat(ContractSchemas.schema("events/mw-core-mock/core.event.v1.schema.json").validate(e))
                  .as("WalletStateChanged against core.event.v1")
                  .isEmpty();
              return e;
            })
        .toList();
  }

  private long version(JsonNode event) {
    return event.path("payload").path("version").asLong();
  }

  private CoreCommand unlock(String wallet) {
    String u = UUID.randomUUID().toString();
    String txnId = u.substring(0, 14) + "7" + u.substring(15);
    String changeRequestId = UUID.randomUUID().toString();
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("subject", Map.of("type", "WALLET", "id", wallet));
    payload.put("changeRequestId", changeRequestId);
    payload.put("makerId", "ops-maker01");
    payload.put("checkerId", "ops-checker01");
    Instant now = Instant.now();
    return new CoreCommand(
        1, "UnlockWallet", changeRequestId + ":unlock", txnId, "corr-" + txnId, now, now.plusSeconds(300),
        json.valueToTree(payload));
  }

  @Test
  void theSeedPublishesOneCreatedEventPerWalletWithAnMsisdn() {
    List<JsonNode> a = stateEvents(CUSTOMER_A);
    assertThat(a).hasSize(1);
    JsonNode p = a.getFirst().path("payload");
    assertThat(p.path("change").asString()).isEqualTo("CREATED");
    assertThat(p.path("msisdn").asString()).isEqualTo(CUSTOMER_A);
    assertThat(p.path("walletType").asString()).isEqualTo("CUSTOMER");
    assertThat(p.path("status").asString()).isEqualTo("ACTIVE");
    assertThat(p.path("kycLevel").asString()).isEqualTo("FULL");
    assertThat(p.path("hasPin").asBoolean()).as("the core holds no PIN (ticket 72)").isFalse();
    assertThat(a.getFirst().path("outcome").asString()).isEqualTo("SUCCESS");

    assertThat(jdbc.queryForObject(
            "select count(*) from outbox where payload->>'eventType' = 'WalletStateChanged'", Long.class))
        .isEqualTo(1083);
    assertThat(stateEvents(WalletSeeder.SETTLEMENT_WALLET)).isEmpty();
  }

  @Test
  void aSecondSeedPublishesNothingNew() {
    seeder.seed();
    assertThat(stateEvents(CUSTOMER_A)).hasSize(1);
  }

  @Test
  void anUnlockOfACoreLockPublishesAHigherVersion() {
    long created = version(stateEvents(CUSTOMER_A).getFirst());
    // a core-side lock (the mock has no PIN lockout since ticket 72); set by hand, it publishes nothing
    jdbc.update("update wallet set status = 'LOCKED' where id = ?", CUSTOMER_A);
    assertThat(stateEvents(CUSTOMER_A)).hasSize(1);

    service.handle(unlock(CUSTOMER_A));
    List<JsonNode> unlocked = stateEvents(CUSTOMER_A);
    assertThat(unlocked).hasSize(2);
    assertThat(unlocked.get(1).path("payload").path("status").asString()).isEqualTo("ACTIVE");
    assertThat(unlocked.get(1).path("payload").path("change").asString()).isEqualTo("STATUS_CHANGED");
    assertThat(version(unlocked.get(1))).isGreaterThan(created);
  }

  @Test
  void eachEventHasItsOwnEnvelopeIdsAndTheVersionSurvivesAReset() {
    JsonNode before = stateEvents(CUSTOMER_A).getFirst();
    seeder.reset();
    JsonNode after = stateEvents(CUSTOMER_A).getLast();
    assertThat(version(after)).isGreaterThan(version(before));
    assertThat(after.path("eventId").asString()).isNotEqualTo(before.path("eventId").asString());
    assertThat(after.path("txnId").asString()).isNotEqualTo(before.path("txnId").asString());
  }

  @Test
  void theSeedApiPublishesACreatedEventOnlyWhenItCreates() {
    seedApi.seed("01712000001", "AGENT", 0, null, null);
    seedApi.seed("01712000001", "AGENT", 0, null, null);
    List<JsonNode> e = stateEvents("01712000001");
    assertThat(e).hasSize(1);
    assertThat(e.getFirst().path("payload").path("walletType").asString()).isEqualTo("AGENT");
  }

  @Test
  void aSnapshotRepublishesEveryWalletAtItsCurrentVersion() {
    long created = version(stateEvents(CUSTOMER_A).getFirst());
    assertThat(events.snapshot()).isEqualTo(1083);
    List<JsonNode> e = stateEvents(CUSTOMER_A);
    assertThat(e).hasSize(2);
    assertThat(e.get(1).path("payload").path("change").asString()).isEqualTo("SNAPSHOT");
    assertThat(version(e.get(1))).isEqualTo(created);
  }
}
