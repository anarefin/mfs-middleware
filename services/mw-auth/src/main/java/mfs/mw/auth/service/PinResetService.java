package mfs.mw.auth.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import mfs.mw.auth.client.CoreClient;
import mfs.mw.auth.client.CryptoClient;
import mfs.mw.auth.config.PinProperties;
import mfs.mw.auth.dto.CarePinReset;
import mfs.mw.auth.dto.CarePinResetRequest;
import mfs.mw.auth.dto.CryptoTokens;
import mfs.mw.auth.dto.EncryptedPin;
import mfs.mw.auth.dto.ErrorDetail;
import mfs.mw.auth.dto.PinHmac;
import mfs.mw.auth.dto.PinResetRequest;
import mfs.mw.auth.dto.PinResetView;
import mfs.mw.auth.dto.SignedRequest;
import mfs.mw.auth.entity.AuthDevice;
import mfs.mw.auth.entity.AuthWallet;
import mfs.mw.auth.entity.PinReset;
import mfs.mw.auth.exception.ErrorCodes;
import mfs.mw.auth.exception.MwException;
import mfs.mw.auth.mapper.AuditEventMapper;
import mfs.mw.auth.repository.CredentialRepository;
import mfs.mw.auth.repository.DeviceRepository;
import mfs.mw.auth.repository.DeviceSessionStore;
import mfs.mw.auth.repository.PinResetRepository;
import mfs.mw.auth.repository.RefreshTokenRepository;
import mfs.mw.auth.util.UuidV7;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * PIN reset (spec 18.7, ticket 70).
 *
 * <ul>
 *   <li>Self-service ({@link #start} with the KYC factors): signed by the bound device, its {@code
 *       RESET} OTP; the factors tokenised by mw-crypto under TOKENISE_NID (each bound to the wallet,
 *       {@code <walletId>:<factor>}); the reset opens {@code PENDING_KYC}, then, after that commit and
 *       off the request thread, the core checks the factors over REST ({@code POST
 *       /core/v1/customers/{walletId}/verify-factors}, ticket 95b, ADR D31; {@link CoreClient}, asked
 *       again on no definite answer) and its answer ({@link #kycAnswered}) moves the reset to {@code
 *       VERIFIED} or {@code REJECTED}. No answer at all leaves it {@code PENDING_KYC} until it expires
 *       (the customer starts again).
 *   <li>Care ({@link #approveCare}, mw-admin-api after maker-checker): a reset {@code APPROVED}
 *       without a device, the customer told by SMS; {@link #start} without factors and unsigned, with
 *       the {@code RESET} OTP of the device in hand (a lost phone: any device), moves it to {@code
 *       VERIFIED} for that device.
 *   <li>{@link #setPin}: only the reset's device, only {@code VERIFIED}: the PIN policy, the credential
 *       replaced (the old PIN kept for the reuse rule), the PIN lock and count cleared, {@code DONE},
 *       every session and refresh token of the wallet ended, audited, an SMS.
 * </ul>
 *
 * A reset's id is random (UUIDv4): only the device that opened it learns it. The factors are never
 * logged or stored.
 */
@Service
public class PinResetService {

  static final String COMMAND_PREFIX = "pin-reset:";
  /** The executor of the core's factors checks (virtual threads, {@code config/CoreClientConfig}). */
  public static final String KYC_EXECUTOR = "kycCheckExecutor";
  private static final Logger log = LoggerFactory.getLogger(PinResetService.class);

  private final WalletAccess wallets;
  private final DeviceRepository devices;
  private final SignedRequestCheck signed;
  private final DeviceOtpService otps;
  private final PinResetRepository resets;
  private final PinService pins;
  private final PinVerifier verifier;
  private final CredentialRepository credentials;
  private final RefreshTokenRepository refresh;
  private final DeviceSessionStore sessions;
  private final CryptoClient crypto;
  private final CoreClient core;
  private final Executor kycChecks;
  private final AuditTrail audit;
  private final PinNotices notices;
  private final PinProperties props;
  private final TransactionTemplate tx;
  private final Clock clock;

  @SuppressWarnings("java:S107")
  public PinResetService(
      WalletAccess wallets,
      DeviceRepository devices,
      SignedRequestCheck signed,
      DeviceOtpService otps,
      PinResetRepository resets,
      PinService pins,
      PinVerifier verifier,
      CredentialRepository credentials,
      RefreshTokenRepository refresh,
      DeviceSessionStore sessions,
      CryptoClient crypto,
      CoreClient core,
      @Qualifier(KYC_EXECUTOR) Executor kycChecks,
      AuditTrail audit,
      PinNotices notices,
      PinProperties props,
      TransactionTemplate tx,
      Clock clock) {
    this.wallets = wallets;
    this.devices = devices;
    this.signed = signed;
    this.otps = otps;
    this.resets = resets;
    this.pins = pins;
    this.verifier = verifier;
    this.credentials = credentials;
    this.refresh = refresh;
    this.sessions = sessions;
    this.crypto = crypto;
    this.core = core;
    this.kycChecks = kycChecks;
    this.audit = audit;
    this.notices = notices;
    this.props = props;
    this.tx = tx;
    this.clock = clock;
  }

  /** {@code POST /auth/v1/pin-resets}: self-service with the factors, else taking up a care reset. */
  public PinResetView start(PinResetRequest body, SignedRequest req) {
    String deviceId = SignedRequestCheck.deviceId(req.deviceId());
    String msisdn = WalletAccess.msisdn(body.msisdn());
    AuthWallet w = wallets.byMsisdn(msisdn).orElseThrow(PinResetService::unknownWallet);
    wallets.checkActive(w);
    return body.selfService() ? startSelf(w, deviceId, body, req) : takeUpCare(w, deviceId, body.otp());
  }

  private PinResetView startSelf(AuthWallet w, String deviceId, PinResetRequest body, SignedRequest req) {
    if (body.nidLast4() == null || body.dateOfBirth() == null) {
      throw invalid("nidLast4", "nidLast4 and dateOfBirth go together");
    }
    String walletId = w.walletId();
    AuthDevice d = devices.findBoundDevice(walletId, deviceId).orElseThrow(LoginService::deviceMismatch);
    signed.verify(RequestSignatures.parsePublicKey(d.publicKey()), req);
    Instant now = clock.instant();
    resets.open(walletId)
        .filter(r -> PinReset.CARE.equals(r.kind()) && now.isBefore(r.expiresAt()))
        .ifPresent(r -> {
          throw new MwException(ErrorCodes.VAL_RESET_OPEN, "An approved care reset is open: use it")
              .withStatus(HttpStatus.CONFLICT);
        });
    DeviceOtpService.Checked checked = otps.check(walletId, deviceId, body.otp(), DeviceOtpService.RESET);
    CryptoTokens t = crypto.tokeniseNid(List.of(walletId + ":" + body.nidLast4(), walletId + ":" + body.dateOfBirth()));
    UUID id = UUID.randomUUID();
    String txnId = UuidV7.at(now).toString();
    String commandId = COMMAND_PREFIX + id;
    PinReset reset = new PinReset(id, walletId, PinReset.SELF, PinReset.PENDING_KYC, deviceId,
        now.plus(props.reset().ttl()), null, null);
    inTx(() -> {
      otps.consume(walletId, deviceId, checked);
      resets.expire(walletId, PinReset.SELF, now);
      resets.insert(reset, "wallet", commandId, now);
      audit.record("auth.pin_reset.requested", walletId, AuditEventMapper.wallet(walletId), "SUCCESS", null,
          Map.of("kind", PinReset.SELF, "status", PinReset.PENDING_KYC));
    });
    log.atInfo().addKeyValue("event", "auth.pin_reset.requested").addKeyValue("txnId", txnId)
        .log("Self-service PIN reset opened on device {}", deviceId);
    kycChecks.execute(() -> kycAnswered(id, walletId, txnId, core.verifyFactors(walletId, txnId, id, t)));
    return view(reset, now);
  }

  private PinResetView takeUpCare(AuthWallet w, String deviceId, String otp) {
    String walletId = w.walletId();
    Instant now = clock.instant();
    PinReset care = resets.open(walletId)
        .filter(r -> PinReset.CARE.equals(r.kind()) && PinReset.APPROVED.equals(r.status()) && now.isBefore(r.expiresAt()))
        .orElseThrow(() -> new MwException(ErrorCodes.AUTH_FORBIDDEN, "No approved PIN reset for this wallet")
            .withStatus(HttpStatus.FORBIDDEN));
    DeviceOtpService.Checked checked = otps.check(walletId, deviceId, otp, DeviceOtpService.RESET);
    Instant expiresAt = now.plus(props.reset().ttl());
    inTx(() -> {
      otps.consume(walletId, deviceId, checked);
      if (!resets.takeUp(care.id(), deviceId, expiresAt, now)) {
        throw notReady();
      }
      audit.record("auth.pin_reset.verified", walletId, AuditEventMapper.wallet(walletId), "SUCCESS",
          Map.of("status", PinReset.APPROVED), Map.of("kind", PinReset.CARE, "status", PinReset.VERIFIED));
    });
    log.atInfo().addKeyValue("event", "auth.pin_reset.verified").log("Care PIN reset taken up on device {}", deviceId);
    return view(new PinReset(care.id(), walletId, PinReset.CARE, PinReset.VERIFIED, deviceId, expiresAt,
        care.changeRequestId(), null), now);
  }

  /** {@code GET /auth/v1/pin-resets/{id}}: only for the reset's device (else 404). */
  public PinResetView get(UUID id, String deviceIdIn) {
    return view(own(id, SignedRequestCheck.deviceId(deviceIdIn)), clock.instant());
  }

  /** {@code PUT /auth/v1/pin-resets/{id}/pin}: see the class comment. */
  public PinResetView setPin(UUID id, String deviceIdIn, EncryptedPin pin) {
    PinReset r = own(id, SignedRequestCheck.deviceId(deviceIdIn));
    Instant now = clock.instant();
    if (!PinReset.VERIFIED.equals(r.statusAt(now))) {
      throw notReady();
    }
    AuthWallet w = wallets.byId(r.walletId()).orElseThrow(PinResetService::unknownWallet);
    wallets.checkActive(w);
    String msisdn = wallets.msisdnOf(w);
    PinHmac hmac = pins.newPin(w, msisdn, pin);
    String walletId = w.walletId();
    inTx(() -> {
      if (!resets.move(id, PinReset.VERIFIED, PinReset.DONE, null, now)) {
        throw notReady();
      }
      var before = credentials.credential(walletId);
      credentials.replace(walletId, hmac, pins.historyAfter(before), now);
      boolean wasLocked = verifier.clearPinLock(walletId);
      audit.record("auth.pin_reset.done", walletId, AuditEventMapper.wallet(walletId), "SUCCESS",
          Map.of("status", PinReset.VERIFIED, "pinLocked", wasLocked), Map.of("kind", r.kind(), "status", PinReset.DONE));
      notices.pinChanged(msisdn);
    });
    endSessions(walletId);
    log.atInfo().addKeyValue("event", "auth.pin_reset.done").log("PIN reset done on device {}", r.deviceId());
    return view(new PinReset(id, walletId, r.kind(), PinReset.DONE, r.deviceId(), r.expiresAt(), r.changeRequestId(),
        null), now);
  }

  /**
   * The core's answer to the factors check: {@code PENDING_KYC} -> {@code VERIFIED} or {@code REJECTED}
   * (the reason the core's), audited; a reset no longer pending (expired, replaced) changes nothing; no
   * definite answer leaves it pending until it expires.
   */
  void kycAnswered(UUID resetId, String walletId, String txnId, CoreClient.FactorsAnswer a) {
    if (a.outcome() == CoreClient.Outcome.UNKNOWN) {
      log.atWarn().addKeyValue("event", "auth.pin_reset.kyc_unknown").addKeyValue("txnId", txnId)
          .log("No answer of the core to a PIN reset's factors; it stays pending until it expires");
      return;
    }
    boolean verified = a.outcome() == CoreClient.Outcome.VERIFIED;
    String to = verified ? PinReset.VERIFIED : PinReset.REJECTED;
    Instant now = clock.instant();
    boolean moved = Boolean.TRUE.equals(tx.execute(status -> {
      if (!resets.move(resetId, PinReset.PENDING_KYC, to, verified ? null : a.reason(), now)) {
        return false;
      }
      audit.record("auth.pin_reset." + (verified ? "verified" : "rejected"), walletId, AuditEventMapper.SYSTEM,
          verified ? "SUCCESS" : "FAILURE", Map.of("status", PinReset.PENDING_KYC),
          verified ? Map.of("status", to) : Map.of("status", to, "reason", String.valueOf(a.reason())));
      return true;
    }));
    if (!moved) {
      log.atInfo().addKeyValue("event", "auth.pin_reset.late_answer").addKeyValue("txnId", txnId)
          .log("Core answer for a PIN reset no longer pending");
      return;
    }
    log.atInfo().addKeyValue("event", "auth.pin_reset." + (verified ? "verified" : "rejected")).addKeyValue("txnId", txnId)
        .addKeyValue("errorCode", a.reason()).log("Core checked the KYC factors of a PIN reset");
  }

  /** The answer of {@code POST /internal/v1/pin-resets}: the reset and whether it is new. */
  public record Care(CarePinReset reset, boolean created) {}

  /** {@code POST /internal/v1/pin-resets} (mw-admin-api, after approval): idempotent on the change request. */
  public Care approveCare(CarePinResetRequest req) {
    Instant now = clock.instant();
    var existing = resets.findByChangeRequest(req.changeRequestId());
    if (existing.isPresent()) {
      PinReset r = existing.get();
      return new Care(new CarePinReset(r.id().toString(), r.statusAt(now), r.expiresAt()), false);
    }
    AuthWallet w = wallets.byId(req.walletId())
        .orElseThrow(() -> new MwException(ErrorCodes.VAL_NOT_FOUND, "Wallet not known").withStatus(HttpStatus.NOT_FOUND));
    String msisdn = wallets.msisdnOf(w);
    PinReset reset = new PinReset(UUID.randomUUID(), w.walletId(), PinReset.CARE, PinReset.APPROVED, null,
        now.plus(props.reset().careTtl()), req.changeRequestId(), null);
    try {
      inTx(() -> {
        resets.expire(w.walletId(), PinReset.SELF, now);
        resets.expire(w.walletId(), PinReset.CARE, now);
        resets.insert(reset, req.makerId(), null, now);
        audit.record("auth.pin_reset.approved", w.walletId(), AuditEventMapper.user(req.checkerId()), "SUCCESS", null,
            Map.of("kind", PinReset.CARE, "status", PinReset.APPROVED, "changeRequestId", req.changeRequestId().toString(),
                "makerId", req.makerId(), "checkerId", req.checkerId()));
        notices.resetApproved(msisdn, req.locale());
      });
    } catch (DuplicateKeyException e) {
      // the same change request in parallel: answer the one that won
      PinReset r = resets.findByChangeRequest(req.changeRequestId()).orElseThrow(() -> e);
      return new Care(new CarePinReset(r.id().toString(), r.statusAt(now), r.expiresAt()), false);
    }
    log.atInfo().addKeyValue("event", "auth.pin_reset.approved").log("Care PIN reset approved");
    return new Care(new CarePinReset(reset.id().toString(), reset.status(), reset.expiresAt()), true);
  }

  /** Whether the wallet has an approved care reset waiting for its OTP (a RESET OTP on any device). */
  public boolean careWaiting(String walletId) {
    Instant now = clock.instant();
    return resets.open(walletId)
        .filter(r -> PinReset.CARE.equals(r.kind()) && PinReset.APPROVED.equals(r.status()) && now.isBefore(r.expiresAt()))
        .isPresent();
  }

  /** Every refresh token revoked and every bound device's session closed. */
  void endSessions(String walletId) {
    refresh.revokeWallet(walletId);
    devices.findBoundAll(walletId).forEach(d -> sessions.close(walletId, d.deviceId()));
  }

  private PinReset own(UUID id, String deviceId) {
    return resets.find(id).filter(r -> deviceId.equals(r.deviceId()))
        .orElseThrow(() -> new MwException(ErrorCodes.VAL_NOT_FOUND, "No such PIN reset").withStatus(HttpStatus.NOT_FOUND));
  }

  private void inTx(Runnable body) {
    tx.executeWithoutResult(status -> body.run());
  }

  private static PinResetView view(PinReset r, Instant now) {
    String status = r.statusAt(now);
    return new PinResetView(r.id().toString(), r.kind(), status, r.expiresAt(),
        PinReset.REJECTED.equals(status) ? r.reason() : null);
  }

  private static MwException notReady() {
    return new MwException(ErrorCodes.VAL_RESET_NOT_READY, "This PIN reset is not verified (or no longer open)")
        .withStatus(HttpStatus.CONFLICT);
  }

  private static MwException unknownWallet() {
    return new MwException(ErrorCodes.AUTH_FORBIDDEN, "Wallet not known").withStatus(HttpStatus.FORBIDDEN);
  }

  private static MwException invalid(String field, String issue) {
    return new MwException(ErrorCodes.VAL_INVALID, "Invalid request")
        .withStatus(HttpStatus.BAD_REQUEST)
        .withDetails(List.of(new ErrorDetail(field, issue)));
  }
}
