package mfs.mw.portal.api.mapper;

import mfs.mw.portal.api.dto.Amount;
import mfs.mw.portal.api.dto.BatchProgress;
import mfs.mw.portal.api.dto.BatchView;
import mfs.mw.portal.api.entity.Batch;

/** {@link Batch} -> {@link BatchView}. */
public final class BatchMapper {

  private BatchMapper() {}

  public static BatchView view(Batch b) {
    return view(b, null);
  }

  /** @param progress the row counts of an approved batch, or null */
  public static BatchView view(Batch b, BatchProgress progress) {
    return new BatchView(
        b.batchId().toString(),
        b.orgId(),
        b.status().name(),
        b.rowCount(),
        new Amount(b.totalMinor(), b.currency()),
        b.makerName(),
        b.checkerName(),
        b.reason(),
        b.createdAt(),
        b.decidedAt(),
        b.parentTxnId() == null ? null : b.parentTxnId().toString(),
        progress,
        b.completedAt());
  }
}
