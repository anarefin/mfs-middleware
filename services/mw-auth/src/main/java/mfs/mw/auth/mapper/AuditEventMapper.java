package mfs.mw.auth.mapper;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import mfs.mw.auth.dto.AuditEvent;
import mfs.mw.auth.util.MaskingPolicy;
import org.springframework.stereotype.Component;

/**
 * Audit events of the PIN lifecycle and the wallet locks (spec 18.7 "every change, reset and lock is
 * audited", ticket 70) as {@code mw.audit.event.v1}. Resource {@code wallet/<id>}, the id masked as an
 * MSISDN when it is one (mw-core-mock's wallet ids are MSISDNs); {@code before}/{@code after} hold
 * only lock levels, statuses and reasons, never PIN material.
 */
@Component
public class AuditEventMapper {

  public static final String SERVICE = "mw-auth";
  /** The actor of what mw-auth decides itself (a lockout). */
  public static final AuditEvent.Actor SYSTEM = new AuditEvent.Actor("system", SERVICE);

  public AuditEvent event(
      String action, String walletId, AuditEvent.Actor actor, String outcome, Map<String, Object> before,
      Map<String, Object> after, String correlationId, Instant at) {
    return new AuditEvent(1, UUID.randomUUID().toString(), correlationId, actor, action, resource(walletId), before,
        after, outcome, at, SERVICE);
  }

  /** {@code wallet/<id>}, an MSISDN id masked ({@code wallet/0171****789}). */
  public static String resource(String walletId) {
    return "wallet/" + MaskingPolicy.maskPartyId("WALLET", walletId);
  }

  /** The actor of a user (a customer's own wallet, or an operator by username). */
  public static AuditEvent.Actor user(String id) {
    return new AuditEvent.Actor("user", id);
  }

  /** A wallet acting on itself: the masked wallet id. */
  public static AuditEvent.Actor wallet(String walletId) {
    return user(MaskingPolicy.maskPartyId("WALLET", walletId));
  }
}
