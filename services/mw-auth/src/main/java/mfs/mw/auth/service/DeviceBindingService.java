package mfs.mw.auth.service;

import java.security.PublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import mfs.mw.auth.config.DeviceBindingProperties;
import mfs.mw.auth.dto.AppUser;
import mfs.mw.auth.dto.BoundDevice;
import mfs.mw.auth.dto.DeviceOtpSent;
import mfs.mw.auth.dto.DeviceSession;
import mfs.mw.auth.dto.LiveSession;
import mfs.mw.auth.dto.NotificationRequest;
import mfs.mw.auth.dto.SignedRequest;
import mfs.mw.auth.entity.AuthDevice;
import mfs.mw.auth.exception.ErrorCodes;
import mfs.mw.auth.exception.MwException;
import mfs.mw.auth.mapper.DeviceNotificationMapper;
import mfs.mw.auth.messaging.EventMetadata;
import mfs.mw.auth.messaging.OutboxWriter;
import mfs.mw.auth.repository.DeviceRepository;
import mfs.mw.auth.repository.DeviceSessionStore;
import mfs.mw.auth.repository.WalletRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Device binding, the signed bind and session-open requests, and sessions (spec 9.1, 18.6, 18.7;
 * built in mw-app-gateway by ticket 29 and review A5, moved here by ticket 68 with the same codes).
 *
 * <ul>
 *   <li>Bind ({@code POST /auth/v1/devices}): the request is signed with the key being registered
 *       (proof of possession) and carries the OTP sent by SMS to the token's wallet for this device
 *       ({@link DeviceOtpService}); the device becomes the wallet's one bound device ({@code
 *       auth.device}), the wallet's other device is unbound and its session ended, the OTP is spent
 *       and a "new device bound" SMS queued (outbox, mw-notify) in the binding's transaction; a
 *       session is opened.
 *   <li>Signed request (bind, session open): timestamp within the skew (MW-AUTH-005), signature
 *       (MW-AUTH-004), nonce never seen for this device (MW-AUTH-006); a session open also needs the
 *       device bound to the token's wallet (MW-AUTH-003).
 *   <li>Session {@code sess:{walletId}:{deviceId}}: mw-app-gateway asks for it on every app request
 *       ({@link #checkSession}, internal API), which extends it by the idle timeout (MW-AUTH-007 when
 *       gone); a signed session open starts a new one.
 * </ul>
 */
@Service
public class DeviceBindingService {

  private static final Logger log = LoggerFactory.getLogger(DeviceBindingService.class);
  private static final Pattern DEVICE_ID = Pattern.compile("[A-Za-z0-9._:-]{8,128}");
  private static final Pattern NONCE = Pattern.compile("[A-Za-z0-9._:-]{16,128}");

  private final DeviceRepository devices;
  private final WalletRepository wallets;
  private final DeviceSessionStore sessions;
  private final DeviceBindingProperties props;
  private final RequestSignatures signatures;
  private final DeviceOtpService otps;
  private final DeviceNotificationMapper notifications;
  private final OutboxWriter outbox;
  private final TransactionTemplate tx;
  private final Clock clock;

  public DeviceBindingService(
      DeviceRepository devices,
      WalletRepository wallets,
      DeviceSessionStore sessions,
      DeviceBindingProperties props,
      DeviceOtpService otps,
      DeviceNotificationMapper notifications,
      OutboxWriter outbox,
      TransactionTemplate tx,
      Clock clock) {
    this.devices = devices;
    this.wallets = wallets;
    this.sessions = sessions;
    this.props = props;
    this.signatures = new RequestSignatures(props.clockSkew());
    this.otps = otps;
    this.notifications = notifications;
    this.outbox = outbox;
    this.tx = tx;
    this.clock = clock;
  }

  /** A binding OTP for the device in {@code X-Device-Id}, by SMS to the token's wallet. */
  public DeviceOtpSent sendOtp(AppUser user, String deviceId, String locale) {
    String id = deviceId(deviceId);
    known(user);
    return otps.send(user.msisdn(), id, locale);
  }

  /**
   * Signature and nonce first, then the OTP (each check reserved atomically, committed whatever
   * the outcome); only a matching OTP opens the binding's transaction, which spends it.
   */
  public DeviceSession bind(AppUser user, String publicKey, String otp, SignedRequest req) {
    String deviceId = deviceId(req.deviceId());
    PublicKey key = RequestSignatures.parsePublicKey(publicKey);
    verify(key, req);
    known(user);
    DeviceOtpService.Checked checked = otps.check(user.msisdn(), deviceId, otp);
    Instant now = clock.instant();
    String wallet = user.msisdn();
    List<String[]> ended = tx.execute(status -> {
      otps.consume(wallet, deviceId, checked);
      List<String[]> closed = new ArrayList<>();
      Optional<AuthDevice> before = devices.lock(deviceId);
      before.filter(d -> d.bound() && !d.walletId().equals(wallet))
          .ifPresent(d -> closed.add(new String[] {d.walletId(), d.deviceId()}));
      for (String other : devices.unbindOthers(wallet, deviceId, now)) {
        closed.add(new String[] {wallet, other});
      }
      devices.bind(deviceId, wallet, publicKey, now);
      String correlationId = DeviceOtpService.correlationId();
      NotificationRequest notice = notifications.bound(wallet, checked.locale(), correlationId);
      outbox.append(props.notifyTopic(), wallet,
          new EventMetadata(correlationId, notice.messageId(), notice.messageId(), 1), notice);
      return closed;
    });
    for (String[] s : ended == null ? List.<String[]>of() : ended) {
      sessions.close(s[0], s[1]);
    }
    Duration idle = props.idleTimeout(user.channel());
    sessions.open(wallet, deviceId, idle);
    log.atInfo().addKeyValue("event", "device.bound").addKeyValue("channel", user.channel())
        .log("Device {} bound", deviceId);
    return new DeviceSession(deviceId, idle.toSeconds());
  }

  public DeviceSession openSession(AppUser user, SignedRequest req) {
    AuthDevice device = bound(user.msisdn(), req.deviceId());
    verify(RequestSignatures.parsePublicKey(device.publicKey()), req);
    Duration idle = props.idleTimeout(user.channel());
    sessions.open(user.msisdn(), device.deviceId(), idle);
    log.atInfo().addKeyValue("event", "device.session.opened").addKeyValue("channel", user.channel())
        .log("Session opened on device {}", device.deviceId());
    return new DeviceSession(device.deviceId(), idle.toSeconds());
  }

  /**
   * The bound device {@code deviceId} (internal API, the gateway's signature check): 404
   * MW-AUTH-003 when it is not bound to any wallet.
   */
  public BoundDevice device(String deviceId) {
    AuthDevice d = devices.find(deviceId(deviceId)).filter(AuthDevice::bound).orElseThrow(() -> notFound(
        ErrorCodes.AUTH_DEVICE_NOT_BOUND, "Device not bound"));
    return new BoundDevice(d.deviceId(), d.walletId(), d.publicKey(), d.boundAt());
  }

  /**
   * The wallet's live session on the device, extended by its idle timeout (internal API, every app
   * request): 404 MW-AUTH-007 when there is none.
   */
  public LiveSession checkSession(String walletId, String deviceId) {
    String id = deviceId(deviceId);
    Duration idle = sessions.touch(walletId, id);
    if (idle == null) {
      throw notFound(ErrorCodes.AUTH_NO_SESSION, "No live session");
    }
    return new LiveSession(walletId, id, idle.toSeconds());
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

  private AuthDevice bound(String walletId, String deviceId) {
    return devices
        .find(deviceId(deviceId))
        .filter(d -> d.bound() && d.walletId().equals(walletId))
        .orElseThrow(() -> new MwException(ErrorCodes.AUTH_DEVICE_NOT_BOUND, "Device not bound to this wallet"));
  }

  /** The token's wallet must be in the projection of the core's wallets (auth.wallet). */
  private void known(AppUser user) {
    if (wallets.version(user.msisdn()).isEmpty()) {
      throw new MwException(ErrorCodes.AUTH_FORBIDDEN, "Wallet not known").withStatus(HttpStatus.FORBIDDEN);
    }
  }

  private static MwException notFound(String code, String message) {
    return new MwException(code, message).withStatus(HttpStatus.NOT_FOUND);
  }

  private static String deviceId(String deviceId) {
    if (deviceId == null || !DEVICE_ID.matcher(deviceId).matches()) {
      throw new MwException(ErrorCodes.AUTH_DEVICE_NOT_BOUND, "X-Device-Id missing or not valid");
    }
    return deviceId;
  }
}
