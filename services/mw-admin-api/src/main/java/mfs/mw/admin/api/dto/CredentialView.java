package mfs.mw.admin.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

/**
 * A partner credential as {@code admin.v1} shows it (ticket 131): metadata only, never the secret.
 *
 * @param daysLeft whole days until {@code notAfter} (negative once expired); absent without an expiry
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CredentialView(
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
    Long daysLeft,
    long version,
    String uploadedBy,
    String approvedBy,
    Instant uploadedAt) {

  public record Page(List<CredentialView> items) {}
}
