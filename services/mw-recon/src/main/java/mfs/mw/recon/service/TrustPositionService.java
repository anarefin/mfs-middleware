package mfs.mw.recon.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import mfs.mw.recon.config.ReconProperties;
import mfs.mw.recon.dto.ReconClass;
import mfs.mw.recon.dto.TrustPosition;
import mfs.mw.recon.exception.ErrorCodes;
import mfs.mw.recon.exception.MwException;
import mfs.mw.recon.repository.ReconStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The daily trust account position (ticket 127, FRS REC-07): the e-money total (all CUSTOMER, AGENT, MERCHANT,
 * DISTRIBUTOR and ORGANISATION wallets, read from the core) against the MT940 closing balance of that business
 * date. A difference raises an {@code AMOUNT_MISMATCH} exception with detail {@code TRUST_POSITION}. Nothing here
 * moves money.
 */
@Service
public class TrustPositionService {

  static final String DETAIL = "TRUST_POSITION";
  private static final String OWNER = "finance";

  private final ReconStore store;
  private final String trustPartnerId;
  private final Clock clock;
  private final ExceptionEvents events;

  @Autowired
  public TrustPositionService(ReconStore store, ReconProperties props, Clock clock, ExceptionEvents events) {
    this(store, props.trust().partnerId(), clock, events);
  }

  TrustPositionService(ReconStore store, String trustPartnerId, Clock clock, ExceptionEvents events) {
    this.store = store;
    this.events = events;
    this.trustPartnerId = trustPartnerId;
    this.clock = clock;
  }

  /** Whether the trust bank's MT940 closing balance of {@code businessDate} is in (ticket 127c). */
  @Transactional(readOnly = true)
  public boolean closingKnown(LocalDate businessDate) {
    return store.trustClosing(businessDate, ReconService.MT940) != null;
  }

  /** The latest business date with an MT940 closing balance, or null (ticket 127c, the test API's default). */
  @Transactional(readOnly = true)
  public LocalDate latestClosingDate() {
    return store.latestTrustClosingDate(ReconService.MT940);
  }

  /** The latest day with a stored position, or null. */
  @Transactional(readOnly = true)
  public LocalDate lastPositionDate() {
    return store.lastTrustPositionDate();
  }

  /** Whether a position is stored for {@code day}. */
  @Transactional(readOnly = true)
  public boolean positionKnown(LocalDate day) {
    return store.trustPositionKnown(day);
  }

  /**
   * Computes and stores the position; a second run for the same day replaces the row and never duplicates the day's
   * exception (M9 review): a gap again updates the day's OPEN or EXPLAINED exception, a clean re-run resolves it as
   * superseded. Each change goes out on {@code mw.recon.exception.v1}.
   */
  @Transactional
  public TrustPosition compute(LocalDate businessDate, long emoneyTotalMinor) {
    Long trust = store.trustClosing(businessDate, ReconService.MT940);
    if (trust == null) {
      throw new MwException(ErrorCodes.VAL_NOT_FOUND, "no MT940 closing balance for " + businessDate);
    }
    long difference = trust - emoneyTotalMinor;
    Instant now = clock.instant();
    UUID open = store.openTrustException(businessDate);
    UUID exceptionId = null;
    if (difference != 0 && open != null) {
      exceptionId = open;
      store.updateStandaloneAmounts(open, trust, emoneyTotalMinor);
      events.changed(open, null, now);
    } else if (difference != 0) {
      exceptionId = UUID.randomUUID();
      store.insertStandaloneException(exceptionId, ReconClass.AMOUNT_MISMATCH.name(), DETAIL, OWNER, trustPartnerId,
          trust, emoneyTotalMinor, now);
      events.changed(exceptionId, null, now);
    } else if (open != null && store.supersede(open, now) == 1) {
      events.changed(open, null, now);
    }
    store.saveTrustPosition(businessDate, emoneyTotalMinor, trust, difference, exceptionId, now);
    return new TrustPosition(businessDate, emoneyTotalMinor, trust, difference, exceptionId);
  }
}
