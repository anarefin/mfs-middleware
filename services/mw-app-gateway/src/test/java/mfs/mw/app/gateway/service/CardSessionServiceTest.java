package mfs.mw.app.gateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import mfs.mw.app.gateway.client.AuthClient;
import mfs.mw.app.gateway.config.AppGatewayProperties;
import mfs.mw.app.gateway.contracts.ContractSchemas;
import mfs.mw.app.gateway.dto.Amount;
import mfs.mw.app.gateway.dto.AppUser;
import mfs.mw.app.gateway.dto.CardSessionRequest;
import mfs.mw.app.gateway.dto.CardSessionView;
import mfs.mw.app.gateway.dto.EncryptedPin;
import mfs.mw.app.gateway.dto.ErrorInfo;
import mfs.mw.app.gateway.dto.StatusMessage;
import mfs.mw.app.gateway.dto.TransactionView;
import mfs.mw.app.gateway.dto.TxnAuthorisation;
import mfs.mw.app.gateway.dto.TxnAuthorisationRequest;
import mfs.mw.app.gateway.dto.TxnRequest;
import mfs.mw.app.gateway.exception.MwException;
import mfs.mw.app.gateway.mapper.RequestMapper;
import mfs.mw.app.gateway.messaging.EventPublisher;
import mfs.mw.app.gateway.repository.AppTransactionRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

/** {@code CardSessionService} (ticket 118b): the ADD_MONEY_CARD request, the hosted page answer, refusals. */
class CardSessionServiceTest {

  static final AppUser CUSTOMER = new AppUser("01711000001", "CUSTOMER_APP", "CUSTOMER", "device-0001");
  static final AppUser AGENT = new AppUser("01811000001", "AGENT_APP", "AGENT", "device-0002");
  static final EncryptedPin PIN = new EncryptedPin("PIN_TRANSPORT.v1", "b3BhcXVl");
  static final Instant NOW = Instant.parse("2026-11-20T08:15:30Z");
  static final String PAGE = "https://localhost/cybersource/hp/sess-0001";
  static final String ASSERTION = "eyJhbGciOiJFUzI1NiJ9.eyJqdGkiOiJiIn0.c2ln";

  final EventPublisher publisher = mock(EventPublisher.class);
  final ReplyWaiters waiters = new ReplyWaiters();
  final AuthClient auth = mock(AuthClient.class);
  final AppTransactionRepository transactions = mock(AppTransactionRepository.class);
  final CardSessionService service =
      new CardSessionService(
          publisher, waiters, new RequestMapper(), transactions,
          new AppGatewayProperties(
              Duration.ofMillis(200), "gw-unit", "mw.txn.request.v1", "mw.txn.status.v1",
              List.of("P2P"), List.of("reference"),
              new AppGatewayProperties.Jwt("http://mw-auth/jwks", "mw-auth", "mw-app"),
              Map.of("P2P", List.of("CUSTOMER"), "ADD_MONEY_CARD", List.of("CUSTOMER"))),
          auth,
          Clock.fixed(NOW, ZoneOffset.UTC));

  {
    when(auth.authorise(any())).thenReturn(new TxnAuthorisation(ASSERTION, NOW.plusSeconds(60), false));
  }

  void orchestratorAnswers(java.util.function.Function<TxnRequest, StatusMessage> answer) {
    doAnswer(inv -> {
          StatusMessage s = answer.apply(inv.getArgument(3));
          if (s != null) {
            waiters.offer(s);
          }
          return null;
        })
        .when(publisher).publish(anyString(), anyString(), any(), any(), anyMap());
  }

  static StatusMessage status(TxnRequest req, String state, ErrorInfo error, Map<String, String> attributes) {
    return new StatusMessage(
        1, req.txnId(), req.idempotencyKey(), req.correlationId(), req.channel(), req.txnType(), state, "VALIDATING",
        req.initiator(), req.debitParty(), req.creditParty(), req.amount(), null, null, error, null, attributes,
        req.requestedAt(), NOW, null);
  }

  @Test
  void theOpenSessionsHostedPageIsHandedBackAndTheRequestIsACardTokenToTheOwnWallet() {
    orchestratorAnswers(req -> status(req, "PARTNER_PENDING", null,
        Map.of("sessionId", "sess-0001", "hostedPageUrl", PAGE)));

    CardSessionView view = service.start(CUSTOMER, "card-1", new CardSessionRequest(new Amount(50_000L, "BDT"), PIN, "en"));

    assertThat(view.open()).isTrue();
    assertThat(view.hostedPageUrl()).isEqualTo(PAGE);
    assertThat(view.sessionId()).isEqualTo("sess-0001");
    assertThat(view.status()).isEqualTo("PARTNER_PENDING");
    ArgumentCaptor<TxnRequest> published = ArgumentCaptor.forClass(TxnRequest.class);
    verify(publisher).publish(eq("mw.txn.request.v1"), eq(view.txnId()), any(), published.capture(),
        eq(Map.of("mw-reply-to", "gw-unit")));
    TxnRequest req = published.getValue();
    assertThat(req.txnType()).isEqualTo("ADD_MONEY_CARD");
    assertThat(req.debitParty().type()).isEqualTo("CARD_TOKEN");
    assertThat(req.debitParty().id()).isEqualTo("cybersource");
    assertThat(req.creditParty().type()).isEqualTo("WALLET");
    assertThat(req.creditParty().id()).isEqualTo("01711000001");
    assertThat(req.partner().partnerId()).isEqualTo("cybersource");
    assertThat(req.assertion()).isEqualTo(ASSERTION);
    assertThat(ContractSchemas.schema("events/mw-orchestrator/mw.txn.request.v1.schema.json")
        .validate(tools.jackson.databind.json.JsonMapper.builder().build().valueToTree(req))).isEmpty();
    ArgumentCaptor<TxnAuthorisationRequest> asked = ArgumentCaptor.forClass(TxnAuthorisationRequest.class);
    verify(auth).authorise(asked.capture());
    assertThat(asked.getValue().txn().payer().type()).isEqualTo("CARD_TOKEN");
    assertThat(asked.getValue().txn().payee().id()).isEqualTo("01711000001");
    assertThat(waiters.size()).isZero();
  }

  @Test
  void noSessionWithinTheBudgetIsProcessing() {
    CardSessionView view = service.start(CUSTOMER, "card-2", new CardSessionRequest(new Amount(50_000L, "BDT"), PIN, null));

    assertThat(view.open()).isFalse();
    assertThat(view.status()).isEqualTo(TransactionView.PROCESSING);
  }

  @Test
  void aRefusedSessionIsTheErrorEnvelope() {
    orchestratorAnswers(req -> status(req, "FAILED",
        new ErrorInfo("MW-PTR-CYBERSOURCE-MALFORMED_REPLY", "The card payment page could not be opened", null, true),
        null));

    assertThatThrownBy(() -> service.start(CUSTOMER, "card-3", new CardSessionRequest(new Amount(50_000L, "BDT"), PIN, null)))
        .isInstanceOf(MwException.class)
        .hasMessageContaining("card payment page");
  }

  @Test
  void onlyACustomerAddsMoneyFromACard() {
    assertThatThrownBy(() -> service.start(AGENT, "card-4", new CardSessionRequest(new Amount(50_000L, "BDT"), PIN, null)))
        .isInstanceOfSatisfying(MwException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.FORBIDDEN));
    verifyNoInteractions(publisher, auth);
  }

  @Test
  void theStatusIsTheCallersOwnCardTransactionOnly() {
    UUID card = UUID.randomUUID();
    UUID p2p = UUID.randomUUID();
    TransactionView cardView = new TransactionView(card.toString(), "COMPLETED", "ADD_MONEY_CARD", null, null, null,
        null, null, null, NOW, NOW);
    TransactionView p2pView = new TransactionView(p2p.toString(), "COMPLETED", "P2P", null, null, null,
        null, null, null, NOW, NOW);
    when(transactions.find(card, "01711000001")).thenReturn(Optional.of(cardView));
    when(transactions.find(p2p, "01711000001")).thenReturn(Optional.of(p2pView));

    assertThat(service.find(CUSTOMER, card.toString())).isEqualTo(cardView);
    assertThatThrownBy(() -> service.find(CUSTOMER, p2p.toString())).isInstanceOf(MwException.class);
    assertThatThrownBy(() -> service.find(CUSTOMER, "not-a-uuid")).isInstanceOf(MwException.class);
  }
}
