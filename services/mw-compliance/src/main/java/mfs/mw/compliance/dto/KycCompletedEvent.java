package mfs.mw.compliance.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import mfs.mw.compliance.util.MaskingPolicy;
import mfs.mw.compliance.util.Sensitive;
import mfs.mw.compliance.util.SensitiveType;

/**
 * {@code mw.kyc.completed.v1} (contract {@code events/mw-compliance/mw.kyc.completed.v1.schema.json}; FRS DAT-01
 * {@code kyc.completed}): an application matched the EC record and the wallet gets {@code kycLevel}. Keyed by the
 * wallet id. No NID, name or date of birth.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record KycCompletedEvent(
    int schemaVersion,
    String eventId,
    String applicationId,
    @Sensitive(SensitiveType.MSISDN) String walletId,
    String kycLevel,
    String channel,
    String agentId,
    int matchScorePct,
    String completedAt,
    String correlationId) {

  @Override
  public String toString() {
    return MaskingPolicy.describe(this);
  }
}
