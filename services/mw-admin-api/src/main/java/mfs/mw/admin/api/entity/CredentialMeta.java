package mfs.mw.admin.api.entity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import mfs.mw.admin.api.dto.CredentialType;

/**
 * One row of {@code admin.credential_meta} (ticket 131): what is known about a partner credential
 * whose secret was written to Vault by an approved change request. Never the secret.
 *
 * @param credentialId {@code <partnerId>/<name>}
 * @param adapter the adapter service whose secret holds it ({@code mw-adapter-<family>})
 * @param vaultFields the field names written in {@code secret/<adapter>/<partnerId>}
 * @param kid {@code <name>-v<version>}
 */
public record CredentialMeta(
    String credentialId,
    String partnerId,
    String name,
    String adapter,
    CredentialType type,
    List<String> vaultFields,
    String kid,
    String fingerprint,
    String subject,
    String serialNumber,
    Instant notAfter,
    long version,
    String uploadedBy,
    String approvedBy,
    Instant uploadedAt,
    UUID changeRequestId) {

  /** {@code ChangeRequest.resourceType} of a credential upload. */
  public static final String RESOURCE_TYPE = "partner_credential";

  public CredentialMeta {
    vaultFields = List.copyOf(vaultFields);
  }

  public static String id(String partnerId, String name) {
    return partnerId + "/" + name;
  }
}
