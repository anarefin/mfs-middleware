package mfs.mw.adapter.billpay.service;

import java.time.Clock;
import java.util.Locale;
import mfs.mw.adapter.billpay.repository.PartnerReferenceRepository;
import org.springframework.stereotype.Service;

/**
 * The partner reference sent to the aggregator as {@code transactionId} (spec 7.3 rule 5): {@code UPAY}
 * plus the txnId's 32 hex digits, upper case. Derived from the txnId and stored on first use, so
 * a repeated request for the same txnId always sends the same reference.
 */
@Service
public class PartnerReferenceService {

  private final PartnerReferenceRepository repository;
  private final Clock clock;

  public PartnerReferenceService(PartnerReferenceRepository repository, Clock clock) {
    this.repository = repository;
    this.clock = clock;
  }

  public String referenceFor(String txnId, String partnerId) {
    String derived = "UPAY" + txnId.replace("-", "").toUpperCase(Locale.ROOT);
    return repository.findOrCreate(txnId, partnerId, derived, clock.instant());
  }
}
