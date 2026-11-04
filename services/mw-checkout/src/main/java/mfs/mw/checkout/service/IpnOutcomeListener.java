package mfs.mw.checkout.service;

import mfs.mw.checkout.dto.SessionStatus;
import mfs.mw.checkout.entity.CheckoutSession;
import mfs.mw.checkout.mapper.IpnMapper;
import mfs.mw.checkout.repository.IpnDeliveryRepository;
import org.springframework.stereotype.Component;

/**
 * Queues the merchant's IPN when a payment's outcome lands (spec 9.3 step 3, ticket 32): attempt 1
 * of {@code checkout.ipn_delivery}, due at once, in the transaction that finished the session, so
 * an outcome never commits without its IPN. Nothing is sent from here: {@link IpnDeliveryService}
 * posts it from the ShedLock-guarded job.
 */
@Component
public class IpnOutcomeListener implements SessionOutcomeListener {

  private final IpnDeliveryRepository deliveries;
  private final IpnMapper mapper;
  private final IpnRetrySchedule schedule;

  public IpnOutcomeListener(IpnDeliveryRepository deliveries, IpnMapper mapper, IpnRetrySchedule schedule) {
    this.deliveries = deliveries;
    this.mapper = mapper;
    this.schedule = schedule;
  }

  @Override
  public void onOutcome(CheckoutSession session) {
    if (session.status() != SessionStatus.PAID && session.status() != SessionStatus.FAILED) {
      return;
    }
    deliveries.insert(mapper.firstAttempt(session, schedule.firstAttemptAt()));
  }
}
