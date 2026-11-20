package mfs.mw.data.export.exception;

import jakarta.validation.ValidationException;

/**
 * A field left a service boundary with no PII tag (ticket 128). Deny by default: the record goes
 * to the DLQ, because retrying an untagged field fails the same way.
 */
public final class UntaggedFieldException extends ValidationException {

  private static final long serialVersionUID = 1L;

  public UntaggedFieldException(String scope, String field) {
    super("untagged field " + field + " in " + scope);
  }
}
