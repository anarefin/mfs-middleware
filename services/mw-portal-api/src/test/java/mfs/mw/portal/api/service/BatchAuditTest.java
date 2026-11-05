package mfs.mw.portal.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import mfs.mw.portal.api.entity.Batch;
import mfs.mw.portal.api.entity.BatchStatus;
import mfs.mw.portal.api.messaging.OutboxWriter;
import mfs.mw.portal.api.util.BulkIds;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class BatchAuditTest {

  private static final Instant T0 = Instant.parse("2026-11-05T00:00:00Z");

  private final OutboxWriter outbox = mock(OutboxWriter.class);
  private final BatchAudit audit = new BatchAudit(outbox, Clock.fixed(T0, ZoneOffset.UTC));

  /**
   * Every event of one batch carries the same key, the batch's parent txnId, whoever the actor is:
   * the maker's upload and the checker's decision then share a partition, so mw-audit-dw chains a
   * batch's trail in the order it happened.
   */
  @Test
  void everyEventOfABatchIsKeyedByItsParentTxnIdWhateverTheActor() {
    UUID id = UUID.randomUUID();
    Batch pending =
        new Batch(id, "ORG001", BatchStatus.PENDING_APPROVAL, 1, 100L, "BDT", "ab", "maker-sub", "org-maker01",
            null, null, null, T0, null);

    audit.record(pending, "bulk.batch.uploaded", "org-maker01", null, BatchStatus.PENDING_APPROVAL, null);
    audit.record(pending, "bulk.batch.approved", "org-checker01", BatchStatus.PENDING_APPROVAL, BatchStatus.APPROVED, null);
    audit.record(pending, "bulk.batch.expired", null, BatchStatus.PENDING_APPROVAL, BatchStatus.EXPIRED, null);

    ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
    verify(outbox, times(3)).append(eq(BatchAudit.TOPIC), keys.capture(), any(), any());
    assertThat(keys.getAllValues()).containsOnly(BulkIds.parentTxnId(id).toString());
  }
}
