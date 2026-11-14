package mfs.mw.app.gateway.service;

import java.time.Clock;
import java.util.regex.Pattern;
import mfs.mw.app.gateway.client.AuthClient;
import mfs.mw.app.gateway.config.DeviceBindingProperties;
import mfs.mw.app.gateway.dto.AppUser;
import mfs.mw.app.gateway.dto.SignedRequest;
import mfs.mw.app.gateway.exception.ErrorCodes;
import mfs.mw.app.gateway.exception.MwException;
import mfs.mw.app.gateway.repository.DeviceGateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The device gate's checks (spec 9.1, 18.6; ticket 29, a thin verifier since ticket 68: device
 * binding, the binding OTP and sessions live in mw-auth, {@code /auth/v1/*}).
 *
 * <ul>
 *   <li>Bound device: the wallet and public key of {@code X-Device-Id} from mw-auth ({@code GET
 *       /internal/v1/devices/{id}}, cached {@code devkey:{deviceId}} 5 min); the device must be
 *       bound to the token's wallet (MW-AUTH-003).
 *   <li>Signed request (submit): timestamp within the skew (MW-AUTH-005), signature by the bound key
 *       (MW-AUTH-004), nonce never seen for this device (Valkey, MW-AUTH-006).
 *   <li>Session: mw-auth's {@code sess:{walletId}:{deviceId}} must be live (MW-AUTH-007); asking
 *       ({@code GET /internal/v1/sessions/{walletId}/{deviceId}}) extends it. A confirmation is
 *       reused for {@code session-cache-ttl} (10 s), so an ended session (another device bound)
 *       passes here for at most that long. Decision of ticket 68: this internal lookup, not a
 *       session claim in the token (ticket 69 may move to one).
 * </ul>
 *
 * mw-auth or Valkey unreachable: 503 MW-SYS-012 (fail closed).
 */
@Service
public class DeviceVerifier {

  private static final Logger log = LoggerFactory.getLogger(DeviceVerifier.class);
  private static final Pattern DEVICE_ID = Pattern.compile("[A-Za-z0-9._:-]{8,128}");
  private static final Pattern NONCE = Pattern.compile("[A-Za-z0-9._:-]{16,128}");

  private final AuthClient auth;
  private final DeviceGateStore store;
  private final DeviceBindingProperties props;
  private final RequestSignatures signatures;
  private final Clock clock;

  public DeviceVerifier(AuthClient auth, DeviceGateStore store, DeviceBindingProperties props, Clock clock) {
    this.auth = auth;
    this.store = store;
    this.props = props;
    this.signatures = new RequestSignatures(props.clockSkew());
    this.clock = clock;
  }

  /** A sensitive operation: bound device, valid signature, fresh nonce and a live session. */
  public void verifySigned(AppUser user, SignedRequest req) {
    String deviceId = deviceId(req.deviceId());
    DeviceGateStore.CachedKey key = bound(user, deviceId);
    if (req.signature() == null || req.timestamp() == null || req.nonce() == null) {
      throw new MwException(ErrorCodes.AUTH_BAD_SIGNATURE, "Request signature required");
    }
    if (!NONCE.matcher(req.nonce()).matches()) {
      throw new MwException(ErrorCodes.AUTH_BAD_SIGNATURE, "Request nonce is not valid");
    }
    signatures.verify(RequestSignatures.parsePublicKey(key.publicKey()), req, clock.instant());
    if (!store.firstUse(deviceId, req.nonce(), props.nonceTtl())) {
      log.atWarn().addKeyValue("event", "device.request.replayed").addKeyValue("errorCode", ErrorCodes.AUTH_REPLAYED)
          .log("Replayed signed request from device {}", deviceId);
      throw new MwException(ErrorCodes.AUTH_REPLAYED, "Request already received");
    }
    session(user, deviceId);
  }

  /** Any other app request: bound device and a live session. */
  public void checkSession(AppUser user, String deviceId) {
    String id = deviceId(deviceId);
    bound(user, id);
    session(user, id);
  }

  private DeviceGateStore.CachedKey bound(AppUser user, String deviceId) {
    DeviceGateStore.CachedKey key = store.deviceKey(deviceId).orElse(null);
    if (key == null) {
      key = auth.device(deviceId)
          .map(d -> new DeviceGateStore.CachedKey(d.walletId(), d.publicKey()))
          .orElseThrow(DeviceVerifier::notBound);
      store.putDeviceKey(deviceId, key, props.deviceKeyTtl());
    }
    if (!key.walletId().equals(user.msisdn())) {
      throw notBound();
    }
    return key;
  }

  private void session(AppUser user, String deviceId) {
    if (store.sessionConfirmed(user.msisdn(), deviceId)) {
      return;
    }
    if (!auth.session(user.msisdn(), deviceId)) {
      throw new MwException(ErrorCodes.AUTH_NO_SESSION, "Session expired, sign in again");
    }
    store.confirmSession(user.msisdn(), deviceId, props.sessionCacheTtl());
  }

  private static MwException notBound() {
    return new MwException(ErrorCodes.AUTH_DEVICE_NOT_BOUND, "Device not bound to this wallet");
  }

  private static String deviceId(String deviceId) {
    if (deviceId == null || !DEVICE_ID.matcher(deviceId).matches()) {
      throw new MwException(ErrorCodes.AUTH_DEVICE_NOT_BOUND, "X-Device-Id missing or not valid");
    }
    return deviceId;
  }
}
