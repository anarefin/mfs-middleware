package mfs.mw.app.gateway.client;

/**
 * The core answered a read with a definite refusal (4xx). Not retried: the status and the
 * envelope's {@code code} (null when the body had none) are the answer.
 */
public class CoreRefusedException extends RuntimeException {

  private final int status;
  private final String code;

  public CoreRefusedException(int status, String code) {
    super("core answered " + status + (code == null ? "" : " " + code), null, false, false);
    this.status = status;
    this.code = code;
  }

  public int status() {
    return status;
  }

  public String code() {
    return code;
  }
}
