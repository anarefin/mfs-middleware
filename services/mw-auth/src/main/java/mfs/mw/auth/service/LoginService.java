package mfs.mw.auth.service;

import java.security.PublicKey;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import mfs.mw.auth.config.AppTypePolicy;
import mfs.mw.auth.dto.EncryptedPin;
import mfs.mw.auth.dto.ErrorDetail;
import mfs.mw.auth.dto.PinHmac;
import mfs.mw.auth.dto.SignedRequest;
import mfs.mw.auth.dto.TokenPair;
import mfs.mw.auth.entity.AuthDevice;
import mfs.mw.auth.entity.AuthWallet;
import mfs.mw.auth.entity.RefreshToken;
import mfs.mw.auth.exception.ErrorCodes;
import mfs.mw.auth.exception.MwException;
import mfs.mw.auth.repository.CredentialRepository;
import mfs.mw.auth.repository.DeviceRepository;
import mfs.mw.auth.repository.DeviceSessionStore;
import mfs.mw.auth.repository.RefreshTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Wallet login and the session's tokens (spec 18.7 steps 3-5, D21; ticket 69).
 *
 * <ul>
 *   <li>Login ({@code POST /auth/v1/sessions}), in this order: the wallet's status and locks ({@link
 *       WalletAccess#check}), the device bound to this wallet (MW-AUTH-047) and its signature, then the
 *       PIN through {@link PinVerifier} (lockout); a wallet without a PIN gives a {@code LOGIN} OTP
 *       instead and gets {@code amr} [otp, device]. Tokens by {@link TokenService}; the session {@code
 *       sess:{walletId}:{deviceId}} is opened with the app's idle timeout.
 *   <li>Refresh: signed by the device the token was issued to, rotated ({@link TokenService#rotate}),
 *       the wallet checked again, new tokens of the same session.
 *   <li>Logout: the session's refresh family revoked and its idle session closed.
 *   <li>First PIN ({@code POST /auth/v1/pin}): only for a wallet without a PIN, with its OTP session;
 *       the HMAC mw-crypto derives is stored and the session replaced by one with {@code amr} [pin,
 *       device].
 * </ul>
 */
@Service
public class LoginService {

  private static final Logger log = LoggerFactory.getLogger(LoginService.class);
  static final List<String> PIN_AMR = List.of("pin", "device");
  static final List<String> OTP_AMR = List.of("otp", "device");
  private static final Map<String, String> CHANNELS =
      Map.of("customer", "CUSTOMER_APP", "agent", "AGENT_APP", "merchant", "MERCHANT_APP",
          "distributor", "DISTRIBUTOR_APP", "dso", "DSO_APP", "partner", "PARTNER_APP");

  private final WalletAccess wallets;
  private final DeviceRepository devices;
  private final SignedRequestCheck signed;
  private final PinService pins;
  private final PinVerifier verifier;
  private final AuditTrail audit;
  private final org.springframework.transaction.support.TransactionTemplate tx;
  private final DeviceOtpService otps;
  private final TokenService tokens;
  private final RefreshTokenRepository refresh;
  private final CredentialRepository credentials;
  private final DeviceSessionStore sessions;
  private final AppTypePolicy appTypes;
  private final PinNotices notices;
  private final java.time.Clock clock;

  public LoginService(
      WalletAccess wallets,
      DeviceRepository devices,
      SignedRequestCheck signed,
      PinService pins,
      PinVerifier verifier,
      AuditTrail audit,
      org.springframework.transaction.support.TransactionTemplate tx,
      DeviceOtpService otps,
      TokenService tokens,
      RefreshTokenRepository refresh,
      CredentialRepository credentials,
      DeviceSessionStore sessions,
      AppTypePolicy appTypes,
      PinNotices notices,
      java.time.Clock clock) {
    this.wallets = wallets;
    this.devices = devices;
    this.signed = signed;
    this.pins = pins;
    this.verifier = verifier;
    this.audit = audit;
    this.tx = tx;
    this.otps = otps;
    this.tokens = tokens;
    this.refresh = refresh;
    this.credentials = credentials;
    this.sessions = sessions;
    this.appTypes = appTypes;
    this.notices = notices;
    this.clock = clock;
  }

  /** The app channel of a role (the session's idle timeout per channel). */
  static String channel(String role) {
    return CHANNELS.getOrDefault(role, "CUSTOMER_APP");
  }

  public TokenPair login(String msisdnIn, EncryptedPin pin, String otp, SignedRequest req) {
    String msisdn = WalletAccess.msisdn(msisdnIn);
    String deviceId = SignedRequestCheck.deviceId(req.deviceId());
    if ((pin == null) == (otp == null)) {
      throw invalid("encryptedPin", "exactly one of encryptedPin and otp is required");
    }
    AuthWallet w = wallets.byMsisdn(msisdn).orElseThrow(LoginService::deviceMismatch);
    String role = TokenService.role(w.appType());
    wallets.check(w);
    AuthDevice d = boundTo(w.walletId(), deviceId);
    signed.verify(key(d), req);
    boolean hasPin = wallets.hasPin(w.walletId());
    List<String> amr;
    if (pin != null) {
      verifier.verify(w.walletId(), pin);
      amr = PIN_AMR;
    } else {
      if (hasPin) {
        throw invalid("encryptedPin", "this wallet has a PIN: sign in with it");
      }
      DeviceOtpService.Checked checked = otps.check(w.walletId(), deviceId, otp, DeviceOtpService.LOGIN);
      otps.consume(w.walletId(), deviceId, checked);
      amr = OTP_AMR;
    }
    TokenPair pair = tokens.issue(w, msisdn, deviceId, amr);
    Duration idle = open(w.walletId(), deviceId, w.appType());
    log.atInfo().addKeyValue("event", "auth.login").addKeyValue("channel", channel(role))
        .addKeyValue("amr", String.join(",", amr)).log("Wallet logged in on device {}", deviceId);
    return pair.withSession(idle.toSeconds(), hasPin);
  }

  public TokenPair refresh(String refreshToken, SignedRequest req) {
    String deviceId = SignedRequestCheck.deviceId(req.deviceId());
    AuthDevice d = devices.find(deviceId).filter(AuthDevice::bound).orElseThrow(LoginService::deviceMismatch);
    signed.verify(key(d), req);
    RefreshToken current = tokens.rotate(refreshToken, deviceId);
    if (!current.walletId().equals(d.walletId())) {
      tokens.revoke(current.family());
      throw deviceMismatch();
    }
    AuthWallet w = wallets.byId(current.walletId()).orElseThrow(LoginService::deviceMismatch);
    String role = TokenService.role(w.appType());
    wallets.check(w);
    TokenPair pair = tokens.issue(w, wallets.msisdnOf(w), deviceId, current.amr(), current.family());
    Duration idle = open(w.walletId(), deviceId, w.appType());
    log.atInfo().addKeyValue("event", "auth.refresh").addKeyValue("channel", channel(role))
        .log("Tokens refreshed on device {}", deviceId);
    return pair.withSession(idle.toSeconds(), wallets.hasPin(w.walletId()));
  }

  public void logout(String walletId, String deviceId, String sid) {
    tokens.revoke(UUID.fromString(sid));
    sessions.close(walletId, deviceId);
    log.atInfo().addKeyValue("event", "auth.logout").log("Session ended on device {}", deviceId);
  }

  public TokenPair setFirstPin(String walletId, String msisdn, String deviceId, String sid, EncryptedPin pin) {
    UUID family = UUID.fromString(sid);
    AuthWallet w = wallets.byId(walletId).orElseThrow(LoginService::forbidden);
    if (!refresh.familyLive(family, clock.instant())) {
      throw new MwException(ErrorCodes.AUTH_UNAUTHENTICATED, "Session ended; sign in again");
    }
    if (wallets.hasPin(walletId)) {
      throw new MwException(ErrorCodes.AUTH_FORBIDDEN, "A PIN is already set").withStatus(HttpStatus.FORBIDDEN);
    }
    String role = TokenService.role(w.appType());
    wallets.check(w);
    PinHmac hmac = pins.newPin(w, msisdn, pin);
    if (!credentials.insertIfAbsent(walletId, hmac, clock.instant())) {
      throw new MwException(ErrorCodes.AUTH_FORBIDDEN, "A PIN is already set").withStatus(HttpStatus.FORBIDDEN);
    }
    tokens.revoke(family);
    TokenPair pair = tokens.issue(w, msisdn, deviceId, PIN_AMR);
    Duration idle = open(walletId, deviceId, w.appType());
    log.atInfo().addKeyValue("event", "auth.pin.set").log("First PIN set on device {}", deviceId);
    return pair.withSession(idle.toSeconds(), true);
  }

  /**
   * PIN change ({@code PUT /auth/v1/pin}, ticket 70): the bearer of a PIN session (an OTP session: 403
   * MW-AUTH-045), its family live; the wallet's status and locks, then the current PIN through {@link
   * PinVerifier} (counted, locked), then the new PIN against the policy; the credential replaced
   * (the old PIN kept for the reuse rule), audited, the wallet told by SMS; the session replaced by a
   * new one with a fresh {@code amr}.
   */
  public TokenPair changePin(
      String walletId, String msisdn, String deviceId, String sid, List<String> amr, EncryptedPin current,
      EncryptedPin next) {
    UUID family = UUID.fromString(sid);
    AuthWallet w = wallets.byId(walletId).orElseThrow(LoginService::forbidden);
    if (!refresh.familyLive(family, clock.instant())) {
      throw new MwException(ErrorCodes.AUTH_UNAUTHENTICATED, "Session ended; sign in again");
    }
    if (amr == null || !amr.contains("pin")) {
      throw PinService.notSet();
    }
    String role = TokenService.role(w.appType());
    wallets.check(w);
    verifier.verify(walletId, current);
    PinHmac hmac = pins.newPin(w, msisdn, next);
    java.time.Instant now = clock.instant();
    tx.executeWithoutResult(status -> {
      var before = credentials.credential(walletId);
      credentials.replace(walletId, hmac, pins.historyAfter(before), now);
      audit.record("auth.pin.changed", walletId, mfs.mw.auth.mapper.AuditEventMapper.wallet(walletId), "SUCCESS",
          null, Map.of("channel", channel(role)));
      notices.pinChanged(msisdn);
    });
    tokens.revoke(family);
    TokenPair pair = tokens.issue(w, msisdn, deviceId, PIN_AMR);
    Duration idle = open(walletId, deviceId, w.appType());
    log.atInfo().addKeyValue("event", "auth.pin.changed").log("PIN changed on device {}", deviceId);
    return pair.withSession(idle.toSeconds(), true);
  }

  private Duration open(String walletId, String deviceId, String walletType) {
    AppTypePolicy.Settings s = appTypes.of(walletType);
    Duration idle = s.idleTimeout();
    for (String ended : sessions.admit(walletId, deviceId, idle, s.maxSessions(), clock.instant())) {
      refresh.revokeDevice(walletId, ended);
    }
    return idle;
  }

  private AuthDevice boundTo(String walletId, String deviceId) {
    return devices.find(deviceId)
        .filter(x -> x.bound() && x.walletId().equals(walletId))
        .orElseThrow(LoginService::deviceMismatch);
  }

  private static PublicKey key(AuthDevice d) {
    return RequestSignatures.parsePublicKey(d.publicKey());
  }

  static MwException deviceMismatch() {
    return new MwException(ErrorCodes.AUTH_DEVICE_MISMATCH, "This device is not bound to this wallet");
  }

  private static MwException forbidden() {
    return new MwException(ErrorCodes.AUTH_FORBIDDEN, "Wallet not known").withStatus(HttpStatus.FORBIDDEN);
  }

  private static MwException invalid(String field, String issue) {
    return new MwException(ErrorCodes.VAL_INVALID, "Invalid request")
        .withStatus(HttpStatus.BAD_REQUEST)
        .withDetails(List.of(new ErrorDetail(field, issue)));
  }
}
