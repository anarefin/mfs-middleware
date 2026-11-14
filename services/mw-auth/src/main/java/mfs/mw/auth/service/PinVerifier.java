package mfs.mw.auth.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import mfs.mw.auth.client.CryptoClient;
import mfs.mw.auth.config.PinProperties;
import mfs.mw.auth.dto.CryptoPinVerified;
import mfs.mw.auth.dto.EncryptedPin;
import mfs.mw.auth.dto.ErrorDetail;
import mfs.mw.auth.dto.PinHmac;
import mfs.mw.auth.exception.ErrorCodes;
import mfs.mw.auth.exception.MwException;
import mfs.mw.auth.mapper.AuditEventMapper;
import mfs.mw.auth.repository.CredentialRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Every wallet PIN check goes through here (spec 18.7, D20; ticket 70): login, device binding, PIN
 * change and (ticket 71) the transaction authorisation. The one entry point is {@link #verify}.
 *
 * <ol>
 *   <li>The locks first, before mw-crypto is asked: an ADMIN lock 403 MW-AUTH-043, a PIN lock in force
 *       423 MW-AUTH-041 ({@code retryAfter} seconds) or MW-AUTH-042 (until a reset). A locked wallet
 *       costs no HSM call and its answers say nothing about the PIN.
 *   <li>No PIN: 403 MW-AUTH-045.
 *   <li>mw-crypto {@code /pin/verify} (down: MW-SYS-050, nothing counted).
 *   <li>Wrong: in one transaction under the wallet's row lock, the failure is counted ({@code
 *       auth.attempt}); below {@code tries} it is 401 MW-AUTH-040 with {@code triesLeft}; the
 *       {@code tries}-th locks the PIN one level up (level 1 {@code level1}, level 2 {@code level2},
 *       level 3 until a reset; the history decays {@code decay} after the last lock), resets the
 *       count, audits {@code auth.pin.locked} and is answered with the new lock (041/042).
 *   <li>Right: the count is reset (not the level history) and a rotated key's rehash stored.
 * </ol>
 *
 * Nothing about the PIN is logged.
 */
@Service
public class PinVerifier {

  static final String PIN = "PIN";
  static final String ADMIN = "ADMIN";
  private static final Logger log = LoggerFactory.getLogger(PinVerifier.class);

  private final CryptoClient crypto;
  private final CredentialRepository credentials;
  private final AuditTrail audit;
  private final PinProperties props;
  private final TransactionTemplate tx;
  private final Clock clock;

  public PinVerifier(
      CryptoClient crypto,
      CredentialRepository credentials,
      AuditTrail audit,
      PinProperties props,
      TransactionTemplate tx,
      Clock clock) {
    this.crypto = crypto;
    this.credentials = credentials;
    this.audit = audit;
    this.props = props;
    this.tx = tx;
    this.clock = clock;
  }

  /**
   * Checks the wallet's PIN with the lockout (see the class comment for the order and the codes).
   * Returns normally only on a correct PIN; the caller checks the wallet's status at the core first
   * ({@link WalletAccess#check}).
   */
  public void verify(String walletId, EncryptedPin pin) {
    requireUnlocked(walletId);
    PinHmac stored = credentials.find(walletId).orElseThrow(PinService::notSet);
    CryptoPinVerified r = crypto.verifyPin(walletId, pin, stored.hmac(), stored.hmacKeyId());
    if (!r.match()) {
      throw failed(walletId);
    }
    credentials.resetFailures(walletId);
    if (r.rehash() != null) {
      credentials.rehash(walletId, r.rehash());
    }
  }

  /** The wallet's locks in force: ADMIN 403 MW-AUTH-043, PIN 423 MW-AUTH-041 / MW-AUTH-042. */
  public void requireUnlocked(String walletId) {
    Instant now = clock.instant();
    for (CredentialRepository.Lock lock : credentials.locks(walletId)) {
      if (ADMIN.equals(lock.kind())) {
        throw walletLocked();
      }
      if (lock.activeAt(now)) {
        throw pinLocked(lock, now);
      }
    }
  }

  /** The answer to a PIN lock in force at {@code now}: 423 MW-AUTH-042 without an end, else MW-AUTH-041. */
  static MwException pinLocked(CredentialRepository.Lock lock, Instant now) {
    if (lock.until() == null) {
      return new MwException(ErrorCodes.AUTH_PIN_LOCKED_PERMANENT, "PIN locked; reset your PIN")
          .withStatus(HttpStatus.LOCKED);
    }
    long retryAfter = Math.max(1, Duration.between(now, lock.until()).toSeconds());
    return new MwException(ErrorCodes.AUTH_PIN_LOCKED, "PIN locked; try again later")
        .withStatus(HttpStatus.LOCKED)
        .withDetails(List.of(new ErrorDetail("retryAfter", Long.toString(retryAfter))));
  }

  static MwException walletLocked() {
    return new MwException(ErrorCodes.AUTH_WALLET_LOCKED, "Wallet locked").withStatus(HttpStatus.FORBIDDEN);
  }

  /** Counts a wrong PIN and escalates; the error to answer. */
  private MwException failed(String walletId) {
    PinProperties.Lock cfg = props.lock();
    MwException answer = tx.execute(status -> {
      Instant now = clock.instant();
      credentials.lockWallet(walletId);
      Optional<CredentialRepository.Lock> current = credentials.lock(walletId, PIN);
      if (current.isPresent() && current.get().activeAt(now)) {
        // a parallel wrong PIN locked it meanwhile: not counted towards the next level
        return pinLocked(current.get(), now);
      }
      int failures = credentials.countFailure(walletId, now);
      if (failures < cfg.tries()) {
        log.atInfo().addKeyValue("event", "auth.pin.wrong").addKeyValue("errorCode", ErrorCodes.AUTH_WRONG_PIN)
            .addKeyValue("failures", failures).log("Wrong PIN");
        return new MwException(ErrorCodes.AUTH_WRONG_PIN, "Wrong PIN")
            .withDetails(List.of(new ErrorDetail("triesLeft", Integer.toString(cfg.tries() - failures))));
      }
      int previous = current.filter(l -> l.lockedAt() != null && l.lockedAt().plus(cfg.decay()).isAfter(now))
          .map(CredentialRepository.Lock::level).orElse(0);
      int level = Math.min(previous + 1, 3);
      Duration d = cfg.duration(level);
      Instant until = d == null ? null : now.plus(d);
      credentials.putLock(walletId, PIN, level, until, "WRONG_PIN", AuditEventMapper.SERVICE, now);
      credentials.resetFailures(walletId);
      audit.record("auth.pin.locked", walletId, AuditEventMapper.SYSTEM, "SUCCESS",
          previous == 0 ? null : Map.of("level", previous),
          until == null ? Map.of("level", level, "permanent", true)
              : Map.of("level", level, "until", until.toString()));
      log.atWarn().addKeyValue("event", "auth.pin.locked").addKeyValue("level", level)
          .addKeyValue("errorCode", until == null ? ErrorCodes.AUTH_PIN_LOCKED_PERMANENT : ErrorCodes.AUTH_PIN_LOCKED)
          .log("PIN locked after {} wrong PINs", cfg.tries());
      return pinLocked(new CredentialRepository.Lock(PIN, level, until, now), now);
    });
    return answer;
  }

  /**
   * Lifts the PIN lock (with its level history) and the wrong-PIN count: a PIN reset, an ops UNLOCK.
   * Call inside the change's transaction; true when there was a PIN lock row.
   */
  public boolean clearPinLock(String walletId) {
    credentials.resetFailures(walletId);
    return credentials.removeLock(walletId, PIN);
  }
}
