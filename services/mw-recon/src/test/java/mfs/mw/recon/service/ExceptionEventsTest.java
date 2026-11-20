package mfs.mw.recon.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import mfs.mw.recon.repository.ReconStore;
import org.junit.jupiter.api.Test;

/** The body of {@code mw.recon.exception.v1} (FRS DAT-01; M9 review: the amount in question and masking). */
class ExceptionEventsTest {

  private static final Instant AT = Instant.parse("2026-11-19T10:00:00Z");

  private static ReconStore.ExceptionRow row(String detail, UUID txnId, String partnerRef, String reference,
      Long file, Long upay) {
    return new ReconStore.ExceptionRow(UUID.randomUUID(), "AMOUNT_MISMATCH", detail, "finance", "trustbank", txnId,
        partnerRef, reference, file, upay, null, "OPEN", AT, null, null, null, null, null, null);
  }

  @Test
  void aTrustPositionExceptionCarriesTheGapNotTheWholeBalance() {
    Map<String, Object> body = ExceptionEvents.body(
        row("TRUST_POSITION", null, null, null, 1_234_567_890_00L, 1_234_567_891_20L), "e", null, "c", AT);

    assertThat(body.get("amountMinor")).isEqualTo(120L);
  }

  @Test
  void aStatementNarrativeIsMaskedAndBoundedAndATxnIdIsPreferred() {
    String narrative = "A/C 1234567890123 KARIM 01711234567 " + "x".repeat(400);
    Map<String, Object> scrubbed = ExceptionEvents.body(row(null, null, null, narrative, 5_000L, null), "e", null,
        "c", AT);
    String ref = (String) scrubbed.get("statementRef");
    assertThat(ref).doesNotContain("1234567890123").doesNotContain("01711234567").hasSizeLessThanOrEqualTo(255);

    UUID txn = UUID.randomUUID();
    Map<String, Object> withTxn = ExceptionEvents.body(row(null, txn, null, narrative, 5_000L, null), "e", null, "c",
        AT);
    assertThat(withTxn.get("statementRef")).isEqualTo(txn.toString());
  }
}
