package mfs.mw.crypto.exception;

import org.springframework.http.HttpStatus;

/**
 * The service's own failures (spec 5.3, 18.8), built in one place so status and message stay the
 * same everywhere. Messages never carry a PIN, a key value or a provider's raw error text.
 */
public final class CryptoErrors {

  private CryptoErrors() {}

  /** The HSM cannot be reached, refused the login or failed: 503, retryable; fails closed. */
  public static MwException hsmUnavailable(String operation, Throwable cause) {
    return new MwException(ErrorCodes.SYS_HSM_UNAVAILABLE, "HSM unavailable: " + operation, cause)
        .withStatus(HttpStatus.SERVICE_UNAVAILABLE);
  }

  public static MwException unknownKey(String keyId) {
    return new MwException(ErrorCodes.VAL_UNKNOWN_KEY, "Unknown keyId " + keyId).withStatus(HttpStatus.NOT_FOUND);
  }

  public static MwException unknownPurpose(String purpose) {
    return new MwException(ErrorCodes.VAL_UNKNOWN_PURPOSE, "Unknown key purpose " + purpose);
  }

  public static MwException keyExists(String keyId) {
    return new MwException(ErrorCodes.VAL_KEY_EXISTS, "Key " + keyId + " exists already")
        .withStatus(HttpStatus.CONFLICT);
  }

  public static MwException noPublicKey(String keyId) {
    return new MwException(ErrorCodes.VAL_NO_PUBLIC_KEY, "Key " + keyId + " is symmetric: no public key");
  }

  /** Whether {@code e} is an HSM failure (for the probe and the metrics). */
  public static boolean isHsmFailure(Throwable e) {
    return e instanceof MwException m && ErrorCodes.SYS_HSM_UNAVAILABLE.equals(m.code());
  }
}
