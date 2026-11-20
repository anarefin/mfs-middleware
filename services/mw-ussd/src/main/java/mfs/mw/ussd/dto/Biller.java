package mfs.mw.ussd.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * One biller of the catalogue and the aggregator ({@code partnerId}) that serves it: from this
 * service's projection of mw-admin-api's catalogue ({@code service/BillerCatalog}, ticket 113b) or,
 * while that is empty, from mw-adapter-billpay's list (contract {@code
 * api/mw-adapter-billpay/billpay.v1.openapi.yaml}, the same fields). The catalogue fields (bn name,
 * input fields, enabled, bn/en maintenance text) are null for a static seed entry.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Biller(
    String code,
    String name,
    String category,
    String partnerId,
    String nameBn,
    List<Field> fields,
    Boolean enabled,
    String maintenanceEn,
    String maintenanceBn,
    Boolean billMonthRequired) {

  public Biller(String code, String name, String category, String partnerId) {
    this(code, name, category, partnerId, null, null, null, null, null, null);
  }

  /** One input field the handset is asked for. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Field(String name, String labelEn, String labelBn, String regex, boolean required) {}

  /** False only when the catalogue says so. */
  @JsonIgnore
  public boolean isEnabled() {
    return !Boolean.FALSE.equals(enabled);
  }

  /** The first required input field, the one the bill number is asked as; null when none is known. */
  @JsonIgnore
  public Field keyField() {
    return fields == null ? null : fields.stream().filter(Field::required).findFirst().orElse(null);
  }
}
