package mfs.mw.auth.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import mfs.mw.auth.client.CryptoClient;
import mfs.mw.auth.config.AtmTokenProperties;
import mfs.mw.auth.dto.AtmTokenIssued;
import mfs.mw.auth.dto.AtmTokenRequest;
import mfs.mw.auth.dto.AtmTokenVerification;
import mfs.mw.auth.dto.AtmTokenVerified;
import mfs.mw.auth.entity.AuthWallet;
import mfs.mw.auth.exception.ErrorCodes;
import mfs.mw.auth.exception.MwException;
import mfs.mw.auth.repository.AssertionCache;
import mfs.mw.auth.repository.AtmTokenRepository;
import mfs.mw.auth.util.TxnHash;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * The ATM cash-out token (ticket 120b2, FRS FL-11, UC-17).
 *
 * <p>Issue (callers {@code mw.auth.atm-token.issuers}, mw-app-gateway): the PIN step-up is the
 * transaction assertion the gateway got first from {@code /internal/v1/txn-authorisations} for
 * {@code ATM_CASH_OUT} wallet -> {@code PARTNER/<partnerId>} with the token's amount, currency,
 * idempotency key and channel. Here the live assertion cached for (wallet, idempotency key) must be
 * the one sent and bound to exactly that transaction, else 401 {@code MW-AUTH-049} and nothing is
 * stored. The step-up is spent by the issue (compare-and-delete in Redis), so one PIN entry issues
 * at most one token, also under concurrent calls. Then 6 random digits; only their HMAC under mw-crypto {@code HMAC_ATMTOKEN} over wallet,
 * amount, currency and digits is stored with the key version (one live token per wallet, a new one
 * replaces it), valid {@code ttl} (10 min).
 *
 * <p>Verify (callers {@code mw.auth.atm-token.verifiers}, mw-adapter-atm): the wallet by MSISDN
 * (unknown: 401 {@code MW-AUTH-011}, no oracle), active and not admin-locked ({@code
 * WalletAccess.checkActive}); the checks as the channel OTP's: used up 401 {@code MW-AUTH-012}, none,
 * spent, void or expired {@code 011}, a check reserved atomically, the HMAC checked by mw-crypto under
 * the stored version (another amount is a wrong token), wrong {@code 010} (the wrong one that uses the
 * last check voids it), right: spent once, answered with the wallet. The token is never logged.
 */
@Service
public class AtmTokenService {

  static final String TXN_TYPE = "ATM_CASH_OUT";
  private static final Logger log = LoggerFactory.getLogger(AtmTokenService.class);
  private static final SecureRandom RANDOM = new SecureRandom();

  private final WalletAccess wallets;
  private final AssertionCache assertions;
  private final AtmTokenRepository tokens;
  private final CryptoClient crypto;
  private final AtmTokenProperties props;
  private final Clock clock;

  public AtmTokenService(
      WalletAccess wallets,
      AssertionCache assertions,
      AtmTokenRepository tokens,
      CryptoClient crypto,
      AtmTokenProperties props,
      Clock clock) {
    this.wallets = wallets;
    this.assertions = assertions;
    this.tokens = tokens;
    this.crypto = crypto;
    this.props = props;
    this.clock = clock;
  }

  public AtmTokenIssued issue(String caller, AtmTokenRequest req) {
    requireCaller(caller, props.issuers().contains(caller));
    AuthWallet w = wallets.byId(req.walletId())
        .orElseThrow(() -> new MwException(ErrorCodes.AUTH_FORBIDDEN, "Unknown wallet").withStatus(HttpStatus.FORBIDDEN));
    wallets.check(w);
    Instant now = clock.instant();
    String txnHash = TxnHash.of(TXN_TYPE, req.amount(), req.currency(),
        new TxnHash.Party("WALLET", w.walletId()), new TxnHash.Party("PARTNER", props.partnerId()),
        req.idempotencyKey(), req.channel());
    Optional<AssertionCache.Entry> stepUp = assertions.find(w.walletId(), req.idempotencyKey());
    boolean ok = stepUp.isPresent()
        && now.isBefore(stepUp.get().expiresAt())
        && equal(stepUp.get().assertion(), req.assertion())
        && stepUp.get().txnHash().equals(txnHash)
        && assertions.consume(w.walletId(), req.idempotencyKey(), stepUp.get());
    if (!ok) {
      log.atWarn().addKeyValue("event", "auth.atm_token.step_up_refused")
          .addKeyValue("errorCode", ErrorCodes.AUTH_STEP_UP_REQUIRED).log("ATM token without a matching PIN step-up");
      throw new MwException(ErrorCodes.AUTH_STEP_UP_REQUIRED, "Confirm with your PIN first")
          .withStatus(HttpStatus.UNAUTHORIZED);
    }
    String token = String.format("%06d", RANDOM.nextInt(1_000_000));
    CryptoClient.Mac mac = crypto.signHmac(props.keyId(), data(w.walletId(), req.amount(), req.currency(), token));
    Instant expiresAt = now.plus(props.ttl());
    tokens.issue(w.walletId(), mac.mac(), mac.kid(), req.amount(), req.currency(), expiresAt, now);
    log.atInfo().addKeyValue("event", "auth.atm_token.issued").addKeyValue("channel", req.channel())
        .log("ATM token issued");
    return new AtmTokenIssued(token, expiresAt);
  }

  public AtmTokenVerified verify(String caller, AtmTokenVerification req) {
    requireCaller(caller, props.verifiers().contains(caller));
    AuthWallet w = wallets.byMsisdn(WalletAccess.msisdn(req.msisdn())).orElseThrow(AtmTokenService::expired);
    wallets.checkActive(w);
    String wallet = w.walletId();
    int max = props.maxAttempts();
    Instant now = clock.instant();
    AtmTokenRepository.Row current = tokens.find(wallet).orElseThrow(AtmTokenService::expired);
    if (current.attempts() >= max) {
      throw locked();
    }
    if (current.mac() == null || !now.isBefore(current.expiresAt())) {
      throw expired();
    }
    if (!tokens.reserveAttempt(wallet, current.mac(), max, now)) {
      throw refusedAfterRace(wallet);
    }
    if (!crypto.verifyHmac(current.macKid(), data(wallet, req.amount(), req.currency(), req.token()), current.mac())) {
      tokens.voidIfExhausted(wallet, current.mac(), max);
      log.atInfo().addKeyValue("event", "auth.atm_token.wrong").addKeyValue("errorCode", ErrorCodes.AUTH_OTP_WRONG)
          .log("Wrong ATM token");
      throw new MwException(ErrorCodes.AUTH_OTP_WRONG, "Wrong token").withStatus(HttpStatus.UNAUTHORIZED);
    }
    if (!tokens.consume(wallet, current.mac())) {
      throw expired();
    }
    log.atInfo().addKeyValue("event", "auth.atm_token.spent").log("ATM token spent");
    return new AtmTokenVerified(wallet);
  }

  /** The HMAC input: the token bound to wallet, amount and currency. */
  static byte[] data(String walletId, long amount, String currency, String token) {
    return ("ATM_TOKEN\n" + walletId + "\n" + amount + "\n" + currency + "\n" + token).getBytes(StandardCharsets.UTF_8);
  }

  private MwException refusedAfterRace(String wallet) {
    return tokens.find(wallet).map(r -> r.attempts() >= props.maxAttempts()).orElse(false) ? locked() : expired();
  }

  private static void requireCaller(String caller, boolean allowed) {
    if (caller == null || !allowed) {
      throw new MwException(ErrorCodes.AUTH_FORBIDDEN, "Not allowed").withStatus(HttpStatus.FORBIDDEN);
    }
  }

  private static boolean equal(String a, String b) {
    return a != null && b != null
        && MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
  }

  private static MwException expired() {
    return new MwException(ErrorCodes.AUTH_OTP_EXPIRED, "No valid token, make a new one in the app")
        .withStatus(HttpStatus.UNAUTHORIZED);
  }

  private static MwException locked() {
    return new MwException(ErrorCodes.AUTH_OTP_LOCKED, "Too many wrong tokens, make a new one in the app")
        .withStatus(HttpStatus.UNAUTHORIZED);
  }
}
