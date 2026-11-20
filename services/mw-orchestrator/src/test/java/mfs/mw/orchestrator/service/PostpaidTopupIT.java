package mfs.mw.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import mfs.mw.orchestrator.CoreCalls;
import mfs.mw.orchestrator.MutableClock;
import mfs.mw.orchestrator.TestData;
import mfs.mw.orchestrator.TestInfrastructure;
import mfs.mw.orchestrator.client.CoreWalletClient;
import mfs.mw.orchestrator.client.CoreWalletClient.CoreRead;
import mfs.mw.orchestrator.client.TelcoBillClient;
import mfs.mw.orchestrator.dto.CoreEvent;
import mfs.mw.orchestrator.dto.PartnerRef;
import mfs.mw.orchestrator.dto.Quote;
import mfs.mw.orchestrator.dto.QuoteRequest;
import mfs.mw.orchestrator.dto.TxnRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Postpaid top-up amount (ticket 112b, FRS FL-03): the quote fetches the bill once and returns the
 * outstanding amount and the minimum due. Confirm pays any amount from the minimum through the
 * outstanding and does not fetch again. An amount outside that range, or a prepaid amount off the
 * operator's list, is refused before a hold.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "mw.orchestrator.consumers.enabled=false",
      "mw.orchestrator.timer.enabled=false",
      "mw.topup.denominations.gp[0]=1000",
      "mw.topup.denominations.gp[1]=2000",
      "mw.topup.denominations.gp[2]=5000",
      "mw.topup.denominations.gp[3]=20000",
      "mw.topup.denominations.gp[4]=45000"
    })
@Import({TestInfrastructure.class, CoreCalls.class, SagaServiceIT.TestClock.class})
class PostpaidTopupIT {

  private static final String GP = "01711000021";
  private static final String PARTNER_TOPIC = "mw.partner.request.telco.v1";
  private static final Map<String, String> BILL = Map.of(
      "billOutstandingMinor", "45000", "billMinimumMinor", "5000");

  private final QuoteService quotes;
  private final TransactionService transactions;
  private final CoreCalls coreEvents;
  private final CoreWalletClient core;
  private final MutableClock clock;
  private final JdbcTemplate jdbc;
  private final JsonMapper json;

  @MockitoBean TelcoBillClient bills;

  @Autowired
  PostpaidTopupIT(
      QuoteService quotes, TransactionService transactions, CoreCalls coreEvents, CoreWalletClient core, Clock clock,
      JdbcTemplate jdbc, JsonMapper json) {
    this.quotes = quotes;
    this.transactions = transactions;
    this.coreEvents = coreEvents;
    this.core = core;
    this.clock = (MutableClock) clock;
    this.jdbc = jdbc;
    this.json = json;
  }

  @BeforeEach
  void clean() {
    clock.set(TestData.NOW);
    for (String t : List.of("outbox", "inbox", "saga_timer", "saga_step", "idempotency", "transaction")) {
      jdbc.update("delete from " + t);
    }
    doReturn(read("""
        {"fee":{"valueMinor":0,"currency":"BDT"},"vat":{"valueMinor":0,"currency":"BDT"},
         "commission":{"valueMinor":0,"currency":"BDT"},"total":{"valueMinor":100,"currency":"BDT"}}"""))
        .when(core).charges(any(), any());
    doReturn(read("{\"allowed\":true,\"remaining\":{}}")).when(core).limits(any(), any());
    doReturn(new TelcoBillClient.Bill(45_000, 5_000)).when(bills).fetch(any(), any(), any());
  }

  @Test
  void aQuoteFetchesOnceAndConfirmPaysInsideTheBillWithoutFetchingAgain() {
    Quote quote = quotes.quote(postpaidQuote(100), "corr-bill");

    assertThat(quote.outstanding().valueMinor()).isEqualTo(45_000);
    assertThat(quote.minimum().valueMinor()).isEqualTo(5_000);

    String txn = accept(postpaid(20_000, BILL));
    coreEvents.answer(event("TransactionValidated", txn, txn + ":validate"));
    coreEvents.answer(event("FundsHeld", txn, txn + ":hold"));

    assertThat(statuses(txn)).contains("VALIDATING", "HOLDING", "PARTNER_PENDING");
    assertThat(operations(txn)).containsExactly("PAY");
    verify(bills, times(1)).fetch(eq("gp-ocs"), eq(GP), any());
  }

  @Test
  void anAmountOutsideTheStoredBillIsRefusedBeforeAHold() {
    for (long amount : new long[] {4_999, 45_001}) {
      String txn = accept(postpaid(amount, BILL));
      assertThat(statuses(txn)).containsExactly("RECEIVED", "REJECTED");
      assertThat(lastStatus(txn).path("error").path("code").asString()).isEqualTo("MW-VAL-030");
      assertThat(commandTypes(txn)).isEmpty();
    }
    verify(bills, never()).fetch(any(), any(), any());
  }

  @Test
  void aMissingStoredBillIsRefusedBeforeAHold() {
    String txn = accept(postpaid(20_000, null));

    assertThat(statuses(txn)).containsExactly("RECEIVED", "REJECTED");
    assertThat(lastStatus(txn).path("error").path("code").asString()).isEqualTo("MW-VAL-030");
    assertThat(commandTypes(txn)).isEmpty();
    verify(bills, never()).fetch(any(), any(), any());
  }

  @Test
  void theMinimumAndTheOutstandingThemselvesAreAccepted() {
    for (long amount : new long[] {5_000, 45_000}) {
      String txn = accept(postpaid(amount, BILL));
      assertThat(statuses(txn)).containsExactly("RECEIVED", "VALIDATING");
      assertThat(commandTypes(txn)).containsExactly("ValidateTransaction");
    }
    verify(bills, never()).fetch(any(), any(), any());
  }

  @Test
  void aPrepaidAmountOffTheListIsRefusedAndOneOnTheListIsNot() {
    String refused = accept(prepaid(3_000));
    assertThat(statuses(refused)).containsExactly("RECEIVED", "REJECTED");
    assertThat(lastStatus(refused).path("error").path("code").asString()).isEqualTo("MW-VAL-031");
    assertThat(commandTypes(refused)).isEmpty();

    String allowed = accept(prepaid(2_000));
    assertThat(statuses(allowed)).containsExactly("RECEIVED", "VALIDATING");
    assertThat(commandTypes(allowed)).containsExactly("ValidateTransaction");
    verify(bills, never()).fetch(any(), any(), any());
  }

  private CoreRead read(String body) {
    return new CoreRead(200, null, json.readTree(body));
  }

  private QuoteRequest postpaidQuote(long amount) {
    return new QuoteRequest("01711000001", "CUSTOMER", "TOPUP", "CUSTOMER_APP",
        new QuoteRequest.Money(amount, "BDT"), new QuoteRequest.Counterparty("MSISDN", GP), "POSTPAID");
  }

  private TxnRequest postpaid(long amount, Map<String, String> attributes) {
    TxnRequest t = TestData.topup(TestData.txnId(), "key-" + TestData.txnId(), amount, GP);
    return new TxnRequest(
        t.schemaVersion(), t.txnId(), t.idempotencyKey(), t.correlationId(), t.channel(), t.txnType(),
        t.initiator(), t.debitParty(), t.creditParty(), t.amount(), new PartnerRef("gp", "POSTPAID", null),
        t.originalTxnId(), t.assertion(), t.locale(), t.metadata(), attributes, t.requestedAt());
  }

  private TxnRequest prepaid(long amount) {
    return TestData.topup(TestData.txnId(), "key-" + TestData.txnId(), amount, GP);
  }

  private String accept(TxnRequest req) {
    transactions.accept(req, null);
    return req.txnId();
  }

  private List<JsonNode> outbox(String topic, String txnId) {
    return jdbc.queryForList(
            "select payload::text from outbox where topic = ? and msg_key = ? order by id", String.class, topic, txnId)
        .stream().map(json::readTree).toList();
  }

  private List<String> statuses(String txnId) {
    return outbox("mw.txn.status.v1", txnId).stream().map(n -> n.path("status").asString()).toList();
  }

  private JsonNode lastStatus(String txnId) {
    List<JsonNode> all = outbox("mw.txn.status.v1", txnId);
    return all.get(all.size() - 1);
  }

  private List<String> commandTypes(String txnId) {
    return coreEvents.commands(txnId).stream().map(c -> c.path("commandType").asString()).toList();
  }

  private List<String> operations(String txnId) {
    return outbox(PARTNER_TOPIC, txnId).stream().map(n -> n.path("payload").path("operation").asString()).toList();
  }

  private CoreEvent event(String type, String txnId, String commandId) {
    return json.readValue(
        "{\"schemaVersion\":1,\"eventType\":\"" + type + "\",\"eventId\":\"evt-" + type + "-" + commandId
            + "\",\"commandId\":\"" + commandId + "\",\"txnId\":\"" + txnId
            + "\",\"coreRef\":\"CORE-7\",\"occurredAt\":\"2026-11-03T08:15:31Z\",\"outcome\":\"SUCCESS\","
            + "\"payload\":" + ("FundsHeld".equals(type) ? "{\"holdId\":\"HOLD-CORE-7\"}" : "{\"fee\":{\"valueMinor\":0,\"currency\":\"BDT\"}}")
            + "}",
        CoreEvent.class);
  }
}
