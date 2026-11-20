package mfs.mw.admin.api.mapper;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import mfs.mw.admin.api.dto.CredentialType;
import mfs.mw.admin.api.dto.CredentialView;
import mfs.mw.admin.api.entity.CredentialMeta;

/**
 * Partner credential metadata between the change request payload, the {@code credential_meta}
 * row and the {@code admin.v1} view (ticket 131). Nothing here ever holds a secret value.
 */
public final class CredentialMapper {

  private CredentialMapper() {}

  public static CredentialView view(CredentialMeta m, Instant now) {
    Long daysLeft = m.notAfter() == null ? null : Math.floorDiv(Duration.between(now, m.notAfter()).toSeconds(), 86_400L);
    return new CredentialView(
        m.credentialId(), m.partnerId(), m.name(), m.adapter(), m.type(), m.vaultFields(), m.kid(), m.fingerprint(),
        m.subject(), m.serialNumber(), m.notAfter(), daysLeft, m.version(), m.uploadedBy(), m.approvedBy(),
        m.uploadedAt());
  }

  /** The shown side of a credential (the change request's {@code payload.meta} and diff side). */
  public static Map<String, Object> side(
      CredentialType type, List<String> vaultFields, String kid, String fingerprint, String subject, String serialNumber,
      Instant notAfter) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("type", type.name());
    m.put("vaultFields", List.copyOf(vaultFields));
    m.put("kid", kid);
    putIf(m, "fingerprint", fingerprint);
    putIf(m, "subject", subject);
    putIf(m, "serialNumber", serialNumber);
    putIf(m, "notAfter", notAfter == null ? null : notAfter.toString());
    return m;
  }

  public static Map<String, Object> side(CredentialMeta m) {
    return side(m.type(), m.vaultFields(), m.kid(), m.fingerprint(), m.subject(), m.serialNumber(), m.notAfter());
  }

  /** The row an approval stores, from the request's payload (built by the maker side). */
  @SuppressWarnings("unchecked")
  public static CredentialMeta fromPayload(Map<String, Object> payload, String uploadedBy, String approvedBy, Instant at, UUID crId) {
    Map<String, Object> meta = (Map<String, Object>) payload.get("meta");
    String partnerId = (String) payload.get("partnerId");
    String name = (String) payload.get("name");
    Object notAfter = meta.get("notAfter");
    return new CredentialMeta(
        CredentialMeta.id(partnerId, name), partnerId, name, (String) payload.get("adapter"),
        CredentialType.valueOf((String) meta.get("type")), (List<String>) meta.get("vaultFields"),
        (String) meta.get("kid"), (String) meta.get("fingerprint"), (String) meta.get("subject"),
        (String) meta.get("serialNumber"), notAfter == null ? null : Instant.parse(notAfter.toString()), 1, uploadedBy,
        approvedBy, at, crId);
  }

  private static void putIf(Map<String, Object> m, String k, Object v) {
    if (v != null) {
      m.put(k, v);
    }
  }
}
