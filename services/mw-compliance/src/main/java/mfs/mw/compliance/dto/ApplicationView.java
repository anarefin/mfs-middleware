package mfs.mw.compliance.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import mfs.mw.compliance.util.MaskingPolicy;
import mfs.mw.compliance.util.Sensitive;
import mfs.mw.compliance.util.SensitiveType;

/**
 * compliance.v1 Application: an application as the API shows it. No clear personal data: the NID only as its
 * last three digits (spec 18.3), no names or date of birth (the permissioned unmask screen is ticket 134).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApplicationView(
    UUID applicationId,
    @Sensitive(SensitiveType.MSISDN) String walletId,
    Channel channel,
    String agentId,
    UUID consentId,
    KycStatus status,
    String nidMasked,
    Double matchScore,
    KycLevel kycLevel,
    String reason,
    String errorCode,
    boolean retryable,
    List<DocumentType> documents,
    Instant createdAt,
    Instant updatedAt) {

  @Override
  public String toString() {
    return MaskingPolicy.describe(this);
  }
}
