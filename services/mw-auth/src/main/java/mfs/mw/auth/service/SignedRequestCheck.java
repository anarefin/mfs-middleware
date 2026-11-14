package mfs.mw.auth.service;

import java.security.PublicKey;
import java.time.Clock;
import java.util.regex.Pattern;
import mfs.mw.auth.config.DeviceBindingProperties;
import mfs.mw.auth.dto.SignedRequest;
import mfs.mw.auth.exception.ErrorCodes;
import mfs.mw.auth.exception.MwException;
import mfs.mw.auth.repository.DeviceSessionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * A device-signed request (spec 9.1, 18.6): the device id's form (MW-AUTH-003), the timestamp within
 * the skew (MW-AUTH-005), the signature by {@code key} (MW-AUTH-004) and a nonce never seen for the
 * device (MW-AUTH-006, Valkey {@code nonce:{deviceId}:{nonce}}, shared with mw-app-gateway).
 */
@Service
public class SignedRequestCheck {

  private static final Logger log = LoggerFactory.getLogger(SignedRequestCheck.class);
  private static final Pattern DEVICE_ID = Pattern.compile("[A-Za-z0-9._:-]{8,128}");
  private static final Pattern NONCE = Pattern.compile("[A-Za-z0-9._:-]{16,128}");

  private final RequestSignatures signatures;
  private final DeviceSessionStore sessions;
  private final DeviceBindingProperties props;
  private final Clock clock;

  public SignedRequestCheck(DeviceSessionStore sessions, DeviceBindingProperties props, Clock clock) {
    this.signatures = new RequestSignatures(props.clockSkew());
    this.sessions = sessions;
    this.props = props;
    this.clock = clock;
  }

  /** The device id of a request; 401 MW-AUTH-003 when missing or malformed. */
  public static String deviceId(String deviceId) {
    if (deviceId == null || !DEVICE_ID.matcher(deviceId).matches()) {
      throw new MwException(ErrorCodes.AUTH_DEVICE_NOT_BOUND, "X-Device-Id missing or not valid");
    }
    return deviceId;
  }

  public void verify(PublicKey key, SignedRequest req) {
    if (req.signature() == null || req.timestamp() == null || req.nonce() == null) {
      throw new MwException(ErrorCodes.AUTH_BAD_SIGNATURE, "Request signature required");
    }
    if (!NONCE.matcher(req.nonce()).matches()) {
      throw new MwException(ErrorCodes.AUTH_BAD_SIGNATURE, "Request nonce is not valid");
    }
    signatures.verify(key, req, clock.instant());
    if (!sessions.firstUse(req.deviceId(), req.nonce(), props.nonceTtl())) {
      log.atWarn().addKeyValue("event", "device.request.replayed").addKeyValue("errorCode", ErrorCodes.AUTH_REPLAYED)
          .log("Replayed signed request from device {}", req.deviceId());
      throw new MwException(ErrorCodes.AUTH_REPLAYED, "Request already received");
    }
  }
}
