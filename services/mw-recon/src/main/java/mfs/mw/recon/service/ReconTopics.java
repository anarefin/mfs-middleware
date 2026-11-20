package mfs.mw.recon.service;

/** Topics mw-recon consumes and the one result it publishes (ticket 126). */
public final class ReconTopics {

  public static final String PARTNER_RECON = "mw.partner.recon.v1";
  public static final String TXN_STATUS = "mw.txn.status.v1";
  public static final String WALLET_EVENTS = "core.wallet.event.v1";
  public static final String PAYMENT_EVENTS = "core.payment.event.v1";
  public static final String RESULT = "mw.recon.result.v1";
  public static final String AUDIT = "mw.audit.event.v1";

  private ReconTopics() {}
}
