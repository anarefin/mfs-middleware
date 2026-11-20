package mfs.mw.admin.api.exception;

/**
 * The APISIX Admin API could not be asked or refused a write (ticket 100a). The channel client
 * projection logs it and leaves the repair to {@code scheduler/ApisixReconcileJob}; the registry
 * row (the source of truth) is already committed.
 */
public class ApisixUnavailableException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public ApisixUnavailableException(String message) {
    super(message);
  }

  public ApisixUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
