package mfs.mw.app.gateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import mfs.mw.app.gateway.config.AppGatewayProperties;
import mfs.mw.app.gateway.dto.Amount;
import mfs.mw.app.gateway.dto.AppUser;
import mfs.mw.app.gateway.dto.EncryptedPin;
import mfs.mw.app.gateway.dto.StatusMessage;
import mfs.mw.app.gateway.dto.TransactionRequest;
import mfs.mw.app.gateway.exception.MwException;
import mfs.mw.app.gateway.mapper.RequestMapper;
import mfs.mw.app.gateway.messaging.EventPublisher;
import mfs.mw.app.gateway.repository.AppTransactionRepository;
import org.junit.jupiter.api.Test;

/** Edge cases of the service seam that the Kafka integration tests do not reach. */
class TransactionServiceTest {

  static final AppUser USER = new AppUser("01711000001", "CUSTOMER_APP", "CUSTOMER");
  static final AppUser AGENT = new AppUser("01811000001", "AGENT_APP", "AGENT");

  final EventPublisher publisher = mock(EventPublisher.class);
  final AppTransactionRepository repo = mock(AppTransactionRepository.class);
  final ReplyWaiters waiters = new ReplyWaiters();
  final BillerService billers = mock(BillerService.class);
  final TransactionService service =
      new TransactionService(
          publisher, waiters, new RequestMapper(), repo,
          new AppGatewayProperties(
              Duration.ofMillis(50), "gw-unit", "mw.txn.request.v1", "mw.txn.status.v1",
              List.of("P2P", "CASH_IN", "CASH_OUT", "BILL_PAY"), List.of("reference"),
              new AppGatewayProperties.Jwt("http://kc/certs", "/realms/customers"),
              Map.of(
                  "P2P", List.of("CUSTOMER"),
                  "CASH_IN", List.of("AGENT"),
                  "CASH_OUT", List.of("CUSTOMER"))),
          Clock.fixed(Instant.parse("2026-11-04T06:00:00Z"), ZoneOffset.UTC),
          billers);

  static TransactionRequest body(String txnType) {
    return body(txnType, null);
  }

  static TransactionRequest body(String txnType, Map<String, String> attributes) {
    return new TransactionRequest(
        txnType, new TransactionRequest.CounterParty(null, "01711000002"), new Amount(100L, "BDT"),
        new EncryptedPin("k", "c"), null, attributes);
  }

  @Test
  void attributesOutsideTheAllowListAreMwValAndNothingIsPublished() {
    for (String key : List.of("duplicate", "requestTxnId", "billNumber")) {
      assertThatThrownBy(() -> service.submit(USER, "key", body("P2P", Map.of(key, "v"))))
          .isInstanceOfSatisfying(
              MwException.class,
              e -> {
                assertThat(e.code()).isEqualTo("MW-VAL-001");
                assertThat(e.details()).extracting(d -> d.field()).containsExactly("attributes." + key);
              });
    }
    verifyNoInteractions(publisher, repo);
    assertThat(waiters.size()).isZero();
  }

  final TransactionService withTopup =
      new TransactionService(
          publisher, waiters, new RequestMapper(), repo,
          new AppGatewayProperties(
              Duration.ofMillis(50), "gw-unit", "mw.txn.request.v1", "mw.txn.status.v1",
              List.of("P2P", "TOPUP"), List.of("reference"), new AppGatewayProperties.Jwt("http://kc/certs", "/realms/customers"),
              Map.of("P2P", List.of("CUSTOMER"), "TOPUP", List.of("CUSTOMER"))),
          Clock.fixed(Instant.parse("2026-11-04T06:00:00Z"), ZoneOffset.UTC),
          billers);

  static TransactionRequest flow(String txnType, String creditType, TransactionRequest.Partner partner) {
    return new TransactionRequest(
        txnType, new TransactionRequest.CounterParty(creditType, "01711000002"), new Amount(100L, "BDT"),
        new EncryptedPin("k", "c"), null, null, partner);
  }

  void assertRefused(TransactionRequest body, String field) {
    assertThatThrownBy(() -> withTopup.submit(USER, "key", body))
        .isInstanceOfSatisfying(
            MwException.class,
            e -> {
              assertThat(e.code()).isEqualTo("MW-VAL-001");
              assertThat(e.details()).extracting(d -> d.field()).containsExactly(field);
            });
  }

  @Test
  void aTopupNeedsAPartnerAndAnMsisdnCreditParty() {
    assertRefused(flow("TOPUP", null, null), "partner");
    assertRefused(flow("TOPUP", "WALLET", new TransactionRequest.Partner("gp", "PREPAID")), "creditParty.type");
    verifyNoInteractions(publisher, repo);
    assertThat(waiters.size()).isZero();
  }

  @Test
  void aP2pTakesNeitherAPartnerNorAnMsisdnCreditParty() {
    assertRefused(flow("P2P", null, new TransactionRequest.Partner("gp", "PREPAID")), "partner");
    assertRefused(flow("P2P", "MSISDN", null), "creditParty.type");
    verifyNoInteractions(publisher, repo);
  }

  @Test
  void aValidTopupIsPublished() {
    var r = withTopup.submit(USER, "key", flow("TOPUP", "MSISDN", new TransactionRequest.Partner("gp", "PREPAID")));
    assertThat(r.view().status()).isEqualTo("PROCESSING");
    verify(publisher).publish(anyString(), anyString(), any(), any(), anyMap());
  }

  // --- BILL_PAY (ticket 21) ------------------------------------------------------------------

  final TransactionService withBillPay =
      new TransactionService(
          publisher, waiters, new RequestMapper(), repo,
          new AppGatewayProperties(
              Duration.ofMillis(50), "gw-unit", "mw.txn.request.v1", "mw.txn.status.v1",
              List.of("P2P", "BILL_PAY"), List.of("reference"),
              new AppGatewayProperties.Jwt("http://kc/certs", "/realms/customers"),
              Map.of("P2P", List.of("CUSTOMER"), "BILL_PAY", List.of("CUSTOMER"))),
          Clock.fixed(Instant.parse("2026-11-04T06:00:00Z"), ZoneOffset.UTC),
          billers);

  static TransactionRequest bill(String creditType, String id, TransactionRequest.Partner partner) {
    return new TransactionRequest(
        "BILL_PAY", new TransactionRequest.CounterParty(creditType, id), new Amount(66_700L, "BDT"),
        new EncryptedPin("k", "c"), null, null, partner);
  }

  void assertBillRefused(TransactionRequest body, String field) {
    assertThatThrownBy(() -> withBillPay.submit(USER, "key", body))
        .isInstanceOfSatisfying(
            MwException.class,
            e -> {
              assertThat(e.code()).isEqualTo("MW-VAL-001");
              assertThat(e.details()).extracting(d -> d.field()).containsExactly(field);
            });
  }

  @Test
  void aBillPayNeedsABillerCodeAndABillerAccount() {
    assertBillRefused(bill(null, "1234567", null), "partner.billerCode");
    assertBillRefused(bill(null, "1234567", new TransactionRequest.Partner("gp", "PREPAID")), "partner.billerCode");
    assertBillRefused(bill("WALLET", "1234567", TransactionRequest.Partner.biller("DESCO")), "creditParty.type");
    assertBillRefused(bill(null, "1234567", TransactionRequest.Partner.biller("DESCO")), "creditParty.type");
    verifyNoInteractions(publisher, repo, billers);
  }

  @Test
  void otherFlowsTakeNoBillerCodeNorABillerAccount() {
    assertBillRefused(flow("P2P", null, TransactionRequest.Partner.biller("DESCO")), "partner");
    assertBillRefused(flow("P2P", "BILLER_ACCOUNT", null), "creditParty.type");
    verifyNoInteractions(publisher, repo);
  }

  @Test
  void aValidBillPayIsPublishedThroughTheBillersAggregator() {
    org.mockito.Mockito.when(billers.partnerFor("DESCO"))
        .thenReturn(new mfs.mw.app.gateway.dto.PartnerRef("billagg", "BILL", "DESCO"));

    var r = withBillPay.submit(USER, "key", bill("BILLER_ACCOUNT", "1234567", TransactionRequest.Partner.biller("DESCO")));

    assertThat(r.view().status()).isEqualTo("PROCESSING");
    var sent = org.mockito.ArgumentCaptor.forClass(Object.class);
    verify(publisher).publish(anyString(), anyString(), any(), sent.capture(), anyMap());
    var req = (mfs.mw.app.gateway.dto.TxnRequest) sent.getValue();
    assertThat(req.partner().partnerId()).isEqualTo("billagg");
    assertThat(req.partner().billerCode()).isEqualTo("DESCO");
    assertThat(req.creditParty().type()).isEqualTo("BILLER_ACCOUNT");
    assertThat(req.debitParty().id()).isEqualTo(USER.msisdn());
  }

  // --- ADD_MONEY_BANK (ticket 23) -------------------------------------------------------------

  final TransactionService withAddMoney =
      new TransactionService(
          publisher, waiters, new RequestMapper(), repo,
          new AppGatewayProperties(
              Duration.ofMillis(50), "gw-unit", "mw.txn.request.v1", "mw.txn.status.v1",
              List.of("P2P", "ADD_MONEY_BANK"), List.of("reference"),
              new AppGatewayProperties.Jwt("http://kc/certs", "/realms/customers"),
              Map.of("P2P", List.of("CUSTOMER"), "ADD_MONEY_BANK", List.of("CUSTOMER"))),
          Clock.fixed(Instant.parse("2026-11-04T06:00:00Z"), ZoneOffset.UTC),
          billers);

  static TransactionRequest addMoney(
      TransactionRequest.CounterParty credit, TransactionRequest.BankAccount debit, TransactionRequest.Partner partner) {
    return new TransactionRequest(
        "ADD_MONEY_BANK", credit, new Amount(50_000L, "BDT"), new EncryptedPin("k", "c"), null, null, partner, debit);
  }

  static final TransactionRequest.BankAccount ACCOUNT = new TransactionRequest.BankAccount("BANK_ACCOUNT", "1234567890123");

  void assertAddMoneyRefused(TransactionRequest body, String field) {
    assertThatThrownBy(() -> withAddMoney.submit(USER, "key", body))
        .isInstanceOfSatisfying(
            MwException.class,
            e -> {
              assertThat(e.code()).isEqualTo("MW-VAL-001");
              assertThat(e.details()).extracting(d -> d.field()).containsExactly(field);
            });
  }

  @Test
  void anAddMoneyNeedsABankAccountAndABankAndNoCounterparty() {
    TransactionRequest.Partner bank = TransactionRequest.Partner.bank("c2wbank");
    assertAddMoneyRefused(addMoney(null, null, bank), "debitParty");
    assertAddMoneyRefused(addMoney(null, ACCOUNT, null), "partner.partnerId");
    assertAddMoneyRefused(addMoney(null, ACCOUNT, new TransactionRequest.Partner("gp", "PREPAID")), "partner.partnerId");
    assertAddMoneyRefused(addMoney(null, ACCOUNT, TransactionRequest.Partner.biller("DESCO")), "partner.partnerId");
    assertAddMoneyRefused(
        addMoney(new TransactionRequest.CounterParty(null, "01711000002"), ACCOUNT, bank), "creditParty");
    verifyNoInteractions(publisher, repo, billers);
  }

  @Test
  void otherFlowsTakeNoBankAccountAndNeedACounterparty() {
    TransactionRequest p2pWithAccount =
        new TransactionRequest(
            "P2P", new TransactionRequest.CounterParty(null, "01711000002"), new Amount(100L, "BDT"),
            new EncryptedPin("k", "c"), null, null, null, ACCOUNT);
    assertAddMoneyRefused(p2pWithAccount, "debitParty");
    TransactionRequest p2pWithoutPayee =
        new TransactionRequest("P2P", null, new Amount(100L, "BDT"), new EncryptedPin("k", "c"), null, null, null, null);
    assertAddMoneyRefused(p2pWithoutPayee, "creditParty");
    assertAddMoneyRefused(flowWith("P2P", TransactionRequest.Partner.bank("c2wbank")), "partner");
    verifyNoInteractions(publisher, repo);
  }

  @Test
  void aTopupCannotNameABankAsOperator() {
    TransactionService withTopup =
        new TransactionService(
            publisher, waiters, new RequestMapper(), repo,
            new AppGatewayProperties(
                Duration.ofMillis(50), "gw-unit", "mw.txn.request.v1", "mw.txn.status.v1",
                List.of("TOPUP"), List.of("reference"),
                new AppGatewayProperties.Jwt("http://kc/certs", "/realms/customers"),
                Map.of("TOPUP", List.of("CUSTOMER"))),
            Clock.fixed(Instant.parse("2026-11-04T06:00:00Z"), ZoneOffset.UTC),
            billers);
    TransactionRequest topup =
        new TransactionRequest(
            "TOPUP", new TransactionRequest.CounterParty("MSISDN", "01711000002"), new Amount(100L, "BDT"),
            new EncryptedPin("k", "c"), null, null, new TransactionRequest.Partner("c2wbank", "PREPAID"));
    assertThatThrownBy(() -> withTopup.submit(USER, "key", topup))
        .isInstanceOfSatisfying(MwException.class, e -> assertThat(e.code()).isEqualTo("MW-VAL-001"));
  }

  @Test
  void aValidAddMoneyIsPublishedFromTheBankAccountIntoTheCallersWallet() {
    var r = withAddMoney.submit(USER, "key", addMoney(null, ACCOUNT, TransactionRequest.Partner.bank("c2wbank")));

    assertThat(r.view().status()).isEqualTo("PROCESSING");
    var sent = org.mockito.ArgumentCaptor.forClass(Object.class);
    verify(publisher).publish(anyString(), anyString(), any(), sent.capture(), anyMap());
    var req = (mfs.mw.app.gateway.dto.TxnRequest) sent.getValue();
    assertThat(req.txnType()).isEqualTo("ADD_MONEY_BANK");
    assertThat(req.partner().partnerId()).isEqualTo("c2wbank");
    assertThat(req.partner().productCode()).isEqualTo("C2W");
    assertThat(req.debitParty().type()).isEqualTo("BANK_ACCOUNT");
    assertThat(req.debitParty().id()).isEqualTo("1234567890123");
    assertThat(req.creditParty().type()).isEqualTo("WALLET");
    assertThat(req.creditParty().id()).isEqualTo(USER.msisdn());
    assertThat(req.initiator().id()).isEqualTo(USER.msisdn());
  }

  @Test
  void anAgentMayNotAddMoneyFromABank() {
    assertThatThrownBy(
            () -> withAddMoney.submit(AGENT, "key", addMoney(null, ACCOUNT, TransactionRequest.Partner.bank("c2wbank"))))
        .isInstanceOfSatisfying(MwException.class, e -> assertThat(e.code()).isEqualTo("MW-AUTH-002"));
    verifyNoInteractions(publisher, repo);
  }

  static TransactionRequest flowWith(String txnType, TransactionRequest.Partner partner) {
    return new TransactionRequest(
        txnType, new TransactionRequest.CounterParty(null, "01711000002"), new Amount(100L, "BDT"),
        new EncryptedPin("k", "c"), null, null, partner);
  }

  @Test
  void anUnknownBillerIsRefusedBeforeAnythingIsPublished() {
    org.mockito.Mockito.when(billers.partnerFor("NOPE"))
        .thenThrow(new MwException("MW-VAL-001", "Request validation failed"));
    assertThatThrownBy(
            () -> withBillPay.submit(USER, "key", bill("BILLER_ACCOUNT", "1234567", TransactionRequest.Partner.biller("NOPE"))))
        .isInstanceOf(MwException.class);
    verifyNoInteractions(publisher, repo);
    assertThat(waiters.size()).isZero();
  }

  @Test
  void aSupportedTypeWithoutARoleEntryIsRefusedForEveryRole() {
    for (AppUser user : List.of(USER, AGENT)) {
      assertThatThrownBy(() -> service.submit(user, "key", body("BILL_PAY")))
          .as(user.walletType())
          .isInstanceOfSatisfying(
              MwException.class,
              e -> {
                assertThat(e.code()).isEqualTo("MW-AUTH-002");
                assertThat(e.status().value()).isEqualTo(403);
              });
    }
    verifyNoInteractions(publisher, repo);
  }

  @Test
  void unsupportedTxnTypeIsMwVal010AndNothingIsPublished() {
    assertThatThrownBy(() -> service.submit(USER, "key", body("ATM_CASH_OUT")))
        .isInstanceOfSatisfying(MwException.class, e -> assertThat(e.code()).isEqualTo("MW-VAL-010"));
    verifyNoInteractions(publisher, repo);
  }

  @Test
  void aCustomerMayNotCashInAndAnAgentMayNotCashOut() {
    for (var c : List.of(Map.entry(USER, "CASH_IN"), Map.entry(AGENT, "CASH_OUT"))) {
      assertThatThrownBy(() -> service.submit(c.getKey(), "key", body(c.getValue())))
          .as(c.getValue())
          .isInstanceOfSatisfying(
              MwException.class,
              e -> {
                assertThat(e.code()).isEqualTo("MW-AUTH-002");
                assertThat(e.status().value()).isEqualTo(403);
              });
    }
    verifyNoInteractions(publisher, repo);
    assertThat(waiters.size()).isZero();
  }

  static TransactionRequest bodyTo(String txnType, String counterparty) {
    return new TransactionRequest(
        txnType, new TransactionRequest.CounterParty(null, counterparty), new Amount(100L, "BDT"),
        new EncryptedPin("k", "c"), null, null);
  }

  @Test
  void theCounterpartyMayNotBeTheInitiatorsOwnWallet() {
    for (var c : List.of(
        Map.entry(AGENT, "CASH_IN"), Map.entry(USER, "CASH_OUT"), Map.entry(USER, "P2P"))) {
      String self = "+880" + c.getKey().msisdn().substring(1);
      assertThatThrownBy(() -> service.submit(c.getKey(), "key", bodyTo(c.getValue(), self)))
          .as(c.getValue())
          .isInstanceOfSatisfying(
              MwException.class,
              e -> {
                assertThat(e.code()).isEqualTo("MW-VAL-012");
                assertThat(e.status().value()).isEqualTo(400);
                assertThat(e.details()).extracting(d -> d.field()).containsExactly("creditParty.id");
              });
    }
    verifyNoInteractions(publisher, repo);
  }

  @Test
  void anAgentMayNotSendP2p() {
    assertThatThrownBy(() -> service.submit(AGENT, "key", body("P2P")))
        .isInstanceOfSatisfying(MwException.class, e -> assertThat(e.code()).isEqualTo("MW-AUTH-002"));
    verifyNoInteractions(publisher, repo);
  }

  @Test
  void anAgentCashInIsPublishedFromTheAgentChannel() {
    var result = service.submit(AGENT, "key", body("CASH_IN"));
    assertThat(result.done()).isFalse();
    var sent = org.mockito.ArgumentCaptor.forClass(Object.class);
    verify(publisher).publish(anyString(), anyString(), any(), sent.capture(), anyMap());
    var req = (mfs.mw.app.gateway.dto.TxnRequest) sent.getValue();
    assertThat(req.channel()).isEqualTo("AGENT_APP");
    assertThat(req.txnType()).isEqualTo("CASH_IN");
    assertThat(req.initiator().id()).isEqualTo("01811000001");
    verify(repo).insertAccepted(org.mockito.ArgumentMatchers.eq("01811000001"),
        org.mockito.ArgumentMatchers.eq("AGENT_APP"), any(), any());
  }

  @Test
  void budgetExpiryLeavesNoWaiterBehind() {
    var result = service.submit(USER, "key", body("P2P"));
    assertThat(result.done()).isFalse();
    assertThat(result.view().status()).isEqualTo("PROCESSING");
    assertThat(waiters.size()).isZero();
    verify(publisher).publish(anyString(), anyString(), any(), any(), anyMap());
    verify(repo).insertAccepted(any(), any(), any(), any());
  }

  @Test
  void aFailedStatusWithoutMessageBnIsTheEnvelopeWithItsRetryableFlag() {
    org.mockito.Mockito.doAnswer(
            inv -> {
              String txnId = inv.getArgument(1);
              waiters.offer(
                  new StatusMessage(
                      1, txnId, "key", "c", "CUSTOMER_APP", "P2P", "FAILED", "VALIDATING",
                      new mfs.mw.app.gateway.dto.Party("MSISDN", USER.msisdn(), null, null),
                      null, null, null, null, null,
                      new mfs.mw.app.gateway.dto.ErrorInfo("MW-TMO-001", "Timed out", null, true),
                      null, null, null, null));
              return null;
            })
        .when(publisher)
        .publish(anyString(), anyString(), any(), any(), anyMap());

    assertThatThrownBy(() -> service.submit(USER, "key", body("P2P")))
        .isInstanceOfSatisfying(
            MwException.class,
            e -> {
              assertThat(e.code()).isEqualTo("MW-TMO-001");
              assertThat(e.retryable()).isTrue();
              assertThat(e.messageBn()).isNull();
              assertThat(e.txnId()).isNotBlank();
            });
  }

  @Test
  void interruptedWaitAnswers202() {
    Thread.currentThread().interrupt();
    try {
      assertThat(service.submit(USER, "key", body("P2P")).done()).isFalse();
    } finally {
      assertThat(Thread.interrupted()).isTrue();
    }
  }

  @Test
  void publishFailureIs503AndNothingIsRecorded() {
    org.mockito.Mockito.doThrow(new IllegalStateException("broker down"))
        .when(publisher)
        .publish(anyString(), anyString(), any(), any(), anyMap());
    assertThatThrownBy(() -> service.submit(USER, "key", body("P2P")))
        .isInstanceOfSatisfying(
            MwException.class,
            e -> {
              assertThat(e.code()).isEqualTo("MW-SYS-010");
              assertThat(e.status().value()).isEqualTo(503);
              assertThat(e.retryable()).isTrue();
            });
    verify(repo, never()).insertAccepted(any(), any(), any(), any());
    assertThat(waiters.size()).isZero();
  }

  @Test
  void findWithAMalformedOrForeignIdIsNotFound() {
    assertThatThrownBy(() -> service.find(USER, "not-a-uuid"))
        .isInstanceOfSatisfying(MwException.class, e -> assertThat(e.status().value()).isEqualTo(404));
    assertThatThrownBy(() -> service.find(USER, "019a4f6e-1c2d-7a3b-8c4d-5e6f7a8b9c0d"))
        .isInstanceOfSatisfying(MwException.class, e -> assertThat(e.code()).isEqualTo("MW-VAL-003"));
  }

  @Test
  void projectionSkipsDuplicatesAndRejectsUnownedRecords() {
    StatusProjectionService projection = new StatusProjectionService(repo);
    projection.apply(
        new StatusMessage(
            1, "T", "k", "c", "CUSTOMER_APP", "P2P", "COMPLETED", null, null, null, null, null,
            null, null, null, null, Map.of("duplicate", "true", "requestTxnId", "T2"), null, null));
    verifyNoInteractions(repo);
    assertThatThrownBy(
            () ->
                projection.apply(
                    new StatusMessage(
                        1, "T", "k", "c", "CUSTOMER_APP", "P2P", "COMPLETED", null, null, null,
                        null, null, null, null, null, null, null, null, null)))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
