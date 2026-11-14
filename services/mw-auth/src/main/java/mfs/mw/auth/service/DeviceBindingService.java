package mfs.mw.auth.service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import mfs.mw.auth.config.DeviceBindingProperties;
import mfs.mw.auth.dto.BoundDevice;
import mfs.mw.auth.dto.DeviceBound;
import mfs.mw.auth.dto.DeviceOtpSent;
import mfs.mw.auth.dto.EncryptedPin;
import mfs.mw.auth.dto.ErrorDetail;
import mfs.mw.auth.dto.LiveSession;
import mfs.mw.auth.dto.NotificationRequest;
import mfs.mw.auth.dto.SignedRequest;
import mfs.mw.auth.entity.AuthDevice;
import mfs.mw.auth.entity.AuthWallet;
import mfs.mw.auth.exception.ErrorCodes;
import mfs.mw.auth.exception.MwException;
import mfs.mw.auth.mapper.DeviceNotificationMapper;
import mfs.mw.auth.messaging.EventMetadata;
import mfs.mw.auth.messaging.OutboxWriter;
import mfs.mw.auth.repository.DeviceRepository;
import mfs.mw.auth.repository.DeviceSessionStore;
import mfs.mw.auth.repository.RefreshTokenRepository;
import mfs.mw.auth.util.MaskingPolicy;
import mfs.mw.auth.util.SensitiveType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Device binding, the OTP and sessions (spec 9.1, 18.6, 18.7; built in mw-app-gateway by ticket 29
 * and review A5, moved here by ticket 68 with the same codes; since ticket 69 without a bearer
 * token: the wallet is named by its MSISDN).
 *
 * <ul>
 *   <li>OTP ({@code POST /auth/v1/otp}): purpose {@code BIND}, or {@code LOGIN} for a wallet without a
 *       PIN ({@link DeviceOtpService}).
 *   <li>Bind ({@code POST /auth/v1/devices}): signed with the key being registered (proof of
 *       possession), the wallet's status and locks ({@link WalletAccess#check}), the {@code BIND} OTP
 *       (each check reserved atomically) and, when the wallet has a PIN, the PIN ({@link PinService});
 *       then in one transaction the OTP is spent, the wallet's other bound device unbound, the device
 *       bound to this wallet and a "new device bound" SMS queued (outbox, mw-notify). Afterwards the
 *       sessions of the devices that lost their binding (and this device's own) are closed and the
 *       wallet's refresh tokens revoked; no session is opened (log in next).
 *   <li>Session {@code sess:{walletId}:{deviceId}}: opened by a login ({@link LoginService});
 *       mw-app-gateway asks for it on every app request ({@link #checkSession}, internal API), which
 *       extends it and answers the device's current key fingerprint.
 * </ul>
 */
@Service
public class DeviceBindingService {

  private static final Logger log = LoggerFactory.getLogger(DeviceBindingService.class);

  private final DeviceRepository devices;
  private final WalletAccess wallets;
  private final DeviceSessionStore sessions;
  private final DeviceBindingProperties props;
  private final SignedRequestCheck signed;
  private final DeviceOtpService otps;
  private final PinService pins;
  private final RefreshTokenRepository refresh;
  private final DeviceNotificationMapper notifications;
  private final OutboxWriter outbox;
  private final TransactionTemplate tx;
  private final Clock clock;

  public DeviceBindingService(
      DeviceRepository devices,
      WalletAccess wallets,
      DeviceSessionStore sessions,
      DeviceBindingProperties props,
      SignedRequestCheck signed,
      DeviceOtpService otps,
      PinService pins,
      RefreshTokenRepository refresh,
      DeviceNotificationMapper notifications,
      OutboxWriter outbox,
      TransactionTemplate tx,
      Clock clock) {
    this.devices = devices;
    this.wallets = wallets;
    this.sessions = sessions;
    this.props = props;
    this.signed = signed;
    this.otps = otps;
    this.pins = pins;
    this.refresh = refresh;
    this.notifications = notifications;
    this.outbox = outbox;
    this.tx = tx;
    this.clock = clock;
  }

  /**
   * An OTP of {@code purpose} for the device in {@code X-Device-Id}, by SMS to the wallet of the
   * MSISDN. 403 MW-AUTH-002 for an unknown wallet, or a {@code LOGIN} OTP for a wallet with a PIN.
   */
  public DeviceOtpSent sendOtp(String msisdnIn, String deviceId, String purpose, String locale) {
    String id = SignedRequestCheck.deviceId(deviceId);
    String msisdn = WalletAccess.msisdn(msisdnIn);
    String p = purpose == null ? DeviceOtpService.BIND : purpose;
    AuthWallet w = known(msisdn);
    if (DeviceOtpService.LOGIN.equals(p) && wallets.hasPin(w.walletId())) {
      throw new MwException(ErrorCodes.AUTH_FORBIDDEN, "This wallet has a PIN: sign in with it")
          .withStatus(HttpStatus.FORBIDDEN);
    }
    return otps.send(w.walletId(), msisdn, id, locale, p);
  }

  /**
   * Signature and nonce first, then the wallet's status and locks, the OTP (each check reserved
   * atomically, committed whatever the outcome) and the PIN; only then the binding's transaction,
   * which spends the OTP.
   */
  public DeviceBound bind(String msisdnIn, String publicKey, String otp, EncryptedPin pin, SignedRequest req) {
    String deviceId = SignedRequestCheck.deviceId(req.deviceId());
    String msisdn = WalletAccess.msisdn(msisdnIn);
    PublicKey key = RequestSignatures.parsePublicKey(publicKey);
    signed.verify(key, req);
    AuthWallet w = known(msisdn);
    String wallet = w.walletId();
    wallets.check(w);
    boolean hasPin = wallets.hasPin(wallet);
    if (hasPin && pin == null) {
      throw new MwException(ErrorCodes.VAL_INVALID, "Invalid request").withStatus(HttpStatus.BAD_REQUEST)
          .withDetails(List.of(new ErrorDetail("encryptedPin", "required: this wallet has a PIN")));
    }
    DeviceOtpService.Checked checked = otps.check(wallet, deviceId, otp, DeviceOtpService.BIND);
    if (hasPin) {
      pins.verify(wallet, pin);
    }
    Instant now = clock.instant();
    List<String[]> ended = tx.execute(status -> {
      otps.consume(wallet, deviceId, checked);
      List<String[]> closed = new ArrayList<>();
      Optional<AuthDevice> before = devices.lock(deviceId);
      before.filter(d -> d.bound() && !d.walletId().equals(wallet))
          .ifPresent(d -> closed.add(new String[] {d.walletId(), d.deviceId()}));
      for (String other : devices.unbindOthers(wallet, deviceId, now)) {
        closed.add(new String[] {wallet, other});
      }
      closed.add(new String[] {wallet, deviceId});
      devices.bind(deviceId, wallet, publicKey, now);
      String correlationId = DeviceOtpService.correlationId();
      NotificationRequest notice = notifications.bound(msisdn, checked.locale(), correlationId);
      outbox.append(props.notifyTopic(), msisdn,
          new EventMetadata(correlationId, notice.messageId(), notice.messageId(), 1), notice);
      return closed;
    });
    for (String[] s : ended == null ? List.<String[]>of() : ended) {
      sessions.close(s[0], s[1]);
      refresh.revokeWallet(s[0]);
    }
    log.atInfo().addKeyValue("event", "device.bound").log("Device {} bound", deviceId);
    return new DeviceBound(deviceId, MaskingPolicy.mask(SensitiveType.MSISDN, msisdn), now);
  }

  /**
   * The bound device {@code deviceId} (internal API, the gateway's signature check): 404
   * MW-AUTH-003 when it is not bound to any wallet.
   */
  public BoundDevice device(String deviceId) {
    AuthDevice d = devices.find(SignedRequestCheck.deviceId(deviceId)).filter(AuthDevice::bound)
        .orElseThrow(() -> notFound(ErrorCodes.AUTH_DEVICE_NOT_BOUND, "Device not bound"));
    return new BoundDevice(d.deviceId(), d.walletId(), d.publicKey(), d.boundAt());
  }

  /**
   * The wallet's live session on the device, extended by its idle timeout, with the device's
   * current key fingerprint (internal API, every app request): 404 MW-AUTH-003 when the device is
   * not bound to this wallet (any more), MW-AUTH-007 when there is no session.
   */
  public LiveSession checkSession(String walletId, String deviceId) {
    String id = SignedRequestCheck.deviceId(deviceId);
    AuthDevice d = devices.find(id).filter(x -> x.bound() && x.walletId().equals(walletId))
        .orElseThrow(() -> notFound(ErrorCodes.AUTH_DEVICE_NOT_BOUND, "Device not bound to this wallet"));
    Duration idle = sessions.touch(walletId, id);
    if (idle == null) {
      throw notFound(ErrorCodes.AUTH_NO_SESSION, "No live session");
    }
    return new LiveSession(walletId, id, idle.toSeconds(), fingerprint(d.publicKey()), d.boundAt());
  }

  /** Lower-case hex SHA-256 of a base64 SubjectPublicKeyInfo's DER bytes. */
  public static String fingerprint(String publicKeyBase64) {
    try {
      return HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256").digest(Base64.getDecoder().decode(publicKeyBase64)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** The wallet must be in the projection of the core's wallets (auth.wallet). */
  private AuthWallet known(String msisdn) {
    return wallets.byMsisdn(msisdn).orElseThrow(() ->
        new MwException(ErrorCodes.AUTH_FORBIDDEN, "Wallet not known").withStatus(HttpStatus.FORBIDDEN));
  }

  private static MwException notFound(String code, String message) {
    return new MwException(code, message).withStatus(HttpStatus.NOT_FOUND);
  }
}
