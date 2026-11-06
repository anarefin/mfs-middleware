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

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import mfs.mw.orchestrator.dto.Amount;
import mfs.mw.orchestrator.dto.Party;
import mfs.mw.orchestrator.dto.TestReversalAccepted;
import mfs.mw.orchestrator.dto.TxnRequest;
import mfs.mw.orchestrator.entity.TransactionRecord;
import mfs.mw.orchestrator.exception.MwException;
import mfs.mw.orchestrator.messaging.EventMetadata;
import mfs.mw.orchestrator.messaging.EventPublisher;
import mfs.mw.orchestrator.repository.IdempotencyRepository;
import mfs.mw.orchestrator.repository.TransactionRepository;
import mfs.mw.orchestrator.service.saga.Status;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** The test-only reversal (ticket 58): a canonical REVERSAL of the original, published on mw.txn.request.v1. */
class TestApiServiceTest {

  private static final Instant NOW = Instant.parse("2026-11-06T10:00:00Z");
  private static final UUID ORIGINAL = UUID.fromString("0190a7c4-0000-7000-8000-000000000001");

  private final TransactionRepository transactions = mock(TransactionRepository.class);
  private final IdempotencyRepository idempotency = mock(IdempotencyRepository.class);
  private final EventPublisher publisher = mock(EventPublisher.class);
  private final TestApiService service =
      new TestApiService(transactions, idempotency, publisher, Clock.fixed(NOW, ZoneOffset.UTC));

  private static TransactionRecord p2p() {
    TxnRequest req =
        new TxnRequest(
            1, ORIGINAL.toString(), "app-key", "corr-1", "APP", "P2P", new Party("MSISDN", "01711000001", null, null),
            new Party("WALLET", "01711000001", null, null), new Party("WALLET", "01711000002", null, null),
            Amount.bdt(1000), null, null, null, null, null, null, NOW);
    return new TransactionRecord(
        ORIGINAL, NOW, NOW, req, Status.COMPLETED, Status.POSTING, null, 500L, "core-1", null, null, null, null, null);
  }

  @Test
  void publishesAReversalOfTheOriginalWithThePartiesSwapped() {
    when(transactions.find(ORIGINAL)).thenReturn(Optional.of(p2p()));

    TestReversalAccepted accepted = service.reverse(ORIGINAL, "chargeback");

    ArgumentCaptor<TxnRequest> sent = ArgumentCaptor.forClass(TxnRequest.class);
    ArgumentCaptor<EventMetadata> meta = ArgumentCaptor.forClass(EventMetadata.class);
    verify(publisher).publish(eq("mw.txn.request.v1"), eq(accepted.txnId()), meta.capture(), sent.capture());
    TxnRequest r = sent.getValue();
    assertThat(r.txnType()).isEqualTo("REVERSAL");
    assertThat(r.channel()).isEqualTo("SYSTEM");
    assertThat(r.schemaVersion()).isEqualTo(1);
    assertThat(r.txnId()).isEqualTo(accepted.txnId());
    assertThat(UUID.fromString(r.txnId()).version()).isEqualTo(7);
    assertThat(r.idempotencyKey()).isEqualTo(accepted.idempotencyKey()).startsWith("test-rev-");
    assertThat(r.initiator()).isEqualTo(new Party("ORG", "bits-ops-test", null, null));
    assertThat(r.debitParty().id()).isEqualTo("01711000002");
    assertThat(r.creditParty().id()).isEqualTo("01711000001");
    assertThat(r.amount()).isEqualTo(Amount.bdt(1000));
    assertThat(r.originalTxnId()).isEqualTo(ORIGINAL.toString());
    assertThat(r.requestedAt()).isEqualTo(NOW);
    assertThat(r.attributes()).isEqualTo(Map.of("reason", "chargeback"));
    assertThat(meta.getValue().txnId()).isEqualTo(r.txnId());
    assertThat(meta.getValue().idempotencyKey()).isEqualTo(r.idempotencyKey());
    assertThat(meta.getValue().correlationId()).isEqualTo(r.correlationId());
  }

  @Test
  void eachReversalHasItsOwnIdsAndADefaultReason() {
    when(transactions.find(ORIGINAL)).thenReturn(Optional.of(p2p()));

    TestReversalAccepted a = service.reverse(ORIGINAL, null);
    TestReversalAccepted b = service.reverse(ORIGINAL, " ");

    assertThat(a.txnId()).isNotEqualTo(b.txnId());
    assertThat(a.idempotencyKey()).isNotEqualTo(b.idempotencyKey());
    ArgumentCaptor<TxnRequest> sent = ArgumentCaptor.forClass(TxnRequest.class);
    verify(publisher, org.mockito.Mockito.times(2)).publish(anyString(), anyString(), any(), sent.capture());
    assertThat(sent.getAllValues()).allSatisfy(r -> assertThat(r.attributes()).containsEntry("reason", "test reversal"));
  }

  @Test
  void anUnknownOriginalIs404AndPublishesNothing() {
    when(transactions.find(ORIGINAL)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.reverse(ORIGINAL, null))
        .isInstanceOfSatisfying(MwException.class, e -> {
          assertThat(e.code()).isEqualTo("MW-VAL-003");
          assertThat(e.status().value()).isEqualTo(404);
        });
    verify(publisher, never()).publish(anyString(), anyString(), any(), any());
  }

  @Test
  void aKeyIsHandledOnceTheIdempotencyTableHasIt() {
    when(idempotency.exists("test-rev-1")).thenReturn(true);

    assertThat(service.handled("test-rev-1")).isTrue();
    assertThat(service.handled("test-rev-2")).isFalse();
  }
}
