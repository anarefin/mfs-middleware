package mfs.mw.auth.service;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import mfs.mw.auth.client.CryptoClient;
import mfs.mw.auth.dto.CryptoPinDerived;
import mfs.mw.auth.dto.CryptoPinVerified;
import mfs.mw.auth.dto.EncryptedPin;
import mfs.mw.auth.dto.ErrorDetail;
import mfs.mw.auth.dto.PinHmac;
import mfs.mw.auth.exception.ErrorCodes;
import mfs.mw.auth.exception.MwException;
import mfs.mw.auth.repository.CredentialRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * The wallet PIN through mw-crypto (spec 18.7, 18.8, D20): mw-auth holds only the PIN HMAC; the
 * ciphertext goes to mw-crypto {@code /pin/verify} or {@code /pin/derive}. A wrong PIN is counted
 * ({@code auth.attempt}; the lock escalation is ticket 70) and answered 401 MW-AUTH-040; a match
 * resets the count and stores the rehash of a rotated key. Nothing about the PIN is logged.
 */
@Service
public class PinService {

  private static final Logger log = LoggerFactory.getLogger(PinService.class);

  private final CryptoClient crypto;
  private final CredentialRepository credentials;
  private final Clock clock;

  public PinService(CryptoClient crypto, CredentialRepository credentials, Clock clock) {
    this.crypto = crypto;
    this.credentials = credentials;
    this.clock = clock;
  }

  /** Verifies the wallet's PIN; 403 MW-AUTH-045 when it has none, 401 MW-AUTH-040 when wrong. */
  public void verify(String walletId, EncryptedPin pin) {
    PinHmac stored = credentials.find(walletId).orElseThrow(PinService::notSet);
    CryptoPinVerified r = crypto.verifyPin(walletId, pin, stored.hmac(), stored.hmacKeyId());
    if (!r.match()) {
      int failures = credentials.countFailure(walletId, clock.instant());
      log.atInfo().addKeyValue("event", "auth.pin.wrong").addKeyValue("errorCode", ErrorCodes.AUTH_WRONG_PIN)
          .addKeyValue("failures", failures).log("Wrong PIN");
      throw new MwException(ErrorCodes.AUTH_WRONG_PIN, "Wrong PIN");
    }
    credentials.resetFailures(walletId);
    if (r.rehash() != null) {
      credentials.rehash(walletId, r.rehash());
    }
  }

  /**
   * The HMAC to store for a new PIN; 400 MW-AUTH-044 naming the rule when mw-crypto finds it weak
   * ({@code policyInput}: the rule inputs, none until ticket 70).
   */
  public PinHmac derive(String walletId, EncryptedPin pin, Map<String, Object> policyInput) {
    CryptoPinDerived d = crypto.derivePin(walletId, pin, policyInput);
    if (d.weak()) {
      throw new MwException(ErrorCodes.AUTH_WEAK_PIN, "This PIN is too easy to guess; choose another")
          .withStatus(HttpStatus.BAD_REQUEST)
          .withDetails(List.of(new ErrorDetail("encryptedPin", d.rule())));
    }
    return new PinHmac(d.hmac(), d.hmacKeyId());
  }

  static MwException notSet() {
    return new MwException(ErrorCodes.AUTH_PIN_NOT_SET, "No PIN set; sign in with an OTP and set one")
        .withStatus(HttpStatus.FORBIDDEN);
  }
}
