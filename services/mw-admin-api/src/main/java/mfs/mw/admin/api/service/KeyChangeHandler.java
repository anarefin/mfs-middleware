package mfs.mw.admin.api.service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
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

  @Override
  public Approval prepare(ChangeRequest cr, Operator checker, Instant now, String correlationId) {
    KeyAction action = KeyAction.valueOf(cr.action());
    String keyId = String.valueOf(cr.payload().get("keyId"));
    CryptoKeys.Key key;
    if (action == KeyAction.KEY_CEREMONY) {
      if (cr.payload().get("checkValue") == null) {
        throw new MwException(ErrorCodes.VAL_CEREMONY_INCOMPLETE, "Both custodians must enter their components first")
            .withStatus(HttpStatus.CONFLICT);
      }
      key = crypto.commit(cr.id(), cr.makerId(), checker.username());
    } else {
      Object version = cr.payload().get("version");
      key = crypto.applyChange(
          cr.id(), action.name().substring("KEY_".length()), keyId, (String) cr.payload().get("purpose"),
          version instanceof Number n ? n.intValue() : null, cr.makerId(), checker.username());
    }
    CryptoKeys.Version active = key.active();
    CryptoKeys.Version newest = key.newest();
    Map<String, Object> added = new LinkedHashMap<>();
    added.put("appliedKid", (active != null ? active : newest).kid());
    log.atInfo()
        .addKeyValue("event", "admin.key_change.applied")
        .addKeyValue("changeRequestId", cr.id().toString())
        .addKeyValue("action", cr.action())
        .addKeyValue("keyId", keyId)
        .log("mw-crypto applied the key change");
    return new Approval(cr.payload(), SENT_AS, () -> added);
  }

  /**
   * mw-crypto's state of this request's change: a ceremony COMMITTED, or a key change applied for
   * this id. Its own failures (unreachable) propagate: the claim then stays APPROVING.
   */
  @Override
  public Optional<Map<String, Object>> applied(ChangeRequest cr) {
    if (KeyAction.KEY_CEREMONY.name().equals(cr.action())) {
      return crypto.ceremony(cr.id())
          .filter(c -> "COMMITTED".equals(c.state()))
          .map(c -> Map.<String, Object>of("appliedKid", c.kid()));
    }
    CryptoKeys.ChangeState s = crypto.keyChangeState(cr.id());
    return s.applied() && s.kid() != null ? Optional.of(Map.<String, Object>of("appliedKid", s.kid()))
        : s.applied() ? Optional.of(Map.<String, Object>of()) : Optional.empty();
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
