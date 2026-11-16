package mfs.mw.auth.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import mfs.mw.auth.config.DeviceBindingProperties;
import mfs.mw.auth.dto.EncryptedPin;
import mfs.mw.auth.dto.PinHmac;
import mfs.mw.auth.dto.TestAccount;
import mfs.mw.auth.entity.AuthWallet;
import mfs.mw.auth.exception.ErrorCodes;
import mfs.mw.auth.exception.MwException;
import mfs.mw.auth.repository.ChannelOtpRepository;
import mfs.mw.auth.repository.CredentialRepository;
import mfs.mw.auth.repository.DeviceOtpRepository;
import mfs.mw.auth.repository.DeviceRepository;
import mfs.mw.auth.repository.DeviceSessionStore;
import mfs.mw.auth.repository.RefreshTokenRepository;
import mfs.mw.auth.repository.WalletRepository;
import mfs.mw.auth.util.MaskingPolicy;
import mfs.mw.auth.util.SensitiveType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Dev only ({@code mw.auth.seed-api.enabled}, the same switch as the seed API): the test console's
 * account actions ({@code auth-test.v1}). Shows a wallet's PIN, lockout, OTP and device state; clears
 * every lockout and counter; unbinds every device (refresh tokens revoked, sessions closed); sets a new
 * PIN. The PIN arrives encrypted under the transport key, as from an app, and mw-crypto derives the
 * HMAC as for a seeded first PIN (no policy inputs: no birth-year, MSISDN or history rule; a weak PIN
 * is still 400 MW-AUTH-044). A wallet not projected yet is 404 MW-VAL-003. Nothing here logs the
 * wallet unmasked or any PIN material.
 */
@Service
@ConditionalOnProperty(name = "mw.auth.seed-api.enabled", havingValue = "true")
public class TestAccountService {

  private static final Logger log = LoggerFactory.getLogger(TestAccountService.class);

  private final WalletRepository wallets;
  private final CredentialRepository credentials;
  private final PinVerifier verifier;
  private final PinService pins;
  private final DeviceRepository devices;
  private final DeviceOtpRepository deviceOtps;
  private final ChannelOtpRepository channelOtps;
  private final RefreshTokenRepository refresh;
  private final DeviceSessionStore sessions;
  private final DeviceBindingProperties binding;
  private final TransactionTemplate tx;
  private final Clock clock;

  public TestAccountService(
      WalletRepository wallets,
      CredentialRepository credentials,
      PinVerifier verifier,
      PinService pins,
      DeviceRepository devices,
      DeviceOtpRepository deviceOtps,
      ChannelOtpRepository channelOtps,
      RefreshTokenRepository refresh,
      DeviceSessionStore sessions,
      DeviceBindingProperties binding,
      TransactionTemplate tx,
      Clock clock) {
    this.wallets = wallets;
    this.credentials = credentials;
    this.verifier = verifier;
    this.pins = pins;
    this.devices = devices;
    this.deviceOtps = deviceOtps;
    this.channelOtps = channelOtps;
    this.refresh = refresh;
    this.sessions = sessions;
    this.binding = binding;
    this.tx = tx;
    this.clock = clock;
  }

  /** The wallet's state; 404 MW-VAL-003 when it is not projected. */
  public TestAccount view(String walletId) {
    AuthWallet wallet = wallet(walletId);
    Instant now = clock.instant();
    Optional<CredentialRepository.Lock> pinLock = credentials.lock(walletId, PinVerifier.PIN);
    boolean adminLocked = credentials.lock(walletId, PinVerifier.ADMIN).map(l -> l.activeAt(now)).orElse(false);
    boolean pinLocked = pinLock.map(l -> l.activeAt(now)).orElse(false);
    Instant lockedUntil = pinLocked ? pinLock.get().until() : null;
    TestAccount.DeviceOtp otp = deviceOtps.find(walletId)
        .map(o -> new TestAccount.DeviceOtp(o.purpose(), o.sends(), o.attempts(), o.expiresAt(),
            o.otpHash() != null && o.expiresAt().isAfter(now) && o.attempts() < binding.otp().maxAttempts()))
        .orElse(null);
    List<TestAccount.Device> list = devices.findByWallet(walletId).stream()
        .map(d -> new TestAccount.Device(d.deviceId(), d.bound() ? "BOUND" : "UNBOUND", d.boundAt(), d.unboundAt()))
        .toList();
    return new TestAccount(
        walletId,
        wallet.status(),
        credentials.find(walletId).isPresent(),
        pinLocked || adminLocked,
        lockedUntil,
        credentials.failures(walletId),
        pinLock.map(l -> new TestAccount.PinLock(l.level(), l.until(), l.activeAt(now))).orElse(null),
        adminLocked,
        otp,
        channelOtps.quotaSends(walletId),
        list);
  }

  /**
   * Clears every lockout and counter of the wallet in one transaction: the ADMIN lock, the PIN lock
   * with its level history and the wrong-PIN count, the device OTP with its send window (a pending
   * OTP is void) and the checkout OTP send quota.
   */
  public TestAccount unlock(String walletId) {
    wallet(walletId);
    tx.executeWithoutResult(s -> {
      credentials.removeLock(walletId, PinVerifier.ADMIN);
      verifier.clearPinLock(walletId);
      deviceOtps.delete(walletId);
      channelOtps.clearQuota(walletId);
    });
    log.atInfo().addKeyValue("event", "auth.test.account.unlocked").addKeyValue("wallet", masked(walletId))
        .log("Test API: every lockout and counter of the wallet cleared");
    return view(walletId);
  }

  /**
   * Unbinds every bound device of the wallet and revokes its refresh tokens (one transaction), then
   * closes the devices' sessions; the next app login needs a new binding.
   */
  public TestAccount unbind(String walletId) {
    wallet(walletId);
    Instant now = clock.instant();
    List<String> unbound = tx.execute(s -> {
      List<String> ids = devices.unbindAll(walletId, now);
      refresh.revokeWallet(walletId);
      return ids;
    });
    for (String deviceId : unbound == null ? List.<String>of() : unbound) {
      sessions.close(walletId, deviceId);
    }
    log.atInfo().addKeyValue("event", "auth.test.account.unbound").addKeyValue("wallet", masked(walletId))
        .addKeyValue("devices", unbound == null ? 0 : unbound.size())
        .log("Test API: every device of the wallet unbound, refresh tokens revoked");
    return view(walletId);
  }

  /**
   * Sets the wallet's PIN, replacing a current one (and forgetting the earlier PINs of the reuse
   * rule); locks and sessions are left as they are.
   */
  public TestAccount setPin(String walletId, EncryptedPin pin) {
    wallet(walletId);
    PinHmac hmac = pins.derive(walletId, pin, null);
    credentials.replace(walletId, hmac, List.of(), clock.instant());
    log.atInfo().addKeyValue("event", "auth.test.account.pin_set").addKeyValue("wallet", masked(walletId))
        .log("Test API: the wallet's PIN set");
    return view(walletId);
  }

  private AuthWallet wallet(String walletId) {
    return wallets.find(walletId).orElseThrow(() ->
        new MwException(ErrorCodes.VAL_NOT_FOUND, "Unknown wallet").withStatus(HttpStatus.NOT_FOUND));
  }

  private static String masked(String walletId) {
    return MaskingPolicy.mask(SensitiveType.MSISDN, walletId);
  }
}
