package mfs.mw.recon.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import mfs.mw.recon.dto.ExceptionView;
import mfs.mw.recon.exception.ErrorCodes;
import mfs.mw.recon.exception.MwException;
import mfs.mw.recon.repository.ReconStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The exception work queue the console workbench reads (ticket 127, UC-22). Reading and explaining only: an
 * action (force complete, reverse, raise dispute) is a maker-checker change request in mw-admin-api.
 */
@Service
public class ExceptionQueueService {

  static final int MAX_LIMIT = 500;

  private final ReconStore store;
  private final Clock clock;

  public ExceptionQueueService(ReconStore store, Clock clock) {
    this.store = store;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public List<ExceptionView> list(String reconClass, String partnerId, String state, Integer minAgeHours, int limit) {
    Instant now = clock.instant();
    Instant olderThan = minAgeHours == null ? null : now.minus(Duration.ofHours(minAgeHours));
    int bounded = Math.clamp(limit, 1, MAX_LIMIT);
    return store.exceptionQueue(blankToNull(reconClass), blankToNull(partnerId), blankToNull(state), olderThan, bounded)
        .stream().map(row -> view(row, now)).toList();
  }

  @Transactional(readOnly = true)
  public ExceptionView get(UUID id) {
    return view(require(id), clock.instant());
  }

  /** Records the explanation; an OPEN exception becomes EXPLAINED, a later state keeps its state. */
  @Transactional
  public ExceptionView explain(UUID id, String explanation, String operatorId) {
    require(id);
    store.explain(id, explanation.strip(), operatorId, clock.instant());
    return get(id);
  }

  private ReconStore.ExceptionRow require(UUID id) {
    ReconStore.ExceptionRow row = store.exception(id);
    if (row == null) {
      throw new MwException(ErrorCodes.VAL_NOT_FOUND, "no such recon exception");
    }
    return row;
  }

  /**
   * Advice for the workbench. A missing core posting suggests FORCE_COMPLETE, a completed transaction the partner
   * does not carry suggests REVERSE, money the partner reports without a Upay transaction or a wrong amount suggests
   * RAISE_DISPUTE, and a trust position gap is investigated and then accepted as explained.
   */
  static String suggestedAction(String reconClass, String detail) {
    if (TrustPositionService.DETAIL.equals(detail)) {
      return "ACCEPT_AS_EXPLAINED";
    }
    return switch (reconClass) {
      case "STATUS_MISMATCH" -> "FORCE_COMPLETE";
      case "MISSING_IN_PARTNER" -> "REVERSE";
      case "MISSING_IN_UPAY", "AMOUNT_MISMATCH" -> "RAISE_DISPUTE";
      default -> "ACCEPT_AS_EXPLAINED";
    };
  }

  private static ExceptionView view(ReconStore.ExceptionRow row, Instant now) {
    long age = Math.max(0, Duration.between(row.createdAt(), now).toHours());
    return new ExceptionView(row.id().toString(), row.reconClass(), row.detail(), row.owner(), row.partnerId(),
        row.txnId() == null ? null : row.txnId().toString(), row.partnerRef(), row.reference(), row.fileAmountMinor(),
        row.upayAmountMinor(), row.ledgerAmountMinor(), row.state(), row.createdAt(), age,
        suggestedAction(row.reconClass(), row.detail()), row.explanation(), row.explainedBy(), row.explainedAt());
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
