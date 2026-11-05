package mfs.mw.orchestrator.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import mfs.mw.orchestrator.MutableClock;
import mfs.mw.orchestrator.TestData;
import mfs.mw.orchestrator.TestInfrastructure;
import mfs.mw.orchestrator.dto.CoreEvent;
import mfs.mw.orchestrator.dto.PartnerReply;
import mfs.mw.orchestrator.dto.TxnRequest;
import mfs.mw.orchestrator.mapper.PartnerRequestMapper;
import mfs.mw.orchestrator.service.CoreEventService;
import mfs.mw.orchestrator.service.PartnerReplyService;
import mfs.mw.orchestrator.service.SagaTimerService;
import mfs.mw.orchestrator.service.TransactionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The internal query API (ticket 35, spec 15.2 / 16.2: mw-admin-api and mw-portal-api read
 * transactions through it, never through the {@code txn} schema), over real HTTP against
 * PostgreSQL. Transactions are driven through the service seams, as the Kafka listeners do.
 */
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = {"mw.orchestrator.consumers.enabled=false", "mw.orchestrator.timer.enabled=false"})
@Import({TestInfrastructure.class, TransactionQueryApiIT.TestClock.class})
class TransactionQueryApiIT {

  @TestConfiguration(proxyBeanMethods = false)
  static class TestClock {
    @Bean
    @Primary
    MutableClock testClock() {
      return new MutableClock(TestData.NOW);
    }
  }

  private final TransactionService transactions;
  private final CoreEventService coreEvents;
  private final PartnerReplyService partnerReplies;
  private final SagaTimerService timers;
  private final MutableClock clock;
  private final JdbcTemplate jdbc;
  private final JsonMapper json;

  @LocalServerPort int port;

  @Autowired
  TransactionQueryApiIT(
      TransactionService transactions,
      CoreEventService coreEvents,
      PartnerReplyService partnerReplies,
      SagaTimerService timers,
      Clock clock,
      JdbcTemplate jdbc,
      JsonMapper json) {
    this.transactions = transactions;
    this.coreEvents = coreEvents;
    this.partnerReplies = partnerReplies;
    this.timers = timers;
    this.clock = (MutableClock) clock;
    this.jdbc = jdbc;
    this.json = json;
  }

  @BeforeEach
  void clean() {
    clock.set(TestData.NOW);
    for (String t :
        List.of("outbox", "inbox", "saga_timer", "saga_step", "partner_call", "idempotency", "transaction")) {
      jdbc.update("delete from " + t);
    }
  }

  // --- helpers -------------------------------------------------------------------------------

  private HttpResponse<String> get(String pathAndQuery) throws Exception {
    // mw-admin-api's token: may query without orgId (who may call: InternalApiAuthIT)
    HttpRequest req =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + pathAndQuery))
            .header("Authorization", mfs.mw.orchestrator.security.InternalTokens.bearer("mw-admin-api"))
            .build();
    return HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
  }

  private JsonNode ok(String pathAndQuery) throws Exception {
    HttpResponse<String> res = get(pathAndQuery);
    assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
    return json.readTree(res.body());
  }

  private static String enc(String v) {
    return URLEncoder.encode(v, StandardCharsets.UTF_8);
  }

  private static List<String> txnIds(JsonNode page) {
    return page.path("items").valueStream().map(n -> n.path("txnId").asString()).toList();
  }

  private String p2p() {
    TxnRequest req = TestData.p2p(TestData.txnId(), "key-" + TestData.txnId(), 5_000);
    transactions.accept(req, "gw-1");
    return req.txnId();
  }

  private String bulkChild(String msisdn) {
    TxnRequest req = TestData.bulkChild(TestData.txnId(), "key-" + TestData.txnId(), 1_000, msisdn);
    transactions.accept(req, null);
    return req.txnId();
  }

  private CoreEvent coreEvent(String type, String txnId, String commandId, String payload) {
    return json.readValue(
        "{\"schemaVersion\":1,\"eventType\":\"" + type + "\",\"eventId\":\"evt-" + UUID.randomUUID()
            + "\",\"commandId\":\"" + commandId + "\",\"txnId\":\"" + txnId
            + "\",\"coreRef\":\"CORE-7\",\"occurredAt\":\"2026-11-03T08:15:31Z\",\"outcome\":\"SUCCESS\","
            + "\"payload\":" + payload + "}",
        CoreEvent.class);
  }

  private PartnerReply reply(String txnId, String requestId, String operation, String outcome, String extra) {
    return json.readValue(
        "{\"schemaVersion\":1,\"family\":\"telco\",\"requestId\":\"" + requestId + "\",\"txnId\":\"" + txnId
            + "\",\"correlationId\":\"corr-1\",\"partnerId\":\"gp\",\"repliedAt\":\"2026-11-03T08:15:33Z\","
            + "\"payload\":{\"operation\":\"" + operation + "\",\"outcome\":\"" + outcome + "\"" + extra + "}}",
        PartnerReply.class);
  }

  private String lastInquiryRequestId(String txnId) {
    return jdbc.queryForObject(
        "select payload ->> 'requestId' from outbox where topic = 'mw.partner.request.telco.v1' and msg_key = ?"
            + " and payload -> 'payload' ->> 'operation' = 'STATUS_QUERY' order by id desc limit 1",
        String.class,
        txnId);
  }

  /** A TOPUP whose PAY outcome is UNKNOWN, resolved by the first status inquiry (spec 7.4). */
  private String tsqdTopup() {
    TxnRequest req = TestData.topup(TestData.txnId(), "key-" + TestData.txnId(), 2_000, "01711000002");
    transactions.accept(req, "gw-1");
    String txn = req.txnId();
    coreEvents.handle(
        coreEvent("TransactionValidated", txn, txn + ":validate", "{\"fee\":{\"valueMinor\":0,\"currency\":\"BDT\"}}"));
    coreEvents.handle(coreEvent("FundsHeld", txn, txn + ":hold", "{\"holdId\":\"HOLD-CORE-7\"}"));
    clock.advance(Duration.ofSeconds(1));
    partnerReplies.handle(
        reply(txn, PartnerRequestMapper.requestId(txn, "PAY"), "PAY", "UNKNOWN",
            ",\"errorCode\":\"MW-TMO-PARTNER_TIMEOUT\""));
    clock.advance(Duration.ofSeconds(30));
    timers.sweep();
    clock.advance(Duration.ofSeconds(1));
    partnerReplies.handle(
        reply(txn, lastInquiryRequestId(txn), "STATUS_QUERY", "SUCCESS", ",\"partnerRef\":\"OCS-TSQ-1\""));
    coreEvents.handle(coreEvent("HoldCommitted", txn, txn + ":commit", "{}"));
    return txn;
  }

  // --- search --------------------------------------------------------------------------------

  @Test
  void anMsisdnSearchFindsTheInitiatorsTransactionsThroughTheKeyedHashAndMasksThem() throws Exception {
    String txn = p2p();

    JsonNode page = ok("/internal/v1/transactions?msisdn=" + enc("+8801711000001"));

    assertThat(txnIds(page)).containsExactly(txn);
    JsonNode item = page.path("items").get(0);
    assertThat(item.path("txnType").asString()).isEqualTo("P2P");
    assertThat(item.path("status").asString()).isEqualTo("VALIDATING");
    assertThat(item.path("amount").path("valueMinor").asLong()).isEqualTo(5_000);
    assertThat(item.path("initiator").path("id").asString()).isEqualTo("0171****001");
    assertThat(item.path("creditParty").path("id").asString()).isEqualTo("0171****002");
    assertThat(page.toString())
        .doesNotContain("01711000001")
        .doesNotContain("01711000002")
        .doesNotContain("deviceId")
        .doesNotContain("idempotencyKey");

    assertThat(txnIds(ok("/internal/v1/transactions?msisdn=01711000009"))).isEmpty();
  }

  @Test
  void theSearchHashIsStoredAndTheRawMsisdnIsNotIndexed() {
    String txn = p2p();

    byte[] stored =
        jdbc.queryForObject("select initiator_hash from transaction where txn_id = ?::uuid", byte[].class, txn);
    assertThat(stored).hasSize(32);
    List<String> indexed =
        jdbc.queryForList(
            "select indexdef from pg_indexes where schemaname = 'txn' and tablename = 'transaction'", String.class);
    assertThat(indexed).noneMatch(d -> d.contains("initiator_id"));
    assertThat(indexed).anyMatch(d -> d.contains("initiator_hash"));
  }

  @Test
  void searchByTxnIdPartnerRefStatusTypeAndDateWindow() throws Exception {
    String first = p2p();
    clock.advance(Duration.ofMinutes(10));
    String topup = tsqdTopup();

    assertThat(txnIds(ok("/internal/v1/transactions?txnId=" + first))).containsExactly(first);
    assertThat(txnIds(ok("/internal/v1/transactions?partnerRef=OCS-TSQ-1"))).containsExactly(topup);
    assertThat(txnIds(ok("/internal/v1/transactions?status=COMPLETED"))).containsExactly(topup);
    assertThat(txnIds(ok("/internal/v1/transactions?txnType=P2P"))).containsExactly(first);
    // newest first; [from, to) on createdAt
    assertThat(txnIds(ok("/internal/v1/transactions"))).containsExactly(topup, first);
    Instant cut = TestData.NOW.plus(Duration.ofMinutes(5));
    assertThat(txnIds(ok("/internal/v1/transactions?from=" + cut))).containsExactly(topup);
    assertThat(txnIds(ok("/internal/v1/transactions?to=" + cut))).containsExactly(first);
    assertThat(txnIds(ok("/internal/v1/transactions?limit=1"))).containsExactly(topup);
    assertThat(txnIds(ok("/internal/v1/transactions?limit=1&offset=1"))).containsExactly(first);
  }

  @Test
  void anOrgSearchFindsOnlyThatOrgsTransactionsAndItsBatchChildren() throws Exception {
    String child = bulkChild("01711000003");
    p2p();

    JsonNode page = ok("/internal/v1/transactions?orgId=ORG001");
    assertThat(txnIds(page)).containsExactly(child);
    JsonNode item = page.path("items").get(0);
    assertThat(item.path("creditParty").path("id").asString()).isEqualTo("0171****003");
    assertThat(item.path("attributes").path("parentTxnId").asString())
        .isEqualTo("01933b8e-0000-7000-8000-000000000001");
    assertThat(txnIds(ok("/internal/v1/transactions?orgId=ORG002"))).isEmpty();
    assertThat(txnIds(ok("/internal/v1/transactions?orgId=ORG001&parentTxnId=01933b8e-0000-7000-8000-000000000001")))
        .containsExactly(child);
    assertThat(txnIds(ok("/internal/v1/transactions?orgId=ORG001&parentTxnId=other"))).isEmpty();
  }

  @Test
  void badFiltersAreTheValidationEnvelope() throws Exception {
    for (String q :
        List.of("status=NOPE", "txnId=not-a-uuid", "limit=0", "limit=201", "from=yesterday",
            "from=2026-11-03T09:00:00Z&to=2026-11-03T08:00:00Z")) {
      HttpResponse<String> res = get("/internal/v1/transactions?" + q);
      assertThat(res.statusCode()).as(q).isEqualTo(400);
      assertThat(json.readTree(res.body()).path("code").asString()).as(q).isEqualTo("MW-VAL-001");
    }
  }

  // --- detail and timeline -------------------------------------------------------------------

  @Test
  void theTimelineOfATsqdTopupListsEveryStateChangeInOrderAndItsPartnerCalls() throws Exception {
    String txn = tsqdTopup();

    JsonNode detail = ok("/internal/v1/transactions/" + txn);

    assertThat(detail.path("transaction").path("status").asString()).isEqualTo("COMPLETED");
    assertThat(detail.path("transaction").path("partnerRef").asString()).isEqualTo("OCS-TSQ-1");
    List<String> changes =
        detail.path("timeline").valueStream()
            .map(s -> s.path("from").asString() + ">" + s.path("to").asString())
            .toList();
    assertThat(changes)
        .containsExactly(
            "RECEIVED>VALIDATING", "VALIDATING>HOLDING", "HOLDING>PARTNER_PENDING", "PARTNER_PENDING>UNKNOWN",
            "UNKNOWN>COMMITTING", "COMMITTING>COMPLETED");
    List<Instant> at =
        detail.path("timeline").valueStream().map(s -> Instant.parse(s.path("at").asString())).toList();
    assertThat(at).isSorted();

    JsonNode calls = detail.path("partnerCalls");
    assertThat(calls).hasSize(2);
    assertThat(calls.get(0).path("operation").asString()).isEqualTo("PAY");
    assertThat(calls.get(0).path("requestId").asString()).isEqualTo(PartnerRequestMapper.requestId(txn, "PAY"));
    assertThat(calls.get(0).path("outcome").asString()).isEqualTo("UNKNOWN");
    assertThat(calls.get(0).path("errorCode").asString()).isEqualTo("MW-TMO-PARTNER_TIMEOUT");
    assertThat(calls.get(0).path("partnerId").asString()).isEqualTo("gp");
    assertThat(calls.get(0).path("family").asString()).isEqualTo("telco");
    assertThat(calls.get(1).path("operation").asString()).isEqualTo("STATUS_QUERY");
    assertThat(calls.get(1).path("outcome").asString()).isEqualTo("SUCCESS");
    assertThat(calls.get(1).path("partnerRef").asString()).isEqualTo("OCS-TSQ-1");
    assertThat(calls.get(1).has("answeredAt")).isTrue();
    assertThat(detail.toString())
        .doesNotContain("01711000001")
        .doesNotContain("01711000002")
        .doesNotContain("encryptedPin");
  }

  @Test
  void anUnknownTxnIdIsTheNotFoundEnvelope() throws Exception {
    HttpResponse<String> res = get("/internal/v1/transactions/" + TestData.txnId());
    assertThat(res.statusCode()).isEqualTo(404);
    assertThat(json.readTree(res.body()).path("code").asString()).isEqualTo("MW-VAL-003");
  }

  @Test
  void anOrgScopedDetailHidesOtherInitiatorsTransactions() throws Exception {
    String child = bulkChild("01711000003");
    String other = p2p();

    assertThat(ok("/internal/v1/transactions/" + child + "?orgId=ORG001").path("transaction").path("txnId").asString())
        .isEqualTo(child);
    assertThat(get("/internal/v1/transactions/" + child + "?orgId=ORG002").statusCode()).isEqualTo(404);
    assertThat(get("/internal/v1/transactions/" + other + "?orgId=ORG001").statusCode()).isEqualTo(404);
  }
}
