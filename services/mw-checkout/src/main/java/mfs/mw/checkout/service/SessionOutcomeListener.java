package mfs.mw.checkout.service;

import mfs.mw.checkout.entity.CheckoutSession;

/**
 * Seam for what follows a payment outcome (spec 9.3 step 3). {@link SessionOutcomes} calls every
 * listener bean inside the transaction that moved the session from PAYING to PAID or FAILED, with
 * the session as stored afterwards; so work a listener writes (the signed IPN to the merchant,
 * ticket 32: an {@code ipn_delivery} row or an outbox event) commits with the outcome or not at
 * all. Listeners must not call out over the network; they record work for a relay or a job.
 */
public interface SessionOutcomeListener {

  void onOutcome(CheckoutSession session);
}
