package mfs.mw.compliance.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.LocalDate;
import mfs.mw.compliance.util.MaskingPolicy;
import mfs.mw.compliance.util.Sensitive;
import mfs.mw.compliance.util.SensitiveType;

/**
 * The EC NID service's record of a citizen (FRS EXT-07), as {@code client/EcNidClient} reads it. Every field is
 * personal data: masked in logs and audit, sealed before it is stored, never returned by the API.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EcPerson(
    @Sensitive(SensitiveType.NID) String nid,
    @Sensitive(SensitiveType.SECRET) LocalDate dob,
    @Sensitive(SensitiveType.NAME) String nameEn,
    @Sensitive(SensitiveType.NAME) String nameBn,
    @Sensitive(SensitiveType.NAME) String fatherName,
    @Sensitive(SensitiveType.NAME) String motherName,
    @Sensitive(SensitiveType.SECRET) String address) {

  @Override
  public String toString() {
    return MaskingPolicy.describe(this);
  }
}
