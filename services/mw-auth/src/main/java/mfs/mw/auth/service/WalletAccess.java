package mfs.mw.auth.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import mfs.mw.auth.client.CryptoClient;
import mfs.mw.auth.dto.ErrorDetail;
import mfs.mw.auth.entity.AuthWallet;
import mfs.mw.auth.exception.ErrorCodes;
import mfs.mw.auth.exception.MwException;
import mfs.mw.auth.repository.CredentialRepository;
import mfs.mw.auth.repository.WalletRepository;
import mfs.mw.auth.util.Msisdn;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

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
  private final Clock clock;

  public WalletAccess(WalletRepository wallets, CredentialRepository credentials, CryptoClient crypto, Clock clock) {
    this.wallets = wallets;
    this.credentials = credentials;
    this.crypto = crypto;
    this.clock = clock;
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
    if (!"ACTIVE".equals(w.status())) {
      throw walletLocked();
    }
    Instant now = clock.instant();
    for (CredentialRepository.Lock lock : credentials.locks(w.walletId())) {
      if ("ADMIN".equals(lock.kind())) {
        throw walletLocked();
      }
      if (lock.until() == null) {
        throw new MwException(ErrorCodes.AUTH_PIN_LOCKED_PERMANENT, "PIN locked; reset your PIN")
            .withStatus(HttpStatus.LOCKED);
      }
      if (lock.until().isAfter(now)) {
        long retryAfter = Math.max(1, Duration.between(now, lock.until()).toSeconds());
        throw new MwException(ErrorCodes.AUTH_PIN_LOCKED, "PIN locked; try again later")
            .withStatus(HttpStatus.LOCKED)
            .withDetails(List.of(new ErrorDetail("retryAfter", Long.toString(retryAfter))));
      }
    }
  }

  private static MwException walletLocked() {
    return new MwException(ErrorCodes.AUTH_WALLET_LOCKED, "Wallet locked").withStatus(HttpStatus.FORBIDDEN);
  }
}
