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
        List.of(
            "outbox", "inbox", "saga_timer", "saga_step", "partner_call", "core_call", "idempotency",
            "transaction")) {
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

  private String billPay() {
    TxnRequest req = TestData.billPay(TestData.txnId(), "key-" + TestData.txnId(), 66_700, "1234567");
    transactions.accept(req, "gw-1");
    return req.txnId();
  }

  private String merchantPay(String merchant) {
    TxnRequest req = TestData.merchantPay(TestData.txnId(), "key-" + TestData.txnId(), 2_500, merchant);
    transactions.accept(req, "gw-1");
    return req.txnId();
  }

  private static List<String> roles(JsonNode page) {
    return page.path("items").valueStream().map(n -> n.path("role").asString()).toList();
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
  void anOrgIdNeverMatchesAnMsisdnInitiatorWithTheSameText() throws Exception {
    String p2p = p2p(); // initiator {MSISDN, 01711000001}

    assertThat(txnIds(ok("/internal/v1/transactions?orgId=01711000001"))).isEmpty();
    assertThat(get("/internal/v1/transactions/" + p2p + "?orgId=01711000001").statusCode()).isEqualTo(404);
    assertThat(txnIds(ok("/internal/v1/transactions?msisdn=01711000001"))).containsExactly(p2p);
  }

  @Test
  void anMsisdnSearchNeverMatchesAnOrgInitiatorWhoseIdLooksLikeAnMsisdn() throws Exception {
    TxnRequest base = TestData.bulkChild(TestData.txnId(), "key-" + TestData.txnId(), 1_000, "01711000003");
    TxnRequest req =
        new TxnRequest(
            base.schemaVersion(), base.txnId(), base.idempotencyKey(), base.correlationId(), base.channel(),
            base.txnType(), new mfs.mw.orchestrator.dto.Party("ORG", "01711000009", null, null), base.debitParty(),
            base.creditParty(), base.amount(), base.partner(), base.originalTxnId(), base.assertion(),
            base.locale(), base.metadata(), base.attributes(), base.requestedAt());
    transactions.accept(req, null);

    assertThat(txnIds(ok("/internal/v1/transactions?msisdn=01711000009"))).isEmpty();
    assertThat(txnIds(ok("/internal/v1/transactions?orgId=01711000009"))).containsExactly(req.txnId());
  }

  @Test
  void anOrgSeesWhatItInitiatedAndWhatItWasPaidEachTaggedWithItsRole() throws Exception {
    String child = bulkChild("01711000003");
    clock.advance(Duration.ofSeconds(1));
    String bill = billPay();
    clock.advance(Duration.ofSeconds(1));
    String merchant = merchantPay("01911000001");
    clock.advance(Duration.ofSeconds(1));
    merchantPay("01911000002"); // another merchant: not ORG002's
    p2p();

    JsonNode org2 = ok("/internal/v1/transactions?orgId=ORG002");
    assertThat(txnIds(org2)).containsExactly(merchant, bill);
    assertThat(roles(org2)).containsExactly("PAYEE", "PAYEE");
    assertThat(org2.toString()).doesNotContain("01711000001").doesNotContain("01911000001");

    JsonNode org1 = ok("/internal/v1/transactions?orgId=ORG001");
    assertThat(txnIds(org1)).containsExactly(child);
    assertThat(roles(org1)).containsExactly("INITIATOR");

    // other filters still apply to the payee side
    assertThat(txnIds(ok("/internal/v1/transactions?orgId=ORG002&txnType=BILL_PAY"))).containsExactly(bill);
    // an unscoped search tags nothing
    assertThat(ok("/internal/v1/transactions?txnId=" + bill).path("items").get(0).has("role")).isFalse();

    JsonNode detail = ok("/internal/v1/transactions/" + bill + "?orgId=ORG002");
    assertThat(detail.path("transaction").path("role").asString()).isEqualTo("PAYEE");
    assertThat(ok("/internal/v1/transactions/" + child + "?orgId=ORG001").path("transaction").path("role").asString())
        .isEqualTo("INITIATOR");
    assertThat(get("/internal/v1/transactions/" + bill + "?orgId=ORG001").statusCode()).isEqualTo(404);
    assertThat(get("/internal/v1/transactions/" + child + "?orgId=ORG002").statusCode()).isEqualTo(404);
  }

  @Test
  void thePayeeHashIsStoredForBillsAndMerchantPaymentsOnly() {
    String bill = billPay();
    String merchant = merchantPay("01911000001");
    String p2p = p2p();

    String q = "select payee_hash from transaction where txn_id = ?::uuid";
    assertThat(jdbc.queryForObject(q, byte[].class, bill)).hasSize(32);
    assertThat(jdbc.queryForObject(q, byte[].class, merchant)).hasSize(32);
    assertThat(jdbc.queryForObject(q, byte[].class, p2p)).isNull();
    List<String> indexed =
        jdbc.queryForList(
            "select indexdef from pg_indexes where schemaname = 'txn' and tablename = 'transaction'", String.class);
    assertThat(indexed).anyMatch(d -> d.contains("payee_hash"));
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

  // --- wallet history (ADR D25) -------------------------------------------------------------

  /** As mw-app-gateway asks: its wallet-scoped token, always with walletId. */
  private JsonNode wallet(String walletId, String extra) throws Exception {
    HttpRequest req =
        HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/internal/v1/transactions?walletId=" + enc(walletId) + extra))
            .header("Authorization", mfs.mw.orchestrator.security.InternalTokens.bearer("mw-app-gateway"))
            .build();
    HttpResponse<String> res = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
    assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
    return json.readTree(res.body());
  }

  private static List<String> directions(JsonNode page) {
    return page.path("items").valueStream().map(n -> n.path("direction").asString()).toList();
  }

  private void setStatus(String txnId, String status) {
    jdbc.update("update transaction set status = ? where txn_id = ?::uuid", status, txnId);
  }

  private String accepted(TxnRequest req, String status) {
    transactions.accept(req, "gw-1");
    if (status != null) {
      setStatus(req.txnId(), status);
    }
    clock.advance(Duration.ofSeconds(1));
    return req.txnId();
  }

  private static TxnRequest cashIn(String customer) {
    TxnRequest base = TestData.p2p(TestData.txnId(), "key-" + TestData.txnId(), 3_000);
    return new TxnRequest(
        base.schemaVersion(), base.txnId(), base.idempotencyKey(), base.correlationId(), "AGENT_APP", "CASH_IN",
        new mfs.mw.orchestrator.dto.Party("MSISDN", "01811000001", null, null),
        new mfs.mw.orchestrator.dto.Party("WALLET", "01811000001", null, "AGENT"),
        new mfs.mw.orchestrator.dto.Party("WALLET", customer, null, "CUSTOMER"), base.amount(), null, null,
        base.assertion(), base.locale(), null, null, base.requestedAt());
  }

  @Test
  void aWalletsHistoryHasWhatItSentAndWhatReachedItEveryChannelTaggedWithTheDirection() throws Exception {
    String sent = accepted(TestData.p2p(TestData.txnId(), "key-" + TestData.txnId(), 5_000), "COMPLETED");
    String cashIn = accepted(cashIn("01711000001"), "COMPLETED");
    String bulk = accepted(TestData.bulkChild(TestData.txnId(), "key-" + TestData.txnId(), 1_000, "01711000001"),
        "COMPLETED");
    String merchant = accepted(TestData.merchantPay(TestData.txnId(), "key-" + TestData.txnId(), 2_500, "01911000001"),
        "COMPLETED");
    String addMoney = accepted(TestData.addMoneyBank(TestData.txnId(), "key-" + TestData.txnId(), 10_000,
        "1234567890123"), "COMPLETED");
    String topup = accepted(TestData.topup(TestData.txnId(), "key-" + TestData.txnId(), 2_000, "01711000002"), null);

    JsonNode mine = wallet("01711000001", "");
    assertThat(txnIds(mine)).containsExactly(topup, addMoney, merchant, bulk, cashIn, sent);
    assertThat(directions(mine)).containsExactly("OUT", "IN", "OUT", "IN", "IN", "OUT");
    // any accepted form of the number finds the same wallet
    assertThat(txnIds(wallet("+880 1711-000001", ""))).isEqualTo(txnIds(mine));
    // the bulk credit shows the org wallet masked, the cash-in the agent masked
    JsonNode bulkItem = mine.path("items").get(3);
    assertThat(bulkItem.path("debitParty").path("type").asString()).isEqualTo("ORG");
    assertThat(bulkItem.path("debitParty").path("id").asString()).doesNotContain("01311000001");
    assertThat(mine.path("items").get(4).path("debitParty").path("id").asString()).isEqualTo("0181****001");
    assertThat(mine.toString())
        .doesNotContain("01711000001").doesNotContain("01811000001").doesNotContain("01911000001")
        .doesNotContain("1234567890123");

    // the payee sides: customer02 got the P2P (not the top-up of its number), the merchant its payment
    JsonNode payee = wallet("01711000002", "");
    assertThat(txnIds(payee)).containsExactly(sent);
    assertThat(directions(payee)).containsExactly("IN");
    assertThat(txnIds(wallet("01911000001", ""))).containsExactly(merchant);
    assertThat(txnIds(wallet("01811000001", ""))).containsExactly(cashIn);
    assertThat(directions(wallet("01811000001", ""))).containsExactly("OUT");
  }

  @Test
  void aTransferToTheWalletIsShownOnlyOnceTheMoneyReachedIt() throws Exception {
    String failed = accepted(TestData.p2p(TestData.txnId(), "key-" + TestData.txnId(), 5_000), "FAILED");
    String running = accepted(TestData.p2p(TestData.txnId(), "key-" + TestData.txnId(), 5_000), null);
    String reversed = accepted(TestData.p2p(TestData.txnId(), "key-" + TestData.txnId(), 5_000), "REVERSED");
    String reversing = accepted(TestData.p2p(TestData.txnId(), "key-" + TestData.txnId(), 5_000), "REVERSING");

    assertThat(txnIds(wallet("01711000002", ""))).containsExactly(reversing, reversed);
    // the payer sees every one of its own payments, whatever their status
    assertThat(txnIds(wallet("01711000001", ""))).containsExactly(reversing, reversed, running, failed);
  }

  @Test
  void theWalletHistoryFiltersByDirectionTypeAndDateAndPages() throws Exception {
    String sent = accepted(TestData.p2p(TestData.txnId(), "key-" + TestData.txnId(), 5_000), "COMPLETED");
    Instant cut = clock.instant();
    String cashIn = accepted(cashIn("01711000001"), "COMPLETED");
    String topup = accepted(TestData.topup(TestData.txnId(), "key-" + TestData.txnId(), 2_000, "01711000002"), null);

    assertThat(txnIds(wallet("01711000001", "&direction=OUT"))).containsExactly(topup, sent);
    assertThat(txnIds(wallet("01711000001", "&direction=IN"))).containsExactly(cashIn);
    assertThat(txnIds(wallet("01711000001", "&txnType=P2P"))).containsExactly(sent);
    assertThat(txnIds(wallet("01711000001", "&from=" + cut))).containsExactly(topup, cashIn);
    assertThat(txnIds(wallet("01711000001", "&to=" + cut))).containsExactly(sent);
    assertThat(txnIds(wallet("01711000001", "&limit=2"))).containsExactly(topup, cashIn);
    assertThat(txnIds(wallet("01711000001", "&limit=2&offset=2"))).containsExactly(sent);
    assertThat(txnIds(wallet("01711000009", ""))).isEmpty();
  }

  @Test
  void aDirectionNeedsAWalletAndAnUnscopedSearchTagsNoDirection() throws Exception {
    String sent = accepted(TestData.p2p(TestData.txnId(), "key-" + TestData.txnId(), 5_000), "COMPLETED");

    HttpResponse<String> res = get("/internal/v1/transactions?direction=IN");
    assertThat(res.statusCode()).isEqualTo(400);
    assertThat(json.readTree(res.body()).path("code").asString()).isEqualTo("MW-VAL-001");
    assertThat(get("/internal/v1/transactions?walletId=01711000001&direction=SIDEWAYS").statusCode()).isEqualTo(400);
    assertThat(ok("/internal/v1/transactions?txnId=" + sent).path("items").get(0).has("direction")).isFalse();
  }

  @Test
  void theWalletIndexIsKeyedAndTheRawNumberIsNotStored() {
    String sent = accepted(TestData.p2p(TestData.txnId(), "key-" + TestData.txnId(), 5_000), null);

    List<byte[]> hashes =
        jdbc.queryForList("select wallet_hash from wallet_txn where txn_id = ?::uuid", byte[].class, sent);
    assertThat(hashes).hasSize(2).allSatisfy(h -> assertThat(h).hasSize(32));
    List<String> columns =
        jdbc.queryForList(
            "select column_name from information_schema.columns where table_schema = 'txn' and table_name = 'wallet_txn'",
            String.class);
    assertThat(columns).containsExactlyInAnyOrder("wallet_hash", "txn_id", "direction", "created_at");
  }

  // --- detail and timeline -------------------------------------------------------------------

  @Test
  void theTimelineOfATsqdTopupListsEveryStateChangeInOrderAndItsPartnerCalls() throws Exception {
    String txn = tsqdTopup();

    JsonNode detail = ok("/internal/v1/transactions/" + txn);

    assertThat(detail.path("transaction").path("status").asString()).isEqualTo("COMPLETED");
    assertThat(detail.path("transaction").path("partnerRef").asString()).isEqualTo("OCS-TSQ-1");
    List<String> entries =
        detail.path("timeline").valueStream()
            .map(
                s ->
                    s.path("kind").asString().equals("STATE")
                        ? s.path("from").asString() + ">" + s.path("to").asString()
                        : s.path("command").asString() + "=" + s.path("result").asString() + "/"
                            + s.path("outcome").asString())
            .toList();
    assertThat(entries)
        .containsExactly(
            "RECEIVED>VALIDATING",
            "ValidateTransaction=TransactionValidated/SUCCESS",
            "VALIDATING>HOLDING",
            "HoldFunds=FundsHeld/SUCCESS",
            "HOLDING>PARTNER_PENDING",
            "PARTNER_PENDING>UNKNOWN",
            "UNKNOWN>COMMITTING",
            "CommitHold=HoldCommitted/SUCCESS",
            "COMMITTING>COMPLETED");
    JsonNode hold = detail.path("timeline").get(3);
    assertThat(hold.path("commandId").asString()).isEqualTo(txn + ":hold");
    assertThat(hold.path("sends").asInt()).isEqualTo(1);
    assertThat(hold.has("resultAt")).isTrue();
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
  void aResentCoreCommandIsOneTimelineEntryWithItsSendCountAndTheFailureReason() throws Exception {
    TxnRequest req = TestData.topup(TestData.txnId(), "key-" + TestData.txnId(), 2_000, "01711000002");
    transactions.accept(req, "gw-1");
    String txn = req.txnId();
    coreEvents.handle(
        coreEvent("TransactionValidated", txn, txn + ":validate", "{\"fee\":{\"valueMinor\":0,\"currency\":\"BDT\"}}"));
    coreEvents.handle(coreEvent("FundsHeld", txn, txn + ":hold", "{\"holdId\":\"HOLD-CORE-7\"}"));
    partnerReplies.handle(
        reply(txn, PartnerRequestMapper.requestId(txn, "PAY"), "PAY", "FAILED",
            ",\"errorCode\":\"MW-PTR-GP-NUMBER_BARRED\""));
    clock.advance(Duration.ofSeconds(6));
    timers.sweep(); // RELEASING unanswered: ReleaseHold resent under the same commandId

    JsonNode pending = ok("/internal/v1/transactions/" + txn).path("timeline");
    JsonNode release = pending.get(pending.size() - 1);
    assertThat(release.path("command").asString()).isEqualTo("ReleaseHold");
    assertThat(release.path("sends").asInt()).isEqualTo(2);
    assertThat(release.has("result")).isFalse();

    coreEvents.handle(
        json.readValue(
            "{\"schemaVersion\":1,\"eventType\":\"ReleaseFailed\",\"eventId\":\"evt-" + UUID.randomUUID()
                + "\",\"commandId\":\"" + txn + ":release\",\"txnId\":\"" + txn
                + "\",\"occurredAt\":\"2026-11-03T08:15:40Z\",\"outcome\":\"FAILURE\","
                + "\"reasonCode\":\"HOLD_ALREADY_COMMITTED\",\"payload\":{}}",
            CoreEvent.class));

    JsonNode timeline = ok("/internal/v1/transactions/" + txn).path("timeline");
    JsonNode answered =
        timeline.valueStream()
            .filter(e -> "ReleaseHold".equals(e.path("command").asString()))
            .findFirst()
            .orElseThrow();
    assertThat(answered.path("result").asString()).isEqualTo("ReleaseFailed");
    assertThat(answered.path("outcome").asString()).isEqualTo("FAILURE");
    assertThat(answered.path("reasonCode").asString()).isEqualTo("HOLD_ALREADY_COMMITTED");
    assertThat(timeline.get(timeline.size() - 1).path("to").asString()).isEqualTo("MANUAL_REVIEW");
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

  // --- reveal (ticket 36) ---------------------------------------------------------------------

  @Test
  void aRevealedDetailCarriesTheUnmaskedMsisdnsAndNothingElseUnmasked() throws Exception {
    String txn = p2p();

    JsonNode masked = ok("/internal/v1/transactions/" + txn).path("transaction");
    assertThat(masked.path("initiator").path("id").asString()).isEqualTo("0171****001");

    JsonNode revealed = ok("/internal/v1/transactions/" + txn + "?reveal=true").path("transaction");
    assertThat(revealed.path("initiator").path("id").asString()).isEqualTo("01711000001");
    assertThat(revealed.path("creditParty").path("id").asString()).isEqualTo("01711000002");
    // names stay reduced to the initial even when revealed
    String name = revealed.path("creditParty").path("name").asString("");
    assertThat(name.length()).isLessThanOrEqualTo(2);

    assertThat(ok("/internal/v1/transactions/" + txn + "?reveal=false").path("transaction").path("initiator")
            .path("id").asString())
        .isEqualTo("0171****001");
  }
}
