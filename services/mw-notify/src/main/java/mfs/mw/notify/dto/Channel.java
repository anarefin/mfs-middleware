package mfs.mw.notify.dto;

/** Notification channels (spec 10.2): SMS (SMPP), EMAIL (SMTP), PUSH (app-server push API, ticket 110). */
public enum Channel {
  SMS,
  EMAIL,
  PUSH
}
