package mfs.mw.audit.dw.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;
import mfs.mw.audit.dw.dto.AuditEvent;
import mfs.mw.audit.dw.dto.ReconExceptionEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

/** Ticket 127b: one {@code mw.recon.exception.v1} state change becomes one audit row. */
class ReconExceptionAuditTest {

  static final JsonMapper JSON = JsonMapper.builder().findAndAddModules().build();

  static final UUID EVENT = UUID.fromString("0193a1b2-3c4d-7e5f-8a9b-0c1d2e3f4a5b");
  static final UUID EXCEPTION = UUID.fromString("0193a1b2-3c4d-7e5f-8a9b-0c1d2e3f4a6c");
  static final UUID TXN = UUID.fromString("0193a1b2-3c4d-7e5f-8a9b-0c1d2e3f4a7d");
  static final UUID CR = UUID.fromString("0193a1b2-3c4d-7e5f-8a9b-0c1d2e3f4a8e");

  static ReconExceptionEvent actioned(String actor) {
    return new ReconExceptionEvent(1, EVENT, EXCEPTION, "ACTIONED", "STATUS_MISMATCH", "LEDGER_MISSING", "ops",
        "trust-bank", TXN, 150_000L, "ref-1", "FORCE_COMMIT", CR, actor, Instant.parse("2026-11-19T20:15:00Z"),
        Instant.parse("2026-11-20T09:30:00Z"), "corr-recon-1");
  }

  @Test
  void anActionedChangeByACheckerIsAUserAuditRowKeyedByTheEventId() {
    AuditEvent a = ReconExceptionAudit.toAudit(actioned("seed-ops-checker"), JSON);

    assertThat(a.auditId()).isEqualTo(EVENT);
    assertThat(a.actor().type()).isEqualTo("user");
    assertThat(a.actor().id()).isEqualTo("seed-ops-checker");
    assertThat(a.action()).isEqualTo("recon.exception.actioned");
    assertThat(a.resource()).isEqualTo("recon_exception/" + EXCEPTION);
    assertThat(a.outcome()).isEqualTo("SUCCESS");
    assertThat(a.at()).isEqualTo(Instant.parse("2026-11-20T09:30:00Z"));
    assertThat(a.correlationId()).isEqualTo("corr-recon-1");
    assertThat(a.txnId()).isEqualTo(TXN.toString());
    assertThat(a.service()).isEqualTo("mw-recon");
    assertThat(a.after().get("state").asString()).isEqualTo("ACTIONED");
    assertThat(a.after().get("exceptionClass").asString()).isEqualTo("STATUS_MISMATCH");
    assertThat(a.after().get("action").asString()).isEqualTo("FORCE_COMMIT");
    assertThat(a.after().get("changeRequestId").asString()).isEqualTo(CR.toString());
  }

  @Test
  void aSystemChangeWithoutTxnIsTheMwReconSystemActor() {
    ReconExceptionEvent opened = new ReconExceptionEvent(1, EVENT, EXCEPTION, "OPEN", "AMOUNT_MISMATCH",
        "TRUST_POSITION", "finance", "trust-bank", null, 2_500L, "ref", null, null, null,
        Instant.parse("2026-11-20T02:00:00Z"), Instant.parse("2026-11-20T02:00:00Z"), "recon-x");

    AuditEvent a = ReconExceptionAudit.toAudit(opened, JSON);

    assertThat(a.actor().type()).isEqualTo("system");
    assertThat(a.actor().id()).isEqualTo("mw-recon");
    assertThat(a.action()).isEqualTo("recon.exception.open");
    assertThat(a.txnId()).isNull();
    assertThat(a.after().has("action")).isFalse();
  }

  @Test
  void anEventWithoutIdOrStateIsRefusedSoItGoesToTheRetryTopics() {
    ReconExceptionEvent noId = new ReconExceptionEvent(1, null, EXCEPTION, "OPEN", "AMOUNT_MISMATCH", null, "ops",
        "p", null, 1L, "r", null, null, null, Instant.now(), Instant.now(), "c");
    ReconExceptionEvent noState = new ReconExceptionEvent(1, EVENT, EXCEPTION, null, "AMOUNT_MISMATCH", null, "ops",
        "p", null, 1L, "r", null, null, null, Instant.now(), Instant.now(), "c");

    assertThatThrownBy(() -> ReconExceptionAudit.toAudit(noId, JSON)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ReconExceptionAudit.toAudit(noState, JSON)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void recordStoresThroughTheAuditChain() {
    AuditService audit = mock(AuditService.class);
    when(audit.record(any())).thenReturn(true);

    assertThat(new ReconExceptionAudit(audit, JSON).record(actioned(null))).isTrue();

    ArgumentCaptor<AuditEvent> stored = ArgumentCaptor.forClass(AuditEvent.class);
    verify(audit).record(stored.capture());
    assertThat(stored.getValue().actor().type()).isEqualTo("system");
  }
}
