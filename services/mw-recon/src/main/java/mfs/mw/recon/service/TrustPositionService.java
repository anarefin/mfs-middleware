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

  /** Computes and stores the position; a second run for the same day replaces the row. */
  @Transactional
  public TrustPosition compute(LocalDate businessDate, long emoneyTotalMinor) {
    Long trust = store.trustClosing(businessDate, ReconService.MT940);
    if (trust == null) {
      throw new MwException(ErrorCodes.VAL_NOT_FOUND, "no MT940 closing balance for " + businessDate);
    }
    long difference = trust - emoneyTotalMinor;
    Instant now = clock.instant();
    UUID exceptionId = null;
    if (difference != 0) {
      exceptionId = UUID.randomUUID();
      store.insertStandaloneException(exceptionId, ReconClass.AMOUNT_MISMATCH.name(), DETAIL, OWNER, trustPartnerId,
          trust, emoneyTotalMinor, now);
      events.changed(exceptionId, null, now);
    }
    store.saveTrustPosition(businessDate, emoneyTotalMinor, trust, difference, exceptionId, now);
    return new TrustPosition(businessDate, emoneyTotalMinor, trust, difference, exceptionId);
  }
}
