package mfs.mw.auth.service;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import mfs.mw.auth.client.CryptoClient;
import mfs.mw.auth.config.AssertionProperties;
import mfs.mw.auth.dto.ErrorDetail;
import mfs.mw.auth.dto.TxnAuthorisation;
import mfs.mw.auth.dto.TxnAuthorisationRequest;
import mfs.mw.auth.entity.AuthDevice;
import mfs.mw.auth.entity.AuthWallet;
import mfs.mw.auth.exception.ErrorCodes;
import mfs.mw.auth.exception.MwException;
import mfs.mw.auth.repository.AssertionCache;
import mfs.mw.auth.repository.DeviceRepository;
import mfs.mw.auth.util.TxnHash;
import mfs.mw.auth.util.UuidV7;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * The transaction authorisation assertion (spec 18.7, D22; ticket 71). A channel sends the PIN block
 * and the transaction before it publishes {@code mw.txn.request.v1}; in this order: the caller is a
 * channel ({@code mw.auth.assertion.callers}, else 403 MW-AUTH-002), the wallet (by id or MSISDN,
 * exactly one) is known, ACTIVE and not locked ({@link WalletAccess#check}), an app's device is the
 * wallet's bound device (401 MW-AUTH-047), then the idempotency key: a live assertion for the same
 * transaction is returned again without a second PIN check, one for another transaction is 409
 * MW-IDEM-001. Otherwise the PIN ({@link PinVerification}: wrong 401 MW-AUTH-040, counted) and a
 * compact JWS by mw-crypto {@code ASSERTION_SIGN} (ES256, kid {@code <keyId>.v<n>}), header {@code
 * typ txn-assertion+jwt}, claims {@code iss}, {@code aud}, {@code sub} walletId, {@code jti}
 * (UUIDv7), {@code iat}, {@code exp} (iat + 60 s), {@code txnHash} ({@link TxnHash}), {@code
 * channel}, {@code authMethod} PIN, {@code deviceId} (app only), {@code newDevice} (the wallet's
 * device bound less than 24 h ago). Nothing about the PIN or the assertion is logged; the event
 * {@code auth.txn.authorised} carries the jti only.
 */
@Service
public class TxnAuthorisationService {

  static final String TYP = "txn-assertion+jwt";
  static final String PURPOSE = "ASSERTION_SIGN";
  static final String ALG = "ES256";
  private static final Logger log = LoggerFactory.getLogger(TxnAuthorisationService.class);

  private final WalletAccess wallets;
  private final DeviceRepository devices;
  private final PinVerification pins;
  private final AssertionCache cache;
  private final CryptoClient crypto;
  private final AssertionProperties props;
  private final Clock clock;

  public TxnAuthorisationService(
      WalletAccess wallets,
      DeviceRepository devices,
      PinVerification pins,
      AssertionCache cache,
      CryptoClient crypto,
      AssertionProperties props,
      Clock clock) {
    this.wallets = wallets;
    this.devices = devices;
    this.pins = pins;
    this.cache = cache;
    this.crypto = crypto;
    this.props = props;
    this.clock = clock;
  }

  public TxnAuthorisation authorise(String caller, TxnAuthorisationRequest req) {
    if (caller == null || !props.callers().contains(caller)) {
      throw new MwException(ErrorCodes.AUTH_FORBIDDEN, "Not allowed").withStatus(HttpStatus.FORBIDDEN);
    }
    AuthWallet w = wallet(req);
    wallets.check(w);
    Optional<AuthDevice> bound = devices.findBound(w.walletId());
    if (req.deviceId() != null && bound.map(d -> !d.deviceId().equals(req.deviceId())).orElse(true)) {
      throw new MwException(ErrorCodes.AUTH_DEVICE_MISMATCH, "Device not bound to this wallet")
          .withStatus(HttpStatus.UNAUTHORIZED);
    }
    TxnAuthorisationRequest.Txn t = req.txn();
    String txnHash = TxnHash.of(t.txnType(), t.amount(), t.currency(),
        new TxnHash.Party(t.payer().type(), t.payer().id()), new TxnHash.Party(t.payee().type(), t.payee().id()),
        t.idempotencyKey(), req.channel());
    Instant now = clock.instant();
    Optional<AssertionCache.Entry> cached = cache.find(w.walletId(), t.idempotencyKey());
    boolean live = cached.isPresent() && now.isBefore(cached.get().expiresAt());
    if (live) {
      return reuse(cached.get(), txnHash);
    }

    pins.verify(w.walletId(), req.encryptedPin());

    boolean newDevice = bound.map(d -> d.boundAt().isAfter(now.minus(props.newDeviceWindow()))).orElse(false);
    long iat = now.getEpochSecond();
    long exp = iat + props.ttl().toSeconds();
    String jti = UuidV7.at(now).toString();
    Map<String, Object> claims = new LinkedHashMap<>();
    claims.put("iss", props.issuer());
    claims.put("aud", props.audience());
    claims.put("sub", w.walletId());
    claims.put("jti", jti);
    claims.put("iat", iat);
    claims.put("exp", exp);
    claims.put("txnHash", txnHash);
    claims.put("channel", req.channel());
    claims.put("authMethod", "PIN");
    if (req.deviceId() != null) {
      claims.put("deviceId", req.deviceId());
    }
    claims.put("newDevice", newDevice);
    String jws = crypto.signJws(PURPOSE, ALG, Map.of("typ", TYP), claims).jws();
    AssertionCache.Entry entry = new AssertionCache.Entry(Instant.ofEpochSecond(exp), txnHash, newDevice, jws);
    AssertionCache.Entry stored;
    if (cached.isPresent()) {
      cache.replace(w.walletId(), t.idempotencyKey(), entry, props.ttl());
      stored = entry;
    } else {
      stored = cache.putIfAbsent(w.walletId(), t.idempotencyKey(), entry, props.ttl());
      if (stored != entry) {
        return reuse(stored, txnHash);
      }
    }
    log.atInfo()
        .addKeyValue("event", "auth.txn.authorised")
        .addKeyValue("jti", jti)
        .addKeyValue("channel", req.channel())
        .addKeyValue("newDevice", newDevice)
        .log("Transaction assertion issued");
    return new TxnAuthorisation(stored.assertion(), stored.expiresAt(), stored.newDevice());
  }

  private static TxnAuthorisation reuse(AssertionCache.Entry e, String txnHash) {
    if (!e.txnHash().equals(txnHash)) {
      throw new MwException(ErrorCodes.IDEM_CONFLICT, "Idempotency key already used for another transaction");
    }
    return new TxnAuthorisation(e.assertion(), e.expiresAt(), e.newDevice());
  }

  private AuthWallet wallet(TxnAuthorisationRequest req) {
    if ((req.walletId() == null) == (req.msisdn() == null)) {
      throw new MwException(ErrorCodes.VAL_INVALID, "Invalid request")
          .withStatus(HttpStatus.BAD_REQUEST)
          .withDetails(List.of(new ErrorDetail("walletId", "exactly one of walletId and msisdn is required")));
    }
    Optional<AuthWallet> w = req.walletId() != null
        ? wallets.byId(req.walletId())
        : wallets.byMsisdn(WalletAccess.msisdn(req.msisdn()));
    return w.orElseThrow(() -> new MwException(ErrorCodes.AUTH_FORBIDDEN, "Unknown wallet").withStatus(HttpStatus.FORBIDDEN));
  }
}
