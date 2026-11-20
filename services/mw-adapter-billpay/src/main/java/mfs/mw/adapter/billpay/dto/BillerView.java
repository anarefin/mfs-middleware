package mfs.mw.adapter.billpay.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * One biller of the catalogue and the aggregator ({@code partnerId}) that serves it. Absent values
 * are left out (the contract's strings are never null). Ticket 113 adds the catalogue fields: bn
 * name, fallback aggregator, input fields (name, labels, regex), amount rule and bounds (poisha),
 * bill-month flag, enabled and the bn/en maintenance text of a disabled biller.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BillerView(
    String code,
    String name,
    String category,
    String partnerId,
    String nameBn,
    String fallbackPartnerId,
    List<FieldView> fields,
    String amountRule,
    Long minAmountMinor,
    Long maxAmountMinor,
    Boolean billMonthRequired,
    Boolean enabled,
    String maintenanceEn,
    String maintenanceBn) {

  /** The ticket 21 shape (name, category, aggregator only). */
  public BillerView(String code, String name, String category, String partnerId) {
    this(code, name, category, partnerId, null, null, null, null, null, null, null, null, null, null);
  }

  /** One input field a channel asks for. */
  public record FieldView(String name, String labelEn, String labelBn, String regex, boolean required) {}
}
