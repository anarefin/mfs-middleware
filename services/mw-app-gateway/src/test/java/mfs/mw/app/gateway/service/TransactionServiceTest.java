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

  final EventPublisher publisher = mock(EventPublisher.class);
  final AppTransactionRepository repo = mock(AppTransactionRepository.class);
  final ReplyWaiters waiters = new ReplyWaiters();
  final TransactionService service =
      new TransactionService(
          publisher, waiters, new RequestMapper(), repo,
          new AppGatewayProperties(
              Duration.ofMillis(50), "gw-unit", "mw.txn.request.v1", "mw.txn.status.v1",
              List.of("P2P"), new AppGatewayProperties.Jwt("http://kc/certs", "/realms/customers")),
          Clock.fixed(Instant.parse("2026-11-04T06:00:00Z"), ZoneOffset.UTC));

  static TransactionRequest body(String txnType) {
    return new TransactionRequest(
        txnType, new TransactionRequest.CounterParty(null, "01711000002"), new Amount(100L, "BDT"),
        new EncryptedPin("k", "c"), null, null);
  }

  @Test
  void unsupportedTxnTypeIsMwVal010AndNothingIsPublished() {
    assertThatThrownBy(() -> service.submit(USER, "key", body("CASH_OUT")))
        .isInstanceOfSatisfying(MwException.class, e -> assertThat(e.code()).isEqualTo("MW-VAL-010"));
    verifyNoInteractions(publisher, repo);
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
                      1, txnId, "key", "c", "CUSTOMER_APP", "P2P", "FAILED", "VALIDATING", null,
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
