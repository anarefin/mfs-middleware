package mfs.mw.perf.mix;

/**
 * One flow of the scenario mix: an MVP transaction type on its channel. {@link #TOPUP_REFUSED} is
 * the run's intended business failure (the OCS simulator refuses MSISDNs ending in 999: 502
 * {@code MW-PTR-GP-TOPUP_REJECTED}); every other flow must succeed.
 */
public enum Flow {
  /** Customer app send money to another perf customer. */
  P2P,
  /** Agent app cash-in to a perf customer. */
  CASH_IN,
  /** Customer app cash-out to a perf agent. */
  CASH_OUT,
  /** Customer app payment to a seeded merchant wallet. */
  MERCHANT_PAY,
  /** Customer app airtime top-up of the customer's own number (OCS simulator, hold/commit). */
  TOPUP,
  /** Top-up the OCS refuses (number ending in 999): hold, refusal, release; intended failure. */
  TOPUP_REFUSED,
  /** Customer app bill fetch then payment (bill aggregator simulator). */
  BILL_PAY,
  /** Customer app add money from a linked bank account (c2w bank simulator). */
  ADD_MONEY_BANK,
  /** Feature-phone P2P over USSD, played by the USSD gateway simulator in HTTP mode (robi). */
  USSD_P2P;

  /** Whether this flow is meant to end in a business failure. */
  public boolean intendedFailure() {
    return this == TOPUP_REFUSED;
  }
}
