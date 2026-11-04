package mfs.mw.audit.dw.exception;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;

/**
 * Error code namespaces (spec 5.3) and their HTTP status and default retryable flag. A code is
 * {@code MW-<NAMESPACE>-<suffix>}; partner codes are {@code MW-PTR-<partner>-<code>}.
 */
public enum ErrorCategory {
  VAL(HttpStatus.BAD_REQUEST, false),
  AUTH(HttpStatus.UNAUTHORIZED, false),
  IDEM(HttpStatus.CONFLICT, false),
  CORE(HttpStatus.UNPROCESSABLE_CONTENT, false),
  PTR(HttpStatus.BAD_GATEWAY, false),
  TMO(HttpStatus.GATEWAY_TIMEOUT, true),
  SYS(HttpStatus.INTERNAL_SERVER_ERROR, true);

  private static final Pattern CODE = Pattern.compile("MW-([A-Z]+)-[A-Z0-9][A-Z0-9_-]*");

  private final HttpStatus status;
  private final boolean retryable;

  ErrorCategory(HttpStatus status, boolean retryable) {
    this.status = status;
    this.retryable = retryable;
  }

  public HttpStatus status() {
    return status;
  }

  public boolean retryable() {
    return retryable;
  }

  /** The namespace of a code; rejects codes outside the 5.3 namespaces. */
  public static ErrorCategory of(String code) {
    Matcher m = CODE.matcher(code == null ? "" : code);
    if (m.matches()) {
      for (ErrorCategory c : values()) {
        if (c.name().equals(m.group(1))) {
          return c;
        }
      }
    }
    throw new IllegalArgumentException("not an MW error code: " + code);
  }
}
