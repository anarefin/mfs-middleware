package mfs.mw.portal.api.entity;

/** Lifecycle of a bulk batch so far (ticket 33); execution states come with ticket 34. */
public enum BatchStatus {
  PENDING_APPROVAL,
  APPROVED,
  REJECTED
}
