package mfs.mw.compliance.entity;

import java.time.Instant;
import java.util.UUID;
import mfs.mw.compliance.dto.Channel;
import mfs.mw.compliance.util.MaskingPolicy;
import mfs.mw.compliance.util.Sensitive;
import mfs.mw.compliance.util.SensitiveType;

/** A row of {@code compliance.consent} (FRS CMP-04): who agreed, to what, on which channel, when. */
public record Consent(
    UUID id,
    @Sensitive(SensitiveType.MSISDN) String walletId,
    String purpose,
    Channel channel,
    String agentId,
    Instant grantedAt) {

  @Override
  public String toString() {
    return MaskingPolicy.describe(this);
  }
}
