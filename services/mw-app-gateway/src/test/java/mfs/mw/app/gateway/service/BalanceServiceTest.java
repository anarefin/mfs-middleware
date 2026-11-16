package mfs.mw.app.gateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
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
import mfs.mw.app.gateway.client.AuthClient;
import mfs.mw.app.gateway.config.AppGatewayProperties;
import mfs.mw.app.gateway.dto.Amount;
import mfs.mw.app.gateway.dto.AppUser;
import mfs.mw.app.gateway.dto.BalanceRequest;
import mfs.mw.app.gateway.dto.BalanceView;
import mfs.mw.app.gateway.dto.EncryptedPin;
import mfs.mw.app.gateway.dto.ErrorDetail;
import mfs.mw.app.gateway.dto.ErrorInfo;
import mfs.mw.app.gateway.dto.Party;
import mfs.mw.app.gateway.dto.StatusMessage;
import mfs.mw.app.gateway.dto.TxnAuthorisation;
import mfs.mw.app.gateway.dto.TxnAuthorisationRequest;
import mfs.mw.app.gateway.dto.TxnRequest;
import mfs.mw.app.gateway.exception.MwException;
import mfs.mw.app.gateway.mapper.RequestMapper;
import mfs.mw.app.gateway.messaging.EventMetadata;
import mfs.mw.app.gateway.messaging.EventPublisher;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

/** {@code BalanceService} (ADR D26): the BALANCE request, mw-auth's assertion and the answers. */
class BalanceServiceTest {

  static final AppUser CUSTOMER = new AppUser("01711000001", "CUSTOMER_APP", "CUSTOMER", "device-0001");
  static final AppUser AGENT = new AppUser("01811000001", "AGENT_APP", "AGENT", "device-0002");
  static final AppUser MERCHANT = new AppUser("01911000001", "MERCHANT_APP", "MERCHANT", "device-0003");
  static final String ASSERTION = "eyJhbGciOiJFUzI1NiJ9.eyJqdGkiOiJiIn0.c2ln";
  static final EncryptedPin PIN = new EncryptedPin("PIN_TRANSPORT.v1", "b3BhcXVl");
  static final Instant AS_OF = Instant.parse("2026-11-16T08:15:31Z");

  final EventPublisher publisher = mock(EventPublisher.class);
  final ReplyWaiters waiters = new ReplyWaiters();
  final AuthClient auth = mock(AuthClient.class);
  final BalanceService service =
      new BalanceService(
          publisher, waiters, new RequestMapper(),
          new AppGatewayProperties(
              Duration.ofMillis(200), "gw-unit", "mw.txn.request.v1", "mw.txn.status.v1",
              List.of("P2P"), List.of("reference"),
              new AppGatewayProperties.Jwt("http://mw-auth/jwks", "mw-auth", "mw-app"),
              Map.of("P2P", List.of("CUSTOMER"))),
          Clock.fixed(Instant.parse("2026-11-16T08:15:30Z"), ZoneOffset.UTC),
          auth);

  {
    when(auth.authorise(any())).thenReturn(new TxnAuthorisation(ASSERTION, AS_OF.plusSeconds(60), false));
  }

  /** The stub orchestrator: answers each published request with {@code answer(req)} at once. */
  void orchestratorAnswers(java.util.function.Function<TxnRequest, StatusMessage> answer) {
    doAnswer(inv -> {
          TxnRequest req = inv.getArgument(3);
          StatusMessage s = answer.apply(req);
          if (s != null) {
            waiters.offer(s);
          }
          return null;
        })
        .when(publisher).publish(anyString(), anyString(), any(), any(), anyMap());
  }

  static StatusMessage status(TxnRequest req, String state, ErrorInfo error, StatusMessage.Balance balance) {
    return new StatusMessage(
        1, req.txnId(), req.idempotencyKey(), req.correlationId(), req.channel(), req.txnType(), state, "VALIDATING",
        req.initiator(), req.debitParty(), req.creditParty(), req.amount(), null, "CORE-1", error, null, null,
        req.requestedAt(), AS_OF, balance);
  }

  @Test
  void everyAppRoleGetsItsOwnWalletsBalance() {
    orchestratorAnswers(req -> status(req, "COMPLETED", null,
        new StatusMessage.Balance(new Amount(1_200_000L, "BDT"), AS_OF)));

    for (AppUser user : List.of(CUSTOMER, AGENT, MERCHANT)) {
      BalanceView view = service.check(user, "bal-" + user.walletType(), new BalanceRequest(PIN, "bn"));
      assertThat(view.available()).isEqualTo(new Amount(1_200_000L, "BDT"));
      assertThat(view.asOf()).isEqualTo(AS_OF);
      assertThat(view.txnId()).isNotBlank();
    }
    assertThat(waiters.size()).isZero();
  }

  @Test
  void theRequestIsABalanceOfTheCallersWalletWithTheAssertionAndThePinGoesToMwAuthOnly() {
    orchestratorAnswers(req -> status(req, "COMPLETED", null,
        new StatusMessage.Balance(new Amount(5L, "BDT"), AS_OF)));

    BalanceView view = service.check(AGENT, "bal-1", new BalanceRequest(PIN, "en"));

    ArgumentCaptor<TxnRequest> published = ArgumentCaptor.forClass(TxnRequest.class);
    ArgumentCaptor<EventMetadata> meta = ArgumentCaptor.forClass(EventMetadata.class);
    verify(publisher).publish(eq("mw.txn.request.v1"), eq(view.txnId()), meta.capture(), published.capture(),
        eq(Map.of("mw-reply-to", "gw-unit")));
    TxnRequest req = published.getValue();
    assertThat(req.txnType()).isEqualTo("BALANCE");
    assertThat(req.channel()).isEqualTo("AGENT_APP");
    assertThat(req.amount()).isEqualTo(new Amount(0L, "BDT"));
    assertThat(req.initiator()).isEqualTo(new Party("MSISDN", "01811000001", null, null));
    assertThat(req.debitParty()).isEqualTo(Party.wallet("01811000001", "AGENT"));
    assertThat(req.creditParty()).isEqualTo(req.debitParty());
    assertThat(req.idempotencyKey()).isEqualTo("bal-1");
    assertThat(req.locale()).isEqualTo("en");
    assertThat(req.assertion()).isEqualTo(ASSERTION);
    assertThat(req.partner()).isNull();
    assertThat(meta.getValue().idempotencyKey()).isEqualTo("bal-1");

    ArgumentCaptor<TxnAuthorisationRequest> asked = ArgumentCaptor.forClass(TxnAuthorisationRequest.class);
    verify(auth).authorise(asked.capture());
    TxnAuthorisationRequest a = asked.getValue();
    assertThat(a.walletId()).isEqualTo("01811000001");
    assertThat(a.deviceId()).isEqualTo("device-0002");
    assertThat(a.encryptedPin()).isEqualTo(PIN);
    // the values mw-auth hashes are exactly the published ones
    assertThat(a.txn()).isEqualTo(new TxnAuthorisationRequest.Txn(
        "BALANCE", 0L, "BDT", new TxnAuthorisationRequest.TxnParty("WALLET", "01811000001"),
        new TxnAuthorisationRequest.TxnParty("WALLET", "01811000001"), "bal-1"));
  }

  @Test
  void mwAuthsRefusalPassesThroughAndNothingIsPublished() {
    when(auth.authorise(any()))
        .thenThrow(new MwException("MW-AUTH-040", "Wrong PIN").withStatus(HttpStatus.UNAUTHORIZED)
            .withDetails(List.of(new ErrorDetail("triesLeft", "2"))));

    assertThatThrownBy(() -> service.check(CUSTOMER, "bal-2", new BalanceRequest(PIN, null)))
        .isInstanceOfSatisfying(MwException.class, e -> {
          assertThat(e.code()).isEqualTo("MW-AUTH-040");
          assertThat(e.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
        });
    verifyNoInteractions(publisher);
    assertThat(waiters.size()).isZero();
  }

  @Test
  void aCoreRefusalIsItsCodeWithTheTxnId() {
    orchestratorAnswers(req -> status(req, "REJECTED",
        new ErrorInfo("MW-CORE-WALLET_NOT_ACTIVE", "Wallet is not active", "ওয়ালেট সক্রিয় নয়", false), null));

    assertThatThrownBy(() -> service.check(CUSTOMER, "bal-3", new BalanceRequest(PIN, null)))
        .isInstanceOfSatisfying(MwException.class, e -> {
          assertThat(e.code()).isEqualTo("MW-CORE-WALLET_NOT_ACTIVE");
          assertThat(e.txnId()).isNotBlank();
          assertThat(e.messageBn()).isEqualTo("ওয়ালেট সক্রিয় নয়");
        });
  }

  @Test
  void noAnswerWithinTheBudgetIsMwTmo001RetryableNot202() {
    orchestratorAnswers(req -> null);

    assertThatThrownBy(() -> service.check(CUSTOMER, "bal-4", new BalanceRequest(PIN, null)))
        .isInstanceOfSatisfying(MwException.class, e -> {
          assertThat(e.code()).isEqualTo("MW-TMO-001");
          assertThat(e.retryable()).isTrue();
          assertThat(e.txnId()).isNotBlank();
        });
    assertThat(waiters.size()).isZero();
  }

  @Test
  void aDuplicateAnswerStillRunningOrACompletedStatusWithoutABalanceIsMwTmo001() {
    orchestratorAnswers(req -> {
      StatusMessage s = status(req, "VALIDATING", null, null);
      return new StatusMessage(
          1, "019a0000-0000-7000-8000-00000000abcd", s.idempotencyKey(), s.correlationId(), s.channel(),
          s.txnType(), s.status(), null, s.initiator(), s.debitParty(), s.creditParty(), s.amount(), null, null,
          null, null, Map.of("duplicate", "true", "requestTxnId", req.txnId()), s.requestedAt(), s.updatedAt(), null);
    });
    assertThatThrownBy(() -> service.check(CUSTOMER, "bal-5", new BalanceRequest(PIN, null)))
        .isInstanceOfSatisfying(MwException.class, e -> assertThat(e.code()).isEqualTo("MW-TMO-001"));

    orchestratorAnswers(req -> status(req, "COMPLETED", null, null));
    assertThatThrownBy(() -> service.check(CUSTOMER, "bal-6", new BalanceRequest(PIN, null)))
        .isInstanceOfSatisfying(MwException.class, e -> assertThat(e.code()).isEqualTo("MW-TMO-001"));
  }

  @Test
  void kafkaRefusingIsMwSys010Retryable() {
    doThrow(new IllegalStateException("broker down"))
        .when(publisher).publish(anyString(), anyString(), any(), any(), anyMap());

    assertThatThrownBy(() -> service.check(CUSTOMER, "bal-7", new BalanceRequest(PIN, null)))
        .isInstanceOfSatisfying(MwException.class, e -> {
          assertThat(e.code()).isEqualTo("MW-SYS-010");
          assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        });
    assertThat(waiters.size()).isZero();
  }
}
