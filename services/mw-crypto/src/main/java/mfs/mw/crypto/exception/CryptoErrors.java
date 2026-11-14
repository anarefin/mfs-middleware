package mfs.mw.crypto.exception;

import java.util.List;
import mfs.mw.crypto.dto.ErrorDetail;
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

  /** The PIN block does not decrypt, or holds no 5-digit PIN. Never says which, nor any of its content. */
  public static MwException badPinBlock() {
    return new MwException(ErrorCodes.VAL_BAD_PIN_BLOCK, "PIN block invalid");
  }

  /** The key {@code ref} cannot do {@code operation} (purpose or state). */
  public static MwException keyNotUsable(String ref, String operation) {
    return new MwException(ErrorCodes.VAL_KEY_NOT_USABLE, "Key " + ref + " cannot be used for " + operation);
  }

  /** A ceremony component that is not usable; never says more, never echoes it. */
  public static MwException badComponent(String field) {
    return new MwException(ErrorCodes.VAL_BAD_COMPONENT, "Component invalid")
        .withDetails(List.of(new ErrorDetail(field, "invalid")));
  }

  /** A ceremony or key change that does not fit the current state: 409. */
  public static MwException keyChangeConflict(String message) {
    return new MwException(ErrorCodes.VAL_KEY_CHANGE_CONFLICT, message).withStatus(HttpStatus.CONFLICT);
  }

  public static MwException badEnvelope() {
    return new MwException(ErrorCodes.VAL_BAD_ENVELOPE, "Envelope cannot be decrypted");
  }

  /** A field that is not what it must be (bad base64, wrong length): 400 {@code MW-VAL-001}. */
  public static MwException invalidField(String field, String issue) {
    return new MwException(ErrorCodes.VAL_INVALID, "Request validation failed")
        .withDetails(List.of(new ErrorDetail(field, issue)));
  }

  /** Whether {@code e} is an HSM failure (for the probe and the metrics). */
  public static boolean isHsmFailure(Throwable e) {
    return e instanceof MwException m && ErrorCodes.SYS_HSM_UNAVAILABLE.equals(m.code());
  }
}
