package mfs.mw.core.mock.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import mfs.mw.core.mock.TestInfrastructure;
import mfs.mw.core.mock.contracts.ContractSchemas;
import mfs.mw.core.mock.dto.CoreEvent;
import mfs.mw.core.mock.security.InternalTokens;
import mfs.mw.core.mock.security.TestAssertions;
import mfs.mw.core.mock.service.CoreCommandService;
import mfs.mw.core.mock.service.CoreCommandsTestSupport;
import mfs.mw.core.mock.service.WalletSeeder;
import mfs.mw.core.mock.util.TxnHash;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A REST money operation (ticket 93, ADR D31: "events stay on Kafka") writes the same {@code
 * core.event.v1} results through the outbox as the Kafka command of the same name: same topic, event
 * type, outcome, payload fields and balances, each valid against the owned contract. So mw-auth's
 * projection and the ledger views read REST-made changes as they read command-made ones.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestInfrastructure.class)
class CoreWalletEventsIT {

  private static final String EVENT_SCHEMA = "events/mw-core-mock/core.event.v1.schema.json";
  private static final String PAYER = "01711000001";

  private final MockMvcTester mvc;
  private final WalletSeeder seeder;
  private final JdbcTemplate jdbc;
  private final JsonMapper json;
  private final CoreCommandService core;
  private final CoreCommandsTestSupport support;

  @Autowired
  CoreWalletEventsIT(MockMvcTester mvc, WalletSeeder seeder, JdbcTemplate jdbc, JsonMapper json, CoreCommandService core) {
    this.mvc = mvc;
    this.seeder = seeder;
    this.jdbc = jdbc;
    this.json = json;
    this.core = core;
    this.support = new CoreCommandsTestSupport(json);
  }

  @BeforeEach
  void seed() {
    seeder.reset();
    jdbc.update("delete from outbox");
  }

  /** What another service can tell from an outbox row: topic, key, type, outcome, payload fields, balances. */
  private record Seen(String topic, String key, String eventType, String outcome, List<String> payloadFields, JsonNode balancesAfter) {}

  private List<Seen> outboxOf(String txnId) {
    List<Seen> seen = new ArrayList<>();
    for (Map<String, Object> row : jdbc.queryForList(
        "select topic, msg_key, payload::text as payload from outbox where msg_key = ? order by id", txnId)) {
      JsonNode e = json.readTree((String) row.get("payload"));
      assertThat(ContractSchemas.schema(EVENT_SCHEMA).validate(e)).as("%s against core.event.v1", e.path("eventType")).isEmpty();
      List<String> fields = new ArrayList<>(e.path("payload").propertyNames());
      fields.sort(null);
      seen.add(new Seen((String) row.get("topic"), (String) row.get("msg_key"), e.path("eventType").asString(),
          e.path("outcome").asString(), fields, e.path("balancesAfter")));
    }
    return seen;
  }

  private static List<Object> withoutKeys(List<Seen> s) {
    return s.stream().map(x -> (Object) List.of(x.topic(), x.eventType(), x.outcome(), x.payloadFields(), x.balancesAfter().toString())).toList();
  }

  private String postHold(String txnId, long minor) {
    String key = UUID.randomUUID().toString();
    String assertion = TestAssertions.mint(TestAssertions.claims(PAYER, "TOPUP", new TxnHash.Party("WALLET", PAYER),
        new TxnHash.Party("WALLET", PAYER), minor, key).build());
    String body = """
        {"txnId":"%s","txnType":"TOPUP","debitParty":{"type":"WALLET","id":"%s"},"creditParty":{"type":"WALLET","id":"%s"},
         "amount":{"valueMinor":%d,"currency":"BDT"},"idempotencyKey":"%s"}""".formatted(txnId, PAYER, PAYER, minor, key);
    var r = mvc.post().uri("/core/v1/holds").header("Authorization", InternalTokens.bearer("mw-orchestrator"))
        .header("X-Txn-Assertion", assertion).contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    assertThat(r).hasStatusOk();
    return json.readTree(r.getResponse().getContentAsByteArray()).path("holdId").asString();
  }

  private void postCommit(String holdId) {
    assertThat(mvc.post().uri("/core/v1/holds/" + holdId + "/commit")
        .header("Authorization", InternalTokens.bearer("mw-orchestrator")).exchange()).hasStatusOk();
  }

  @Test
  void aRestHoldAndCommitEmitTheEventsOfTheKafkaCommands() {
    // the Kafka command path, as mw-orchestrator drives it today
    String kafkaTxn = CoreCommandsTestSupport.txnId();
    CoreEvent held = core.handle(support.holdFunds(kafkaTxn, PAYER, 5000), CoreCommandService.PAYMENT_EVENT_TOPIC).orElseThrow();
    core.handle(support.commitHold(kafkaTxn, (String) held.payload().get("holdId")), CoreCommandService.PAYMENT_EVENT_TOPIC);
    // reset the payer, then the same over REST
    jdbc.update("update wallet set balance_minor = 1000000 where id = ?", PAYER);
    String restTxn = CoreCommandsTestSupport.txnId();
    postCommit(postHold(restTxn, 5000));

    List<Seen> kafka = outboxOf(kafkaTxn);
    List<Seen> rest = outboxOf(restTxn);

    assertThat(rest).extracting(Seen::eventType).containsExactly("FundsHeld", "HoldCommitted");
    assertThat(rest).extracting(Seen::topic).containsOnly(CoreCommandService.PAYMENT_EVENT_TOPIC);
    assertThat(rest).extracting(Seen::key).containsOnly(restTxn);
    assertThat(withoutKeys(rest)).isEqualTo(withoutKeys(kafka));
  }

  @Test
  void aRepeatedRestCommitRepublishesTheStoredEventAndPostsNothingMore() {
    String txn = CoreCommandsTestSupport.txnId();
    String holdId = postHold(txn, 5000);
    postCommit(holdId);
    postCommit(holdId);

    List<Seen> rest = outboxOf(txn);
    assertThat(rest).extracting(Seen::eventType).containsExactly("FundsHeld", "HoldCommitted", "HoldCommitted");
    assertThat(jdbc.queryForObject("select count(*) from entry where txn_id = ?", Integer.class, txn)).as("transfer, fee, fee income, VAT").isEqualTo(5);
  }
}
