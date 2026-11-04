package mfs.mw.notify.exception;

/** A template cannot be found or rendered (spec 10.3); the message is recorded as FAILED. */
public class TemplateException extends MwException {

  private static final long serialVersionUID = 1L;

  public TemplateException(String message) {
    super(ErrorCodes.NOTIFY_TEMPLATE, message);
  }
}
