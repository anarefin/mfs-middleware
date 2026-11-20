package mfs.mw.checkout.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import mfs.mw.checkout.dto.StatusMessage;
import mfs.mw.checkout.entity.Refund;
import mfs.mw.checkout.mapper.RefundMapper;
import mfs.mw.checkout.repository.IpnDeliveryRepository;
import mfs.mw.checkout.repository.RefundRepository;
import mfs.mw.checkout.repository.SessionRepository;
import org.junit.jupiter.api.Test;

/**
 * M9 review (FL-06) and its security review: a duplicate answer (a repeated idempotency key) settles a
 * refund only when it describes that very refund (stored txnId = the refundId that was sent); a
 * duplicate pointing at another refund settles nothing, and the projection path ignores duplicates.
 */
class RefundOutcomesTest {

  static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-20T10:00:00Z"), ZoneOffset.UTC);

  final RefundRepository refunds = mock(RefundRepository.class);
  final RefundOutcomes outcomes = new RefundOutcomes(
      refunds, mock(SessionRepository.class), mock(IpnDeliveryRepository.class), mock(RefundMapper.class),
      new IpnRetrySchedule(List.of(Duration.ofMinutes(1)), CLOCK), CLOCK);

  static StatusMessage duplicate(UUID storedTxnId, UUID requestTxnId) {
    return new StatusMessage(1, storedTxnId.toString(), "refund:k", "corr", "WEB_CHECKOUT", "REVERSAL", "COMPLETED",
        "REVERSING", null, null, null, null, null, null, null, "en",
        Map.of(StatusMessage.REQUEST_TXN_ID, requestTxnId.toString(), StatusMessage.DUPLICATE, "true"),
        null, Instant.parse("2026-11-20T10:00:00Z"));
  }

  @Test
  void aDuplicateAnswerPointingAtAnotherRefundSettlesNothing() {
    UUID sent = UUID.randomUUID();
    UUID other = UUID.randomUUID();
    when(refunds.findById(any())).thenReturn(Optional.empty());

    outcomes.applyAnswer(duplicate(other, sent), sent);

    verify(refunds, never()).finish(any(), any(), any(), any());
  }

  @Test
  void aDuplicateAnswerDescribingTheSentRefundSettlesIt() {
    UUID sent = UUID.randomUUID();
    when(refunds.finish(eq(sent), eq(Refund.Status.COMPLETED), any(), any())).thenReturn(false);

    outcomes.applyAnswer(duplicate(sent, sent), sent);

    verify(refunds).finish(eq(sent), eq(Refund.Status.COMPLETED), any(), any());
  }

  @Test
  void theProjectionIgnoresDuplicates() {
    UUID sent = UUID.randomUUID();

    outcomes.apply(duplicate(sent, sent));

    verify(refunds, never()).finish(any(), any(), any(), any());
  }
}
