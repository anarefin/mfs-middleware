package mfs.mw.app.gateway.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * One biller of the catalogue. {@code partnerId} (the aggregator serving it) is never shown to the
 * app (null in the app view). Ticket 113b: the fields of mw-admin-api's catalogue ({@code
 * mw.config.changed.v1} BILLER, this service's projection {@code service/BillerCatalog}): bn name,
 * input fields (labels and regex the app prompts with), amount rule and bounds (poisha), bill-month
 * flag, enabled and the bn/en maintenance text of a disabled biller. Absent values are left out (an
 * entry read from mw-adapter-billpay's static seed has none of them).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Biller(
    String code,
    String name,
    String category,
    String partnerId,
    String nameBn,
    List<Field> fields,
    String amountRule,
    Long minAmountMinor,
    Long maxAmountMinor,
    Boolean billMonthRequired,
    Boolean enabled,
    String maintenanceEn,
    String maintenanceBn) {

  /** The ticket 21 shape (name, category, aggregator only). */
  public Biller(String code, String name, String category, String partnerId) {
    this(code, name, category, partnerId, null, null, null, null, null, null, null, null, null);
  }

  /** One input field the app asks for. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Field(String name, String labelEn, String labelBn, String regex, boolean required) {}

  public Biller withoutPartner() {
    return new Biller(code, name, category, null, nameBn, fields, amountRule, minAmountMinor, maxAmountMinor,
        billMonthRequired, enabled, maintenanceEn, maintenanceBn);
  }

  /** False only when the catalogue says so. */
  @JsonIgnore
  public boolean isEnabled() {
    return !Boolean.FALSE.equals(enabled);
  }
}
