package mfs.mw.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;
import mfs.mw.orchestrator.client.CoreWalletClient;
import mfs.mw.orchestrator.client.CoreWalletClient.CoreRead;
import mfs.mw.orchestrator.client.TelcoBillClient;
import mfs.mw.orchestrator.config.TopupProperties;
import mfs.mw.orchestrator.dto.Amount;
import mfs.mw.orchestrator.dto.CoreCallBodies;
import mfs.mw.orchestrator.dto.Quote;
import mfs.mw.orchestrator.dto.QuoteRequest;
import mfs.mw.orchestrator.exception.MwException;
import mfs.mw.orchestrator.service.routing.RoutingCatalog;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.json.JsonMapper;

/**
 * The quote before the PIN (ticket 105, FRS CH-APP-05): the core's charges, the payer's limit check
 * and the payee's account, read over REST, nothing moved; the payee masked.
 */
class QuoteServiceTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String PAYER = "01711000001";
  private static final String PAYEE = "01711000002";

  private static final String GP_POSTPAID = "01711000021";

  private final CoreWalletClient core = mock(CoreWalletClient.class);
  private final TelcoBillClient bills = mock(TelcoBillClient.class);
  private final RoutingCatalog routing = mock(RoutingCatalog.class);
  private final QuoteService service = new QuoteService(core, bills, routing, new TopupProperties(Map.of(), Map.of(), null));
  private final QuoteService prepaidListed = new QuoteService(core, bills, routing,
      new TopupProperties(Map.of("gp", "gp-ocs"), Map.of("gp", java.util.List.of(1_000L, 2_000L)), null));

  private static CoreRead ok(String json) {
    return new CoreRead(200, null, JSON.readTree(json));
  }

  private static QuoteRequest p2p(long amount) {
    return new QuoteRequest(PAYER, "CUSTOMER", "P2P", "CUSTOMER_APP", new QuoteRequest.Money(amount, "BDT"),
        new QuoteRequest.Counterparty("WALLET", PAYEE));
  }

  private void charges() {
    when(core.charges(any(), anyString())).thenReturn(ok("""
        {"txnType":"P2P","amount":{"valueMinor":100000,"currency":"BDT"},"fee":{"valueMinor":500,"currency":"BDT"},
         "vat":{"valueMinor":65,"currency":"BDT"},"commission":{"valueMinor":0,"currency":"BDT"},
         "total":{"valueMinor":100500,"currency":"BDT"}}"""));
  }

  private void limits(String json) {
    when(core.limits(any(), anyString())).thenReturn(ok(json));
  }

  @Test
  void aQuoteCarriesTheCoreChargesTheLimitsAndTheMaskedPayee() {
    charges();
    limits("""
        {"walletId":"01711000001","allowed":true,"remaining":{"perTxn":{"valueMinor":2500000,"currency":"BDT"},
         "daily":{"valueMinor":4900000,"currency":"BDT"},"dailyCount":49}}""");
    when(core.account(eq(PAYEE), anyString())).thenReturn(ok("""
        {"walletId":"01711000002","msisdn":"01711000002","type":"CUSTOMER","status":"ACTIVE","holderName":"Rahim Uddin"}"""));

    Quote q = service.quote(p2p(100_000), "corr-1");

    assertThat(q.fee()).isEqualTo(Amount.bdt(500));
    assertThat(q.vat()).isEqualTo(Amount.bdt(65));
    assertThat(q.commission()).isEqualTo(Amount.bdt(0));
    assertThat(q.total()).isEqualTo(Amount.bdt(100_500));
    assertThat(q.amount()).isEqualTo(Amount.bdt(100_000));
    assertThat(q.limits().allowed()).isTrue();
    assertThat(q.limits().remaining().daily()).isEqualTo(Amount.bdt(4_900_000));
    assertThat(q.limits().remaining().dailyCount()).isEqualTo(49);
    assertThat(q.limits().remaining().monthly()).isNull();
    assertThat(q.counterparty().id()).isEqualTo("0171****002");
    assertThat(q.counterparty().name()).isEqualTo("R.");
    assertThat(q.counterparty().walletType()).isEqualTo("CUSTOMER");
  }

  @Test
  void theChargesAreAskedForThePayersWalletTypeAndChannel() {
    charges();
    limits("{\"walletId\":\"01711000001\",\"allowed\":true,\"remaining\":{}}");
    when(core.account(anyString(), anyString())).thenReturn(ok("{\"walletId\":\"x\",\"type\":\"AGENT\",\"status\":\"ACTIVE\"}"));

    service.quote(new QuoteRequest(PAYER, "AGENT", "CASH_IN", "AGENT_APP", new QuoteRequest.Money(5_000, "BDT"),
        new QuoteRequest.Counterparty("WALLET", PAYEE)), "c");

    ArgumentCaptor<CoreCallBodies.Charges> charges = ArgumentCaptor.forClass(CoreCallBodies.Charges.class);
    verify(core).charges(charges.capture(), eq("c"));
    assertThat(charges.getValue()).isEqualTo(new CoreCallBodies.Charges("CASH_IN", Amount.bdt(5_000), "AGENT_APP", "AGENT"));
    verify(core).limits(new CoreCallBodies.Limits(PAYER, "CASH_IN", Amount.bdt(5_000)), "c");
  }

  @Test
  void aBrokenLimitIsAnAnswerWithTheCoreReason() {
    charges();
    limits("{\"walletId\":\"01711000001\",\"allowed\":false,\"reasonCode\":\"LIMIT_EXCEEDED_DAILY\",\"remaining\":{}}");
    when(core.account(anyString(), anyString())).thenReturn(ok("{\"walletId\":\"x\",\"type\":\"CUSTOMER\",\"status\":\"ACTIVE\"}"));

    Quote q = service.quote(p2p(100_000), "c");

    assertThat(q.limits().allowed()).isFalse();
    assertThat(q.limits().reasonCode()).isEqualTo("LIMIT_EXCEEDED_DAILY");
  }

  @Test
  void aPayeeThatIsNotAWalletIsNotLookedUp() {
    charges();
    limits("{\"walletId\":\"01711000001\",\"allowed\":true,\"remaining\":{}}");

    Quote q = service.quote(new QuoteRequest(PAYER, "CUSTOMER", "BILL_PAY", "CUSTOMER_APP",
        new QuoteRequest.Money(100_000, "BDT"), new QuoteRequest.Counterparty("BILLER_ACCOUNT", "BILL-123456")), "c");

    verify(core, never()).account(anyString(), anyString());
    assertThat(q.counterparty().id()).isEqualTo("****3456");
    assertThat(q.counterparty().name()).isNull();
  }

  @Test
  void anUnknownPayeeWalletIsTheCoreReason() {
    charges();
    limits("{\"walletId\":\"01711000001\",\"allowed\":true,\"remaining\":{}}");
    when(core.account(anyString(), anyString())).thenReturn(new CoreRead(404,
        "MW-VAL-003", JSON.readTree("{\"code\":\"MW-VAL-003\"}")));

    assertThatThrownBy(() -> service.quote(p2p(100_000), "c"))
        .isInstanceOfSatisfying(MwException.class, e -> {
          assertThat(e.code()).isEqualTo("MW-CORE-CREDIT_WALLET_NOT_FOUND");
          assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        });
  }

  @Test
  void aChargesBodyWithoutValueMinorFailsTheQuoteAndInventsNoFee() {
    when(core.charges(any(), anyString())).thenReturn(ok("""
        {"txnType":"P2P","amount":{"valueMinor":100000,"currency":"BDT"},"fee":{"currency":"BDT"},
         "vat":{"valueMinor":65,"currency":"BDT"},"commission":{"valueMinor":0,"currency":"BDT"},
         "total":{"valueMinor":100065,"currency":"BDT"}}"""));
    limits("{\"walletId\":\"01711000001\",\"allowed\":true,\"remaining\":{}}");
    when(core.account(anyString(), anyString())).thenReturn(ok("{\"walletId\":\"x\",\"type\":\"CUSTOMER\",\"status\":\"ACTIVE\"}"));

    assertThatThrownBy(() -> service.quote(p2p(100_000), "c"))
        .isInstanceOfSatisfying(MwException.class, e -> {
          assertThat(e.code()).isEqualTo("MW-SYS-CORE_UNAVAILABLE");
          assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
          assertThat(e.retryable()).isTrue();
        });

    when(core.charges(any(), anyString())).thenReturn(ok("""
        {"txnType":"P2P","fee":{"valueMinor":"500","currency":"BDT"},"vat":{"valueMinor":0,"currency":"BDT"},
         "commission":{"valueMinor":0,"currency":"BDT"}}"""));
    assertThatThrownBy(() -> service.quote(p2p(100_000), "c"))
        .isInstanceOfSatisfying(MwException.class, e -> assertThat(e.code()).isEqualTo("MW-SYS-CORE_UNAVAILABLE"));
  }

  @Test
  void aCoreRefusalOfTheChargesPassesThroughAndNoAnswerIsUnavailable() {
    when(core.charges(any(), anyString())).thenReturn(new CoreRead(422, "MW-CORE-INVALID_AMOUNT",
        JSON.readTree("{\"code\":\"MW-CORE-INVALID_AMOUNT\"}")));
    assertThatThrownBy(() -> service.quote(p2p(100_000), "c"))
        .isInstanceOfSatisfying(MwException.class, e -> assertThat(e.code()).isEqualTo("MW-CORE-INVALID_AMOUNT"));

    when(core.charges(any(), anyString())).thenReturn(new CoreRead(403, "MW-AUTH-002", null));
    assertThatThrownBy(() -> service.quote(p2p(100_000), "c"))
        .isInstanceOfSatisfying(MwException.class, e -> {
          assertThat(e.code()).isEqualTo("MW-SYS-CORE_UNAVAILABLE");
          assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
          assertThat(e.retryable()).isTrue();
        });
  }

  /** An amount outside the bill still returns both numbers. Confirm is what refuses it. */
  @Test
  void aPostpaidQuoteReturnsTheFetchedBillEvenWhenTheAmountIsOutsideIt() {
    charges();
    limits("{\"walletId\":\"01711000001\",\"allowed\":true,\"remaining\":{}}");
    when(routing.canonicalMsisdn(GP_POSTPAID)).thenReturn(GP_POSTPAID);
    when(routing.operatorOf(GP_POSTPAID)).thenReturn(Optional.of("gp"));
    when(bills.fetch("gp-ocs", GP_POSTPAID, "c")).thenReturn(new TelcoBillClient.Bill(45_000, 5_000));

    Quote q = prepaidListed.quote(new QuoteRequest(PAYER, "CUSTOMER", "TOPUP", "CUSTOMER_APP",
        new QuoteRequest.Money(100, "BDT"), new QuoteRequest.Counterparty("MSISDN", GP_POSTPAID), "POSTPAID"), "c");

    assertThat(q.outstanding()).isEqualTo(Amount.bdt(45_000));
    assertThat(q.minimum()).isEqualTo(Amount.bdt(5_000));
    verify(bills).fetch("gp-ocs", GP_POSTPAID, "c");
  }

  @Test
  void aPrepaidAmountOffTheListIsRefusedBeforeTheCoreAndNeverFetchesABill() {
    when(routing.operatorOf(GP_POSTPAID)).thenReturn(Optional.of("gp"));

    assertThatThrownBy(() -> prepaidListed.quote(new QuoteRequest(PAYER, "CUSTOMER", "TOPUP", "CUSTOMER_APP",
        new QuoteRequest.Money(3_000, "BDT"), new QuoteRequest.Counterparty("MSISDN", GP_POSTPAID), "PREPAID"), "c"))
        .isInstanceOfSatisfying(MwException.class, e -> assertThat(e.code()).isEqualTo("MW-VAL-031"));

    verify(core, never()).charges(any(), anyString());
    verify(bills, never()).fetch(anyString(), anyString(), any());
  }

  @Test
  void aPrepaidAmountOnTheListQuotesAndDoesNotFetchABill() {
    charges();
    limits("{\"walletId\":\"01711000001\",\"allowed\":true,\"remaining\":{}}");
    when(routing.operatorOf(GP_POSTPAID)).thenReturn(Optional.of("gp"));

    Quote q = prepaidListed.quote(new QuoteRequest(PAYER, "CUSTOMER", "TOPUP", "CUSTOMER_APP",
        new QuoteRequest.Money(2_000, "BDT"), new QuoteRequest.Counterparty("MSISDN", GP_POSTPAID), "PREPAID"), "c");

    assertThat(q.outstanding()).isNull();
    assertThat(q.minimum()).isNull();
    verify(bills, never()).fetch(anyString(), anyString(), any());
    verify(core).charges(any(), eq("c"));
  }
}
