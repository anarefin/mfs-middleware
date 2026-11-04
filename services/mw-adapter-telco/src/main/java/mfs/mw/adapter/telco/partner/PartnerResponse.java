package mfs.mw.adapter.telco.partner;

/**
 * Every way one partner call can end, as the framework reports it to the service. Only {@link
 * Success}, {@link Rejected}, {@link NotFound} and {@link Pending} are definite answers from the
 * partner; {@link Ambiguous} and {@link Unknown} mean the request may have been applied; {@link
 * NotSent} means it certainly was not.
 */
public sealed interface PartnerResponse {

  /** Short outcome name for metrics and logs. */
  String status();

  /** Applied; {@code partnerRef} is the partner's own reference. */
  record Success(String partnerRef, String code) implements PartnerResponse {
    @Override
    public String status() {
      return "SUCCESS";
    }
  }

  /**
   * Refused for a business reason, nothing applied. {@code errorSuffix} and {@code retryable} come
   * from the partner's error-code map ({@code UNMAPPED}, not retryable, for an unlisted code).
   */
  record Rejected(String code, String message, String errorSuffix, boolean retryable)
      implements PartnerResponse {
    @Override
    public String status() {
      return "FAILED";
    }
  }

  /** The partner answered with a code that does not say whether the request was applied. */
  record Ambiguous(String code, String message) implements PartnerResponse {
    @Override
    public String status() {
      return "AMBIGUOUS";
    }
  }

  /** Status inquiry only: the partner has no record of the reference. */
  record NotFound(String code, String message) implements PartnerResponse {
    @Override
    public String status() {
      return "NOT_FOUND";
    }
  }

  /** Async-callback template only: accepted, the outcome follows by callback. */
  record Pending(String partnerRef, String code) implements PartnerResponse {
    @Override
    public String status() {
      return "PENDING";
    }
  }

  /** Never left: connect refused, open circuit, full bulkhead, or partner disabled. */
  record NotSent(NotSentReason reason) implements PartnerResponse {
    @Override
    public String status() {
      return "NOT_SENT";
    }
  }

  /** May have been applied (timeout, reset after send, 5xx, unreadable or unexpected reply). */
  record Unknown(UnknownReason reason) implements PartnerResponse {
    @Override
    public String status() {
      return "UNKNOWN";
    }
  }

  enum NotSentReason {
    CONNECT_FAILED,
    CIRCUIT_OPEN,
    BULKHEAD_FULL,
    /** Partner disabled, or no partner config and no default (nothing to send to). */
    DISABLED,
    /** The config cannot serve this call: operation not configured, mapping or credentials failed. */
    MISCONFIGURED
  }

  enum UnknownReason {
    TIMEOUT,
    CONNECTION_RESET,
    HTTP_5XX,
    MALFORMED_REPLY,
    /** Any status the operation does not list as definite (408, 409, 429, other 4xx, 1xx, 3xx). */
    UNEXPECTED_STATUS
  }
}
