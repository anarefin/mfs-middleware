package mfs.mw.notify.dto;

/**
 * Delivery states on {@code mw.notify.status.v1} (spec 10.2, 10.4). {@code notify.message} also
 * uses RETRY (a transient error, next attempt scheduled), which goes out as FAILED with {@code
 * final=false}.
 */
public enum NotifyStatus {
  QUEUED,
  SENT,
  DELIVERED,
  UNDELIVERABLE,
  EXPIRED,
  BOUNCED,
  READ,
  FAILED
}
