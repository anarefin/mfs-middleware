package mfs.mw.compliance.entity;

import java.time.Instant;
import java.util.UUID;
import mfs.mw.compliance.dto.Channel;
import mfs.mw.compliance.dto.KycLevel;
import mfs.mw.compliance.dto.KycStatus;
import mfs.mw.compliance.util.MaskingPolicy;
import mfs.mw.compliance.util.Sensitive;
import mfs.mw.compliance.util.SensitiveType;

/**
 * A row of {@code compliance.kyc_application}. The {@code *Enc} columns are mw-crypto envelopes (JSON) of the
 * NID, the date of birth, the claimed names and the EC's names; {@code nidToken} is the TOKENISE_NID token.
 */
public record KycApplication(
    UUID id,
    @Sensitive(SensitiveType.MSISDN) String walletId,
    Channel channel,
    String agentId,
    UUID consentId,
    KycStatus status,
    @Sensitive(SensitiveType.SECRET) String nidToken,
    String nidLast3,
    @Sensitive(SensitiveType.SECRET) String nidEnc,
    @Sensitive(SensitiveType.SECRET) String dobEnc,
    @Sensitive(SensitiveType.SECRET) String nameEnc,
    @Sensitive(SensitiveType.SECRET) String ecNameEnc,
    Double matchScore,
    KycLevel kycLevel,
    String reason,
    String errorCode,
    String correlationId,
    Instant createdAt,
    Instant updatedAt) {

  @Override
  public String toString() {
    return MaskingPolicy.describe(this);
  }
}
