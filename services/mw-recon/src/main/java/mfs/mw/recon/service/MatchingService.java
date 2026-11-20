package mfs.mw.recon.service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import mfs.mw.recon.dto.ExternalLine;
import mfs.mw.recon.dto.Finding;
import mfs.mw.recon.dto.MatchRule;
import mfs.mw.recon.dto.ReconClass;
import mfs.mw.recon.dto.TxnProjection;
import org.springframework.stereotype.Component;

/**
 * Three-way classification (ticket 126, FRS REC-03, REC-04). Pure: it reports a class and never
 * posts money or changes a transaction. The key is partner ref, then reference (or the txn id a
 * statement line carries). Amounts agree at the tolerance and disagree one poisha over it.
 */
@Component
public class MatchingService {

  /** Ledger detail on a {@link ReconClass#STATUS_MISMATCH} when Upay is COMPLETED and nothing posted. */
  public static final String LEDGER_MISSING = "LEDGER_MISSING";

  /**
   * Classifies {@code lines} against {@code txns}. {@code postedTxnIds} are txn ids with a core
   * posting. When {@code raiseMissingInPartner} is false (an intraday MT942) a COMPLETED txn that
   * is not on the file is left for the end-of-day statement.
   */
  public List<Finding> match(
      LocalDate businessDate,
      List<ExternalLine> lines,
      List<TxnProjection> txns,
      Set<String> postedTxnIds,
      MatchRule rule,
      boolean raiseMissingInPartner) {
    List<Finding> out = new ArrayList<>();
    Set<String> used = new HashSet<>();
    for (ExternalLine line : lines) {
      TxnProjection txn = find(line, txns, used, rule);
      if (txn != null) {
        used.add(txn.txnId());
      }
      out.add(classify(line, txn, txn != null && postedTxnIds.contains(txn.txnId()), rule));
    }
    if (raiseMissingInPartner) {
      List<TxnProjection> missing = new ArrayList<>();
      for (TxnProjection txn : txns) {
        if ("COMPLETED".equals(txn.state()) && !used.contains(txn.txnId())
            && inWindow(businessDate, txn.valueDate(), rule.dateWindowDays())) {
          missing.add(txn);
        }
      }
      missing.sort(Comparator.comparing(TxnProjection::txnId));
      for (TxnProjection txn : missing) {
        out.add(new Finding(ReconClass.MISSING_IN_PARTNER, null, "partner-ops", txn.txnId(),
            txn.partnerRef(), txn.reference(), null, txn.amountMinor(),
            postedTxnIds.contains(txn.txnId()) ? txn.amountMinor() : null));
      }
    }
    return List.copyOf(out);
  }

  private static Finding classify(ExternalLine line, TxnProjection txn, boolean posted, MatchRule rule) {
    if (txn == null) {
      return new Finding(ReconClass.MISSING_IN_UPAY, null, "ops", null, line.partnerRef(), line.reference(),
          line.amountMinor(), null, null);
    }
    boolean success = partnerSuccess(line.status());
    boolean completed = "COMPLETED".equals(txn.state());
    if (success != completed) {
      return of(ReconClass.STATUS_MISMATCH, null, txn, line, posted);
    }
    if (Math.abs(line.amountMinor() - txn.amountMinor()) > rule.toleranceMinor()) {
      return of(ReconClass.AMOUNT_MISMATCH, null, txn, line, posted);
    }
    if (completed && !posted) {
      return of(ReconClass.STATUS_MISMATCH, LEDGER_MISSING, txn, line, false);
    }
    return of(ReconClass.MATCHED, null, txn, line, posted);
  }

  private static Finding of(ReconClass classification, String detail, TxnProjection txn, ExternalLine line, boolean posted) {
    Long ledger = posted ? txn.amountMinor() : null;
    return new Finding(classification, detail, "ops", txn.txnId(),
        first(line.partnerRef(), txn.partnerRef()), first(line.reference(), txn.reference()),
        line.amountMinor(), txn.amountMinor(), ledger);
  }

  private static TxnProjection find(ExternalLine line, List<TxnProjection> txns, Set<String> used, MatchRule rule) {
    TxnProjection best = null;
    long bestDistance = Long.MAX_VALUE;
    for (TxnProjection txn : txns) {
      if (used.contains(txn.txnId()) || !keysMatch(line, txn)
          || !inWindow(line.valueDate(), txn.valueDate(), rule.dateWindowDays())) {
        continue;
      }
      long distance = distance(line.valueDate(), txn.valueDate());
      if (best == null || distance < bestDistance) {
        best = txn;
        bestDistance = distance;
      }
    }
    return best;
  }

  /** Partner ref first; a reference (or the txn id on a statement line) when that does not hit. */
  static boolean keysMatch(ExternalLine line, TxnProjection txn) {
    if (same(line.partnerRef(), txn.partnerRef())) {
      return true;
    }
    return same(line.reference(), txn.partnerRef()) || same(line.reference(), txn.reference())
        || same(line.reference(), txn.txnId()) || same(line.partnerRef(), txn.reference())
        || same(line.partnerRef(), txn.txnId());
  }

  /** A settlement or statement line with no status is a claim that the movement succeeded. */
  static boolean partnerSuccess(String status) {
    if (status == null || status.isBlank()) {
      return true;
    }
    return switch (status.trim().toUpperCase(Locale.ROOT)) {
      case "SUCCESS", "OK", "SETTLED", "COMPLETED", "POSTED" -> true;
      default -> false;
    };
  }

  static boolean inWindow(LocalDate a, LocalDate b, int days) {
    if (a == null || b == null) {
      return true;
    }
    return Math.abs(ChronoUnit.DAYS.between(a, b)) <= days;
  }

  private static long distance(LocalDate a, LocalDate b) {
    if (a == null || b == null) {
      return 0;
    }
    return Math.abs(ChronoUnit.DAYS.between(a, b));
  }

  private static boolean same(String left, String right) {
    return left != null && !left.isBlank() && left.equals(right);
  }

  private static String first(String preferred, String fallback) {
    return preferred != null && !preferred.isBlank() ? preferred : fallback;
  }
}
