package mfs.mw.ussd.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import mfs.mw.ussd.client.AuthClient;
import mfs.mw.ussd.config.UssdProperties;
import mfs.mw.ussd.dto.Amount;
import mfs.mw.ussd.dto.Balance;
import mfs.mw.ussd.dto.EncryptedPin;
import mfs.mw.ussd.dto.ErrorDetail;
import mfs.mw.ussd.dto.ErrorEnvelope;
import mfs.mw.ussd.dto.FlowOutcome;
import mfs.mw.ussd.dto.FlowRequest;
import mfs.mw.ussd.dto.StatusMessage;
import mfs.mw.ussd.dto.TxnAuthorisation;
import mfs.mw.ussd.dto.TxnAuthorisationRequest;
import mfs.mw.ussd.dto.TxnRequest;
import mfs.mw.ussd.dto.UssdSession;
import mfs.mw.ussd.exception.AuthRefusedException;
import mfs.mw.ussd.exception.ErrorCodes;
import mfs.mw.ussd.exception.MwException;
import mfs.mw.ussd.mapper.TxnRequestMapper;
import mfs.mw.ussd.messaging.EventMetadata;
import mfs.mw.ussd.messaging.EventPublisher;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The flow asks mw-auth before it publishes (ticket 72, D22): the authorisation carries exactly the
 * published request's values, the request goes out with the assertion and no PIN; a refusal ends
 * the flow with nothing published.
 */
class KafkaTransactionFlowTest {

  static final Instant NOW = Instant.parse("2026-11-15T10:00:00Z");
  static final EncryptedPin PIN = new EncryptedPin("PIN_TRANSPORT.v1", "Y2lwaGVy");
  static final String ASSERTION = "eyJhbGciOiJFUzI1NiJ9.eyJqdGkiOiJ0In0.c2lnbmF0dXJl";

  final EventPublisher publisher = mock(EventPublisher.class);
  final AuthClient auth = mock(AuthClient.class);
  final PinTransport pins = mock(PinTransport.class);
  final TxnRequestMapper mapper = new TxnRequestMapper();
  final ReplyWaiters waiters = new ReplyWaiters();
  final KafkaTransactionFlow flow =
      new KafkaTransactionFlow(publisher, waiters, mapper, auth, pins, props(), Clock.fixed(NOW, ZoneOffset.UTC));

  static UssdProperties props() {
    return new UssdProperties(
        "test-instance", "mw.txn.request.v1", "mw.txn.status.v1", Duration.ofMillis(10), Duration.ofSeconds(30),
        "bn", 3, Map.of(), Map.of(), Map.of());
  }

  static FlowRequest p2p() {
    UssdSession s = new UssdSession("robi", "sess-1", "01712345678", "main", 2, "en", "p2p.send",
        List.of("main:1", "p2p.pin"), Map.of(), 0, List.of(), false, NOW);
    return new FlowRequest("P2P", s, "main:1>p2p.pin", Map.of("creditParty", "01812345678", "amount", "500.00"), PIN);
  }

  static FlowRequest balance() {
    UssdSession s = new UssdSession("robi", "sess-2", "01712345678", "main", 3, "bn", "bal.check",
        List.of("main:5", "bal.pin"), Map.of(), 0, List.of(), false, NOW);
    return new FlowRequest("BALANCE", s, "main:5>bal.pin", Map.of(), PIN);
  }

  /** The orchestrator's final answer to the published request, as mw.txn.status.v1 carries it. */
  static StatusMessage answer(TxnRequest req, String status, Balance balance) {
    return new StatusMessage(1, req.txnId(), req.idempotencyKey(), req.correlationId(), req.channel(), req.txnType(),
        status, "VALIDATING", req.initiator(), req.debitParty(), req.creditParty(), req.amount(), null, "CORE-1", null,
        req.locale(), null, req.requestedAt(), NOW, balance);
  }

  /** The publisher "sends" and the orchestrator answers at once with {@code status}. */
  void orchestratorAnswers(String status, Balance balance) {
    doAnswer(inv -> {
      waiters.offer(answer((TxnRequest) inv.getArgument(3), status, balance));
      return null;
    }).when(publisher).publish(anyString(), anyString(), any(EventMetadata.class), any(), anyMap());
  }

  static AuthRefusedException refusal(int status, String code, List<ErrorDetail> details) {
    return new AuthRefusedException(status, new ErrorEnvelope(code, "refused", null, null, "c", false, details));
  }

  @Test
  void publishesWithTheAssertionForExactlyTheAuthorisedValues() {
    when(auth.authorise(any())).thenReturn(new TxnAuthorisation(ASSERTION, NOW.plusSeconds(60), false));

    FlowOutcome out = flow.submit(p2p());

    // no answer within the (test) budget
    assertThat(out.kind()).isEqualTo(FlowOutcome.Kind.PROCESSING);
    ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
    verify(publisher).publish(eq("mw.txn.request.v1"), anyString(), any(EventMetadata.class), published.capture(), anyMap());
    TxnRequest req = (TxnRequest) published.getValue();
    assertThat(req.assertion()).isEqualTo(ASSERTION);
    ArgumentCaptor<TxnAuthorisationRequest> asked = ArgumentCaptor.forClass(TxnAuthorisationRequest.class);
    verify(auth).authorise(asked.capture());
    assertThat(asked.getValue()).isEqualTo(mapper.authorisation(req.withAssertion(null), PIN));
    assertThat(asked.getValue().txn().idempotencyKey()).isEqualTo(req.idempotencyKey());
  }

  @Test
  void aWrongPinIsWrongPinWithTheTriesLeftAndNothingIsPublished() {
    when(auth.authorise(any()))
        .thenThrow(refusal(401, ErrorCodes.AUTH_WRONG_PIN, List.of(new ErrorDetail("triesLeft", "2"))));

    FlowOutcome out = flow.submit(p2p());

    assertThat(out.kind()).isEqualTo(FlowOutcome.Kind.WRONG_PIN);
    assertThat(out.triesLeft()).isEqualTo("2");
    verify(publisher, never()).publish(anyString(), anyString(), any(), any(), anyMap());
  }

  @Test
  void locksAreLocked() {
    for (String code : List.of(ErrorCodes.AUTH_PIN_LOCKED, ErrorCodes.AUTH_PIN_LOCKED_PERMANENT, ErrorCodes.AUTH_WALLET_LOCKED)) {
      doThrow(refusal(423, code, List.of())).when(auth).authorise(any());

      FlowOutcome out = flow.submit(p2p());

      assertThat(out.kind()).as(code).isEqualTo(FlowOutcome.Kind.LOCKED);
      assertThat(out.error().code()).isEqualTo(code);
    }
    verify(publisher, never()).publish(anyString(), anyString(), any(), any(), anyMap());
  }

  @Test
  void anotherRefusalFailsWithMwAuthsCode() {
    when(auth.authorise(any())).thenThrow(refusal(409, "MW-IDEM-001", List.of()));

    FlowOutcome out = flow.submit(p2p());

    assertThat(out.kind()).isEqualTo(FlowOutcome.Kind.FAILED);
    assertThat(out.error().code()).isEqualTo("MW-IDEM-001");
  }

  @Test
  void mwAuthUnavailableIsUnavailable() {
    when(auth.authorise(any())).thenThrow(new MwException(ErrorCodes.SYS_AUTH_UNAVAILABLE, "down"));

    assertThat(flow.submit(p2p()).kind()).isEqualTo(FlowOutcome.Kind.UNAVAILABLE);
    verify(publisher, never()).publish(anyString(), anyString(), any(), any(), anyMap());
    verify(pins, never()).refresh();
  }

  @Test
  void aRefusedPinBlockDropsTheTransportKey() {
    when(auth.authorise(any())).thenThrow(refusal(503, ErrorCodes.SYS_CRYPTO_REFUSED, List.of()));

    assertThat(flow.submit(p2p()).kind()).isEqualTo(FlowOutcome.Kind.UNAVAILABLE);
    verify(pins, times(1)).refresh();
  }

  // --- BALANCE (ADR D26) ---

  @Test
  void aCompletedBalanceCarriesTheAvailableBalance() {
    when(auth.authorise(any())).thenReturn(new TxnAuthorisation(ASSERTION, NOW.plusSeconds(60), false));
    Balance twelveThousand = new Balance(new Amount(1_200_000L, Amount.BDT), NOW);
    orchestratorAnswers("COMPLETED", twelveThousand);

    FlowOutcome out = flow.submit(balance());

    assertThat(out.kind()).isEqualTo(FlowOutcome.Kind.COMPLETED);
    assertThat(out.balance()).isEqualTo(twelveThousand);
    ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
    verify(publisher).publish(eq("mw.txn.request.v1"), anyString(), any(EventMetadata.class), published.capture(), anyMap());
    TxnRequest req = (TxnRequest) published.getValue();
    assertThat(req.txnType()).isEqualTo("BALANCE");
    assertThat(req.amount()).isEqualTo(new Amount(0L, Amount.BDT));
    assertThat(req.creditParty()).isEqualTo(req.debitParty());
    assertThat(req.assertion()).isEqualTo(ASSERTION);
  }

  @Test
  void aCompletedTransferCarriesNoBalance() {
    when(auth.authorise(any())).thenReturn(new TxnAuthorisation(ASSERTION, NOW.plusSeconds(60), false));
    orchestratorAnswers("COMPLETED", null);

    FlowOutcome out = flow.submit(p2p());

    assertThat(out.kind()).isEqualTo(FlowOutcome.Kind.COMPLETED);
    assertThat(out.balance()).isNull();
  }

  /**
   * The mapping of a balance check is empty, so a key from the fields alone would repeat: a second
   * check (another attempt, or after a wrong PIN) would replay the first one and its old balance.
   */
  @Test
  void everyBalanceCheckHasAFreshIdempotencyKey() {
    when(auth.authorise(any())).thenReturn(new TxnAuthorisation(ASSERTION, NOW.plusSeconds(60), false));

    flow.submit(balance());
    flow.submit(balance());

    ArgumentCaptor<TxnAuthorisationRequest> asked = ArgumentCaptor.forClass(TxnAuthorisationRequest.class);
    verify(auth, times(2)).authorise(asked.capture());
    List<String> keys = asked.getAllValues().stream().map(a -> a.txn().idempotencyKey()).toList();
    assertThat(keys).doesNotHaveDuplicates().allSatisfy(k -> assertThat(k).matches("[0-9a-f]{64}"));
  }
}
