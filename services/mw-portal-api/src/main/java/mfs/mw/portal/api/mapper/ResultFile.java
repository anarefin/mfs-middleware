package mfs.mw.portal.api.mapper;

import java.math.BigDecimal;
import java.util.List;
import mfs.mw.portal.api.entity.BatchItem;
import mfs.mw.portal.api.util.MaskingPolicy;
import mfs.mw.portal.api.util.SensitiveType;

/**
 * The result file of a bulk batch (spec 9.4, ticket 34): CSV, header {@code
 * line,msisdn,amount,reference,status,txnId,errorCode}, one line per row in file order. The MSISDN
 * is masked (18.3: the org matches rows by its own unique reference); the amount is in taka with
 * two decimals as uploaded; txnId and errorCode are empty while there is none.
 */
public final class ResultFile {

  public static final String HEADER = "line,msisdn,amount,reference,status,txnId,errorCode";

  private ResultFile() {}

  public static String of(List<BatchItem> items) {
    StringBuilder b = new StringBuilder(64 * (items.size() + 1)).append(HEADER).append('\n');
    for (BatchItem i : items) {
      b.append(i.lineNo())
          .append(',')
          .append(MaskingPolicy.mask(SensitiveType.MSISDN, i.msisdn()))
          .append(',')
          .append(BigDecimal.valueOf(i.amountMinor(), 2).toPlainString())
          .append(',')
          .append(i.reference())
          .append(',')
          .append(i.status().name())
          .append(',')
          .append(i.txnId() == null ? "" : i.txnId().toString())
          .append(',')
          .append(i.errorCode() == null ? "" : i.errorCode())
          .append('\n');
    }
    return b.toString();
  }
}
