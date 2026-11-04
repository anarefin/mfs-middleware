package mfs.mw.notify.dto;

/** Notification channels (spec 10.2). The MVP sends SMS only; email and push come later. */
public enum Channel {
  SMS,
  EMAIL,
  PUSH
}
