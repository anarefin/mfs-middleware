package mfs.mw.data.export.service;

/** DW topics this service publishes, and the source topics it reads (ticket 128). */
public final class DwTopics {

  public static final String TXN_CREATED = "dw.txn.created.v1";
  public static final String TXN_STATE = "dw.txn.state-changed.v1";
  public static final String TXN_COMPLETED = "dw.txn.completed.v1";
  public static final String NOTIFICATION = "dw.notification.status.v1";
  public static final String RECON = "dw.recon.exception.v1";
  public static final String KYC = "dw.kyc.completed.v1";
  public static final String OPERATOR = "dw.audit.operator-action.v1";

  public static final String SRC_TXN = "mw.txn.status.v1";
  public static final String SRC_NOTIFY = "mw.notify.status.v1";
  public static final String SRC_AUDIT = "mw.audit.event.v1";
  public static final String SRC_RECON = "mw.recon.exception.v1";
  public static final String SRC_KYC = "mw.kyc.completed.v1";

  public static final String FILE_TRANSACTIONS = "file.transactions";
  public static final String FILE_WALLETS = "file.wallet_snapshot";
  public static final String FILE_NOTIFICATIONS = "file.notifications";
  public static final String FILE_RECON = "file.recon";

  public static final String AUDIT_EVENT = "mw.audit.event.v1";

  private DwTopics() {}
}
