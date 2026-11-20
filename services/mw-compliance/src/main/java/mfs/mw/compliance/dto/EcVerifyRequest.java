package mfs.mw.compliance.dto;

import java.time.LocalDate;
import mfs.mw.compliance.util.MaskingPolicy;
import mfs.mw.compliance.util.Sensitive;
import mfs.mw.compliance.util.SensitiveType;

/**
 * The EC NID verify request (REST {@code POST /ec/v1/nid/verify}): the NID and date of birth to look up, the
 * consent the customer gave (CMP-04) and this request's id (the same on a retry: the call is idempotent).
 */
public record EcVerifyRequest(
    @Sensitive(SensitiveType.NID) String nid,
    @Sensitive(SensitiveType.SECRET) LocalDate dob,
    String consentId,
    String requestId) {

  @Override
  public String toString() {
    return MaskingPolicy.describe(this);
  }
}
