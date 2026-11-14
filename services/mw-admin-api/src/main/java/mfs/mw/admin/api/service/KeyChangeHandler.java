package mfs.mw.admin.api.service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.Set;
import mfs.mw.admin.api.client.CryptoKeyClient;
import mfs.mw.admin.api.config.AdminApiProperties;
import mfs.mw.admin.api.dto.AdminRole;
import mfs.mw.admin.api.dto.CryptoKeys;
import mfs.mw.admin.api.dto.KeyChangeBody.KeyAction;
import mfs.mw.admin.api.dto.Operator;
import mfs.mw.admin.api.entity.ChangeRequest;
import mfs.mw.admin.api.exception.ErrorCodes;
import mfs.mw.admin.api.exception.MwException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Approval of a key change request (ticket 75, resourceType {@code key}, spec 18.8, OWN-08): the
 * checker is a security-admin who is not the maker (the shared rule) and signed in with MFA at most
 * {@code mw.admin-api.key-mfa-max-age} ago ({@link #checkDecider}, also on reject). The change
 * itself is mw-crypto's and is approved claim-first ({@link #claimFirst}): the request is APPROVING
 * before {@link #prepare} calls mw-crypto's key API (idempotent on the change request id), so a
 * parallel reject or expiry, which act on PENDING only, can never win after the call. A rejected or
 * expired ceremony has its staged key destroyed ({@link #closed}, best effort).
 */
@Component
public class KeyChangeHandler implements ResourceChangeHandler {

  private static final Logger log = LoggerFactory.getLogger(KeyChangeHandler.class);
  static final String SENT_AS = "mw-crypto";

  private final CryptoKeyClient crypto;
  private final AdminApiProperties props;

  public KeyChangeHandler(CryptoKeyClient crypto, AdminApiProperties props) {
    this.crypto = crypto;
    this.props = props;
  }

  @Override
  public String resourceType() {
    return ChangeRequest.KEY;
  }

  @Override
  public Set<AdminRole> checkers() {
    return AdminRole.KEY_ADMIN;
  }

  @Override
  public boolean claimFirst() {
    return true;
  }

  @Override
  public void checkDecider(ChangeRequest cr, Operator checker, Instant now) {
    requireFreshMfa(checker, now, props);
  }

  /** A ceremony is approved only once both components are in (no remote call: before the claim). */
  @Override
  public void precheck(ChangeRequest cr) {
    if (KeyAction.KEY_CEREMONY.name().equals(cr.action()) && cr.payload().get("checkValue") == null) {
      throw new MwException(ErrorCodes.VAL_CEREMONY_INCOMPLETE, "Both custodians must enter their components first")
          .withStatus(HttpStatus.CONFLICT);
    }
  }

  @Override
  public Approval prepare(ChangeRequest cr, Operator checker, Instant now, String correlationId) {
    throw new IllegalStateException("key changes are approved claim-first with an attempt id");
  }

  /**
   * The ceremony is committed under its own id (the change request's: the ceremony spans the
   * components); a key change is PUT under the approval attempt's id ({@code attemptId}, the claim
   * token), so a cancelled attempt never blocks a later approval of the same request.
   */
  @Override
  public Approval prepare(ChangeRequest cr, Operator checker, Instant now, String correlationId, UUID attemptId) {
    precheck(cr);
    KeyAction action = KeyAction.valueOf(cr.action());
    String keyId = String.valueOf(cr.payload().get("keyId"));
    CryptoKeys.Key key;
    if (action == KeyAction.KEY_CEREMONY) {
      key = crypto.commit(cr.id(), cr.makerId(), checker.username());
    } else {
      Object version = cr.payload().get("version");
      key = crypto.applyChange(
          attemptId, action.name().substring("KEY_".length()), keyId, (String) cr.payload().get("purpose"),
          version instanceof Number n ? n.intValue() : null, cr.makerId(), checker.username());
    }
    CryptoKeys.Version active = key.active();
    CryptoKeys.Version newest = key.newest();
    Map<String, Object> added = new LinkedHashMap<>();
    added.put("appliedKid", (active != null ? active : newest).kid());
    log.atInfo()
        .addKeyValue("event", "admin.key_change.applied")
        .addKeyValue("changeRequestId", cr.id().toString())
        .addKeyValue("attemptId", attemptId.toString())
        .addKeyValue("action", cr.action())
        .addKeyValue("keyId", keyId)
        .log("mw-crypto applied the key change");
    return new Approval(cr.payload(), SENT_AS, () -> added);
  }

  /**
   * Makes "not applied" binding at mw-crypto before a claim is given up: a key change attempt is
   * cancelled unless applied ({@code POST /internal/v1/key-changes/{attemptId}/cancel}); a ceremony is
   * aborted unless committed (abort is terminal; a 409 means committed, read back). Present = applied.
   */
  @Override
  public Optional<Map<String, Object>> settle(ChangeRequest cr, UUID attemptId) {
    if (KeyAction.KEY_CEREMONY.name().equals(cr.action())) {
      try {
        crypto.abort(cr.id());
        return Optional.empty();
      } catch (MwException e) {
        if (!e.status().is4xxClientError()) {
          throw e;
        }
        return crypto.ceremony(cr.id())
            .filter(c -> "COMMITTED".equals(c.state()))
            .map(c -> Map.<String, Object>of("appliedKid", c.kid()))
            .map(Optional::of)
            .orElseThrow(() -> e);
      }
    }
    CryptoKeys.ChangeState s = crypto.cancelKeyChange(attemptId);
    if (s.applied()) {
      return Optional.of(s.kid() == null ? Map.of() : Map.of("appliedKid", s.kid()));
    }
    if (s.cancelled()) {
      return Optional.empty();
    }
    throw new MwException(ErrorCodes.SYS_CRYPTO_UNAVAILABLE, "mw-crypto neither applied nor cancelled the change")
        .withStatus(HttpStatus.SERVICE_UNAVAILABLE);
  }

  /** A cancelled ceremony is aborted for good: its request expires instead of going back to PENDING. */
  @Override
  public boolean reusableAfterCancel(ChangeRequest cr) {
    return !KeyAction.KEY_CEREMONY.name().equals(cr.action());
  }

  /**
   * Aborts ceremonies mw-crypto still has OPEN or COMBINED whose request was rejected, expired or
   * never committed to this database (the filing transaction failed, or an abort failed while
   * mw-crypto was down); ceremonies younger than {@code olderThan} may still be being filed.
   */
  @Override
  public void reconcile(java.util.function.Function<UUID, Optional<ChangeRequest>> find, Instant olderThan) {
    for (CryptoKeys.Live c : crypto.liveCeremonies().items()) {
      if (c.createdAt() == null || !c.createdAt().isBefore(olderThan)) {
        continue;
      }
      Optional<ChangeRequest> cr = find.apply(c.ceremonyId());
      boolean dead = cr.isEmpty()
          || cr.get().status() == ChangeRequest.Status.REJECTED
          || cr.get().status() == ChangeRequest.Status.EXPIRED;
      if (dead) {
        crypto.abort(c.ceremonyId());
        log.atInfo()
            .addKeyValue("event", "admin.key_ceremony.orphan_aborted")
            .addKeyValue("changeRequestId", c.ceremonyId().toString())
            .log("aborted a ceremony whose request is gone, rejected or expired");
      }
    }
  }

  @Override
  public void closed(ChangeRequest cr, ChangeRequest.Status status, String checkerId, Instant now) {
    if (!KeyAction.KEY_CEREMONY.name().equals(cr.action())) {
      return;
    }
    try {
      crypto.abort(cr.id());
    } catch (MwException e) {
      log.atWarn()
          .addKeyValue("event", "admin.key_ceremony.abort_failed")
          .addKeyValue("changeRequestId", cr.id().toString())
          .addKeyValue("errorCode", e.code())
          .log("mw-crypto could not abort the ceremony; its staged key stays unusable until aborted");
    }
  }

  /** 403 MW-AUTH-031 unless the operator's auth_time is within the key MFA window. */
  static void requireFreshMfa(Operator operator, Instant now, AdminApiProperties props) {
    if (!operator.mfaFresh(now, props.keyMfaMaxAge())) {
      log.atWarn()
          .addKeyValue("event", "admin.key.stale_mfa_refused")
          .addKeyValue("errorCode", ErrorCodes.AUTH_STALE_MFA)
          .log("key action refused: MFA too old");
      throw new MwException(ErrorCodes.AUTH_STALE_MFA, "Sign in again: key actions need an MFA of the last 5 minutes")
          .withStatus(HttpStatus.FORBIDDEN);
    }
  }
}
