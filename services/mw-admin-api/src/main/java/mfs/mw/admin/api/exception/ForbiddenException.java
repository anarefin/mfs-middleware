package mfs.mw.admin.api.exception;

import org.springframework.http.HttpStatus;

/** The operator lacks the role an operation needs: 403 {@code MW-AUTH-002}. */
public class ForbiddenException extends MwException {

  private static final long serialVersionUID = 1L;

  public ForbiddenException(String message) {
    super(ErrorCodes.AUTH_FORBIDDEN, message, HttpStatus.FORBIDDEN);
  }
}
