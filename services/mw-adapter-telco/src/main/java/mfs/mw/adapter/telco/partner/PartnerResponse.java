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

  /**
   * Applied; {@code partnerRef} is the partner's own reference. {@code attributes} is what the
   * response mapping put under {@code attributes} (text values; billpay extension of the copied
   * framework, used by the bill fetch to return the amount due), empty when none.
   */
  record Success(String partnerRef, String code, java.util.Map<String, String> attributes)
      implements PartnerResponse {

    public Success {
      attributes = attributes == null ? java.util.Map.of() : java.util.Map.copyOf(attributes);
    }

    public Success(String partnerRef, String code) {
      this(partnerRef, code, null);
    }

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

  /**
   * Never left: connect refused, open circuit, full bulkhead, partner disabled, or maintenance
   * (ticket 131b). {@code notice} is the bn/en text of a maintenance refusal; null otherwise.
   */
  record NotSent(NotSentReason reason, Notice notice) implements PartnerResponse {

    public NotSent(NotSentReason reason) {
      this(reason, null);
    }

    @Override
    public String status() {
      return "NOT_SENT";
    }
  }

  /**
   * The bn/en text a maintenance refusal carries (ticket 131b, FRS PLT-043). Blank text becomes
   * the default a caller can show.
   */
  record Notice(String en, String bn) {

    public static final String DEFAULT_EN = "This service is under maintenance";

    public static final String DEFAULT_BN = "এই সেবা রক্ষণাবেক্ষণে আছে";

    /** {@code en} and {@code bn}, or the default text where one is missing. */
    public static Notice orDefault(String en, String bn) {
      return new Notice(
          en == null || en.isBlank() ? DEFAULT_EN : en, bn == null || bn.isBlank() ? DEFAULT_BN : bn);
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
    /** Partner disabled (nothing to send to). */
    DISABLED,
    /** Maintenance flag or an open maintenance window (ticket 97, ticket 131b): nothing was sent. */
    MAINTENANCE,
    /** The config cannot serve this call: operation not configured, mapping or credentials failed. */
    MISCONFIGURED,
    /** The partnerId is neither a configured partner nor a {@code partner-aliases} entry (D28). */
    UNKNOWN_PARTNER
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
