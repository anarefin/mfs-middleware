package mfs.mw.portal.api.mapper;

import java.util.List;
import mfs.mw.portal.api.dto.Amount;
import mfs.mw.portal.api.dto.BatchPreview;
import mfs.mw.portal.api.dto.BatchProgress;
import mfs.mw.portal.api.dto.BatchView;
import mfs.mw.portal.api.dto.InvalidRow;
import mfs.mw.portal.api.entity.Batch;
import mfs.mw.portal.api.entity.BatchItem;
import mfs.mw.portal.api.entity.ItemStatus;
import mfs.mw.portal.api.util.MaskingPolicy;
import mfs.mw.portal.api.util.SensitiveType;

/** {@link Batch} -> {@link BatchView}; a page of {@link BatchItem}s -> {@link BatchPreview}. */
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
        b.completedAt(),
        b.validCount(),
        b.invalidCount(),
        null,
        b.retryRound() == 0 ? null : b.retryRound(),
        b.retryRequestedBy());
  }

  /** The upload's answer: the view plus the given invalid rows' codes. */
  public static BatchView uploaded(Batch b, List<InvalidRow> invalid) {
    return view(b).withRowErrors(invalid.stream().map(r -> new BatchView.RowError(r.line(), r.errors())).toList());
  }

  public static BatchPreview preview(Batch b, int offset, int limit, List<BatchItem> page) {
    return new BatchPreview(
        b.batchId().toString(),
        b.status().name(),
        b.rowCount(),
        b.validCount(),
        b.invalidCount(),
        new Amount(b.totalMinor(), b.currency()),
        offset,
        limit,
        page.stream().map(i -> row(i, b.currency())).toList());
  }

  private static BatchPreview.Row row(BatchItem i, String currency) {
    boolean valid = i.status() != ItemStatus.INVALID;
    return new BatchPreview.Row(
        i.lineNo(),
        i.msisdn() == null ? null : MaskingPolicy.mask(SensitiveType.MSISDN, i.msisdn()),
        i.amountMinor() == null ? null : new Amount(i.amountMinor(), currency),
        i.reference(),
        valid,
        valid ? null : i.rowErrors());
  }
}
