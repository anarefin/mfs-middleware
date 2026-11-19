package mfs.mw.auth.service;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import mfs.mw.auth.dto.AuditEvent;
import mfs.mw.auth.dto.CarePinResetRequest;
import mfs.mw.auth.dto.WalletLockCommand;
import mfs.mw.auth.mapper.AuditEventMapper;
import mfs.mw.auth.mapper.WalletLockMapper;
import mfs.mw.auth.messaging.Inbox;
import mfs.mw.auth.repository.CredentialRepository;
import mfs.mw.auth.repository.WalletRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * {@code auth.wallet-lock.v1} (spec 18.7 Revocation, ticket 70), once per commandId (inbox, consumer
 * {@value #CONSUMER}):
 *
 * <ul>
 *   <li>{@code LOCK}: an ADMIN lock (login, binding, PIN checks and assertions 403 MW-AUTH-043), every
 *       refresh token revoked, the session closed;
 *   <li>{@code UNLOCK}: the ADMIN lock and the PIN lock (every level, the count) removed (the ops
 *       unlock of ticket 52 lands here);
 *   <li>{@code REVOKE_SESSIONS}: every refresh token revoked, the session closed, nothing locked;
 *   <li>{@code PIN_RESET}: an approved care PIN reset ({@link PinResetService#approveCare}, idempotent
 *       on the change request id instead of the inbox), written by mw-admin-api in the approval's
 *       transaction (security review of ticket 70: a reject that wins the race opens nothing).
 * </ul>
 *
 * Each is audited. A wallet this service does not know is skipped (logged). The Redis session is
 * closed on every delivery (idempotent), so a retry after a Redis failure still closes it. A
 * malformed record is MW-VAL-002 (retry topics, then the DLQ).
 */
@Service
public class WalletLockService {

  public static final String CONSUMER = "mw-auth.wallet-lock";
  private static final Logger log = LoggerFactory.getLogger(WalletLockService.class);

  private final WalletRepository wallets;
  private final CredentialRepository credentials;
  private final PinVerifier verifier;
  private final PinResetService resets;
  private final AuditTrail audit;
  private final Inbox inbox;
  private final WalletLockMapper mapper;
  private final Clock clock;

  public WalletLockService(
      WalletRepository wallets,
      CredentialRepository credentials,
      PinVerifier verifier,
      PinResetService resets,
      AuditTrail audit,
      Inbox inbox,
      WalletLockMapper mapper,
      Clock clock) {
    this.wallets = wallets;
    this.credentials = credentials;
    this.verifier = verifier;
    this.resets = resets;
    this.audit = audit;
    this.inbox = inbox;
    this.mapper = mapper;
    this.clock = clock;
  }

  /** Handles one record body. */
  public void handle(String body) {
    WalletLockCommand cmd = mapper.read(body);
    if (wallets.find(cmd.walletId()).isEmpty()) {
      log.atWarn().addKeyValue("event", "auth.wallet_lock.unknown_wallet").addKeyValue("action", cmd.action())
          .log("Wallet lock command for a wallet not projected; skipped");
      return;
    }
    if (WalletLockCommand.PIN_RESET.equals(cmd.action())) {
      // idempotent on the change request id (a redelivery answers the reset already open)
      PinResetService.Care care = resets.approveCare(new CarePinResetRequest(cmd.walletId(),
          java.util.UUID.fromString(cmd.changeRequestId()), cmd.makerId(), cmd.actor().id(), cmd.reason(), null));
      log.atInfo().addKeyValue("event", care.created() ? "auth.wallet_lock.applied" : "auth.wallet_lock.duplicate")
          .addKeyValue("action", cmd.action()).log("Care PIN reset command handled");
      return;
    }
    boolean applied = inbox.processOnce(CONSUMER, cmd.commandId(), () -> apply(cmd));
    if (!WalletLockCommand.UNLOCK.equals(cmd.action())) {
      resets.endSessions(cmd.walletId());
    }
    log.atInfo().addKeyValue("event", applied ? "auth.wallet_lock.applied" : "auth.wallet_lock.duplicate")
        .addKeyValue("action", cmd.action()).log("Wallet lock command handled");
  }

  private void apply(WalletLockCommand cmd) {
    String walletId = cmd.walletId();
    Instant now = clock.instant();
    AuditEvent.Actor actor = "system".equals(cmd.actor().type())
        ? new AuditEvent.Actor("system", cmd.actor().id()) : AuditEventMapper.user(cmd.actor().id());
    Map<String, Object> after = new LinkedHashMap<>();
    after.put("action", cmd.action());
    after.put("reason", cmd.reason());
    if (cmd.changeRequestId() != null) {
      after.put("changeRequestId", cmd.changeRequestId());
    }
    switch (cmd.action()) {
      case WalletLockCommand.LOCK -> {
        credentials.putLock(walletId, PinVerifier.ADMIN, 1, null, cmd.reason(), cmd.actor().id(), now);
        audit.record("auth.wallet.locked", walletId, actor, "SUCCESS", null, after);
      }
      case WalletLockCommand.UNLOCK -> {
        Optional<CredentialRepository.Lock> pin = credentials.lock(walletId, PinVerifier.PIN);
        boolean admin = credentials.removeLock(walletId, PinVerifier.ADMIN);
        verifier.clearPinLock(walletId);
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("adminLock", admin);
        before.put("pinLockLevel", pin.map(CredentialRepository.Lock::level).orElse(0));
        audit.record("auth.wallet.unlocked", walletId, actor, "SUCCESS", before, after);
      }
      default -> audit.record("auth.wallet.sessions_revoked", walletId, actor, "SUCCESS", null, after);
    }
  }
}
