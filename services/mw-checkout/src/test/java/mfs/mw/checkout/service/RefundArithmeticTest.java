package mfs.mw.checkout.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import mfs.mw.checkout.entity.Refund;
import org.junit.jupiter.api.Test;

/** Ticket 116, FL-06: the refunded total never passes the payment; the idempotency hash of a refund. */
class RefundArithmeticTest {

  private static final UUID SESSION = UUID.randomUUID();

  @Test
  void theRemainderCountsCompletedAndPendingRefundsButNotRejectedOnes() {
    assertThat(RefundService.remaining(10_000, List.of())).isEqualTo(10_000);
    assertThat(RefundService.remaining(10_000, List.of(
        refund(6_000, Refund.Status.COMPLETED),
        refund(3_000, Refund.Status.PENDING),
        refund(5_000, Refund.Status.REJECTED)))).isEqualTo(1_000);
    assertThat(RefundService.remaining(10_000, List.of(
        refund(6_000, Refund.Status.COMPLETED),
        refund(4_000, Refund.Status.COMPLETED)))).isZero();
  }

  @Test
  void theRequestHashTellsAmountsAndReasonsApart() {
    String h = RefundService.hash(SESSION, 4_000, "returned");
    assertThat(RefundService.hash(SESSION, 4_000, "returned")).isEqualTo(h);
    assertThat(RefundService.hash(SESSION, 4_001, "returned")).isNotEqualTo(h);
    assertThat(RefundService.hash(SESSION, 4_000, null)).isNotEqualTo(h);
    assertThat(RefundService.hash(UUID.randomUUID(), 4_000, "returned")).isNotEqualTo(h);
  }

  private static Refund refund(long amount, Refund.Status status) {
    Instant now = Instant.now();
    return new Refund(UUID.randomUUID(), SESSION, "m", "k", "h", amount, "BDT", UUID.randomUUID(), null, status,
        null, now, now);
  }
}
