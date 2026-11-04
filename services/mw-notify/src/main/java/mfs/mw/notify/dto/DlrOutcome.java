package mfs.mw.notify.dto;

/**
 * Final DLR states (spec 10.2) in the order a multi-part message takes the worst of: a message is
 * DELIVERED only when every part is; any UNDELIVERABLE part makes it UNDELIVERABLE, else any
 * EXPIRED part EXPIRED.
 */
public enum DlrOutcome {
  DELIVERED,
  EXPIRED,
  UNDELIVERABLE;

  public NotifyStatus status() {
    return NotifyStatus.valueOf(name());
  }
}
