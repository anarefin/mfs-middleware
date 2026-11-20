package mfs.mw.recon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import mfs.mw.recon.dto.ExceptionView;
import mfs.mw.recon.messaging.OutboxWriter;
import mfs.mw.recon.repository.ReconStore;
import org.junit.jupiter.api.Test;

/** The work-queue view (UC-22; M9 review): masked free text and an action name an endpoint accepts. */
class ExceptionQueueViewTest {

  private static final Instant AT = Instant.parse("2026-11-19T10:00:00Z");

  private final ReconStore store = mock(ReconStore.class);
  private final ExceptionQueueService queue = new ExceptionQueueService(store, mock(OutboxWriter.class),
      Clock.fixed(AT, ZoneOffset.UTC), mock(ExceptionEvents.class));

  @Test
  void theStatementNarrativeAndTheExplanationAreMasked() {
    UUID id = UUID.randomUUID();
    when(store.exception(id)).thenReturn(new ReconStore.ExceptionRow(id, "MISSING_IN_UPAY", null, "ops", "trustbank",
        null, null, "A/C 1234567890123 KARIM " + "y".repeat(400), 5_000L, null, null, "EXPLAINED", AT,
        "called 01711234567, account 9876543210987", "seed-recon-maker", AT, null, null, null));

    ExceptionView view = queue.get(id);

    assertThat(view.reference()).doesNotContain("1234567890123").hasSizeLessThanOrEqualTo(255);
    assertThat(view.explanation()).doesNotContain("01711234567").doesNotContain("9876543210987");
  }

  @Test
  void aMissingPostingSuggestsTheForceCommitTheActionEndpointAccepts() {
    assertThat(ExceptionQueueService.suggestedAction("STATUS_MISMATCH", "LEDGER_MISSING")).isEqualTo("FORCE_COMMIT");
  }
}
