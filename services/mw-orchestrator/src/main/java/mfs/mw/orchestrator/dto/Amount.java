package mfs.mw.orchestrator.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Money on the wire: non-negative integer minor units, currency BDT (contract {@code Amount}). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Amount(long valueMinor, String currency) {

  public static final String BDT = "BDT";

  public static Amount bdt(long valueMinor) {
    return new Amount(valueMinor, BDT);
  }
}
