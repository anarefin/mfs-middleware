package mfs.mw.simulators.exception;

import java.util.List;
import mfs.mw.simulators.dto.ErrorDetail;
import org.springframework.http.HttpStatus;

/**
 * Throw this (or a subclass) for every expected failure; the global handler turns it into the
 * error envelope (spec 4.4 rule 4, 5.3). The code namespace decides the HTTP status and the
 * retryable flag unless overridden.
 */
public class MwException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String code;
  private final transient ErrorCategory category;
  private final HttpStatus status;
  private final boolean retryable;
  private final String messageBn;
  private final String txnId;
  private final transient List<ErrorDetail> details;

  public MwException(String code, String message) {
    this(code, message, null, null, null, null, List.of(), null);
  }

  public MwException(String code, String message, Throwable cause) {
    this(code, message, null, null, null, null, List.of(), cause);
  }

  private MwException(
      String code,
      String message,
      HttpStatus status,
      Boolean retryable,
      String messageBn,
      String txnId,
      List<ErrorDetail> details,
      Throwable cause) {
    super(message, cause);
    this.code = code;
    this.category = ErrorCategory.of(code);
    this.status = status != null ? status : category.status();
    this.retryable = retryable != null ? retryable : category.retryable();
    this.messageBn = messageBn;
    this.txnId = txnId;
    this.details = List.copyOf(details);
  }

  public MwException withTxnId(String txnId) {
    return copy(status, retryable, messageBn, txnId, details);
  }

  public MwException withMessageBn(String messageBn) {
    return copy(status, retryable, messageBn, txnId, details);
  }

  public MwException withDetails(List<ErrorDetail> details) {
    return copy(status, retryable, messageBn, txnId, details);
  }

  /** For codes whose status differs from the namespace default, e.g. MW-AUTH forbidden = 403. */
  public MwException withStatus(HttpStatus status) {
    return copy(status, retryable, messageBn, txnId, details);
  }

  public MwException withRetryable(boolean retryable) {
    return copy(status, retryable, messageBn, txnId, details);
  }

  private MwException copy(
      HttpStatus s, boolean r, String bn, String txn, List<ErrorDetail> d) {
    MwException e = new MwException(code, getMessage(), s, r, bn, txn, d, getCause());
    e.setStackTrace(getStackTrace());
    return e;
  }

  public String code() {
    return code;
  }

  public ErrorCategory category() {
    return category;
  }

  public HttpStatus status() {
    return status;
  }

  public boolean retryable() {
    return retryable;
  }

  public String messageBn() {
    return messageBn;
  }

  public String txnId() {
    return txnId;
  }

  public List<ErrorDetail> details() {
    return details;
  }
}
