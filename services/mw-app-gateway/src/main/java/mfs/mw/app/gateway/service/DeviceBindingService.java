package mfs.mw.app.gateway.service;

import java.security.PublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.regex.Pattern;
import mfs.mw.app.gateway.client.DeviceSessionStore;
import mfs.mw.app.gateway.config.DeviceBindingProperties;
import mfs.mw.app.gateway.dto.AppUser;
import mfs.mw.app.gateway.dto.DeviceSession;
import mfs.mw.app.gateway.dto.SignedRequest;
import mfs.mw.app.gateway.entity.AppDevice;
import mfs.mw.app.gateway.exception.ErrorCodes;
import mfs.mw.app.gateway.exception.MwException;
import mfs.mw.app.gateway.repository.AppDeviceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Device binding, signed requests and sessions (spec 9.1, 18.6, ticket 29).
 *
 * <ul>
 *   <li>Bind ({@code POST /app/v1/devices}): the request is signed with the key being registered
 *       (proof of possession); the device becomes ACTIVE for the token's wallet and a session is
 *       opened. With a single active device per wallet, the wallet's other devices are REPLACED
 *       and their sessions ended.
 *   <li>Signed request (submit, session open): device bound and active (MW-AUTH-003), timestamp
 *       within the skew (MW-AUTH-005), signature by the bound key (MW-AUTH-004), nonce never seen
 *       for this device (MW-AUTH-006).
 *   <li>Session {@code sess:{subject}:{deviceId}}: every request of a bound device needs a live one
 *       (MW-AUTH-007) and extends it by the idle timeout; a signed session open starts a new one.
 * </ul>
 */
@Service
public class DeviceBindingService {

  private static final Logger log = LoggerFactory.getLogger(DeviceBindingService.class);
  private static final Pattern DEVICE_ID = Pattern.compile("[A-Za-z0-9._:-]{8,128}");
  private static final Pattern NONCE = Pattern.compile("[A-Za-z0-9._:-]{16,128}");

  private final AppDeviceRepository devices;
  private final DeviceSessionStore sessions;
  private final DeviceBindingProperties props;
  private final RequestSignatures signatures;
  private final Clock clock;

  public DeviceBindingService(
      AppDeviceRepository devices, DeviceSessionStore sessions, DeviceBindingProperties props, Clock clock) {
    this.devices = devices;
    this.sessions = sessions;
    this.props = props;
    this.signatures = new RequestSignatures(props.clockSkew());
    this.clock = clock;
  }

  @Transactional
  public DeviceSession bind(String subject, AppUser user, String publicKey, SignedRequest req) {
    String deviceId = deviceId(req.deviceId());
    PublicKey key = RequestSignatures.parsePublicKey(publicKey);
    verify(key, req);
    Instant now = clock.instant();
    devices.bind(user.msisdn(), deviceId, subject, publicKey, now);
    if (props.singleActiveDevice()) {
      devices.replaceOthers(user.msisdn(), deviceId, now);
      endOtherSessions(user, deviceId);
    }
    Duration idle = props.idleTimeout(user.channel());
    sessions.open(subject, deviceId, idle);
    log.atInfo().addKeyValue("event", "device.bound").addKeyValue("channel", user.channel())
        .log("Device {} bound", deviceId);
    return new DeviceSession(deviceId, idle.toSeconds());
  }

  public DeviceSession openSession(String subject, AppUser user, SignedRequest req) {
    AppDevice device = bound(user, req.deviceId());
    verify(RequestSignatures.parsePublicKey(device.publicKey()), req);
    if (props.singleActiveDevice()) {
      endOtherSessions(user, device.deviceId());
    }
    Duration idle = props.idleTimeout(user.channel());
    sessions.open(subject, device.deviceId(), idle);
    log.atInfo().addKeyValue("event", "device.session.opened").addKeyValue("channel", user.channel())
        .log("Session opened on device {}", device.deviceId());
    return new DeviceSession(device.deviceId(), idle.toSeconds());
  }

  /** A sensitive operation: bound device, valid signature, fresh nonce and a live session. */
  public void verifySigned(String subject, AppUser user, SignedRequest req) {
    AppDevice device = bound(user, req.deviceId());
    verify(RequestSignatures.parsePublicKey(device.publicKey()), req);
    touch(subject, user, device.deviceId());
  }

  /** Any other app request: bound device and a live session. */
  public void checkSession(String subject, AppUser user, String deviceId) {
    AppDevice device = bound(user, deviceId);
    touch(subject, user, device.deviceId());
  }

  private void verify(PublicKey key, SignedRequest req) {
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

  private AppDevice bound(AppUser user, String deviceId) {
    return devices
        .find(user.msisdn(), deviceId(deviceId))
        .filter(AppDevice::active)
        .orElseThrow(() -> new MwException(ErrorCodes.AUTH_DEVICE_NOT_BOUND, "Device not bound to this wallet"));
  }

  private void touch(String subject, AppUser user, String deviceId) {
    if (!sessions.touch(subject, deviceId, props.idleTimeout(user.channel()))) {
      throw new MwException(ErrorCodes.AUTH_NO_SESSION, "Session expired, sign in again");
    }
  }

  private void endOtherSessions(AppUser user, String deviceId) {
    for (AppDevice other : devices.others(user.msisdn(), deviceId)) {
      sessions.close(other.subject(), other.deviceId());
    }
  }

  private static String deviceId(String deviceId) {
    if (deviceId == null || !DEVICE_ID.matcher(deviceId).matches()) {
      throw new MwException(ErrorCodes.AUTH_DEVICE_NOT_BOUND, "X-Device-Id missing or not valid");
    }
    return deviceId;
  }
}
