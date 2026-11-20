package mfs.mw.compliance.entity;

import java.util.UUID;
import mfs.mw.compliance.dto.DocumentType;
import mfs.mw.compliance.util.MaskingPolicy;
import mfs.mw.compliance.util.Sensitive;
import mfs.mw.compliance.util.SensitiveType;

/** A row of {@code compliance.kyc_document}: the image as an mw-crypto envelope (JSON) and the sha256 of its bytes. */
public record KycDocument(
    UUID id,
    UUID applicationId,
    DocumentType type,
    String contentType,
    int sizeBytes,
    String sha256,
    @Sensitive(SensitiveType.SECRET) String blobEnc) {

  @Override
  public String toString() {
    return MaskingPolicy.describe(this);
  }
}
