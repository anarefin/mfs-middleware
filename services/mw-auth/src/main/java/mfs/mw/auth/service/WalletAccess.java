package mfs.mw.auth.service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import mfs.mw.auth.client.CryptoClient;
import mfs.mw.auth.dto.CryptoEnvelope;
import mfs.mw.auth.dto.ErrorDetail;
import mfs.mw.auth.entity.AuthWallet;
import mfs.mw.auth.exception.ErrorCodes;
import mfs.mw.auth.exception.MwException;
import mfs.mw.auth.repository.CredentialRepository;
import mfs.mw.auth.repository.WalletRepository;
import mfs.mw.auth.util.Msisdn;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Finding a wallet by its MSISDN and the checks every login-like request makes first (spec 18.7):
 * the projection's lookup key is the mw-crypto keyed hash of the canonical MSISDN; the wallet must be
 * ACTIVE at the core (else 403 MW-AUTH-043) and not locked: an ADMIN lock is 403 MW-AUTH-043, a PIN
 * lock 423 MW-AUTH-041 with {@code retryAfter} (seconds) or MW-AUTH-042 when it has no end.
 */
@Service
public class WalletAccess {

  private final WalletRepository wallets;
  private final CredentialRepository credentials;
  private final CryptoClient crypto;
  private final PinVerifier pins;
  private final JsonMapper json;

  public WalletAccess(
      WalletRepository wallets, CredentialRepository credentials, CryptoClient crypto, PinVerifier pins, JsonMapper json) {
    this.wallets = wallets;
    this.credentials = credentials;
    this.crypto = crypto;
    this.pins = pins;
    this.json = json;
  }

  /** The canonical MSISDN ({@code 01XXXXXXXXX}); 400 MW-VAL-001 when it is not one. */
  public static String msisdn(String value) {
    String m = Msisdn.canonical(value);
    if (m == null) {
      throw new MwException(ErrorCodes.VAL_INVALID, "Invalid request")
          .withStatus(HttpStatus.BAD_REQUEST)
          .withDetails(List.of(new ErrorDetail("msisdn", "not an MSISDN")));
    }
    return m;
  }

  /** The projected wallet of a canonical MSISDN. */
  public Optional<AuthWallet> byMsisdn(String msisdn) {
    return wallets.findByMsisdnToken(crypto.tokeniseMsisdn(msisdn).token());
  }

  public Optional<AuthWallet> byId(String walletId) {
    return wallets.find(walletId);
  }

  public boolean hasPin(String walletId) {
    return credentials.find(walletId).isPresent();
  }

  /** Active at the core and not locked; see the class comment for the codes. */
  public void check(AuthWallet w) {
    checkActive(w);
    pins.requireUnlocked(w.walletId());
  }

  /**
   * Active at the core and no ADMIN lock (403 MW-AUTH-043); a PIN lock is let through: a PIN reset
   * is what lifts it (ticket 70).
   */
  public void checkActive(AuthWallet w) {
    if (!"ACTIVE".equals(w.status())) {
      throw walletLocked();
    }
    if (credentials.lock(w.walletId(), PinVerifier.ADMIN).isPresent()) {
      throw walletLocked();
    }
  }

  /** The wallet's MSISDN, decrypted from its mw-crypto envelope (the SMS and the PIN policy need it). */
  public String msisdnOf(AuthWallet w) {
    CryptoEnvelope env = json.readValue(w.msisdnEnc(), CryptoEnvelope.class);
    return new String(crypto.decrypt(env), StandardCharsets.UTF_8);
  }

  private static MwException walletLocked() {
    return PinVerifier.walletLocked();
  }
}
