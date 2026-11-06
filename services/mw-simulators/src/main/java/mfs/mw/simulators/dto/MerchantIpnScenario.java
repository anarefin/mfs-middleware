package mfs.mw.simulators.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * How the merchant IPN receiver answers (ticket 32): the next {@code failNext} IPNs (only those
 * whose {@code X-Bits-Ipn-Id} is {@code ipnId}, when given) get {@code failStatus} (default 500),
 * every later one 200.
 */
public record MerchantIpnScenario(
    @PositiveOrZero int failNext, @Min(300) @Max(599) Integer failStatus, String ipnId) {

  public static final MerchantIpnScenario ACCEPT = new MerchantIpnScenario(0, null, null);

  public boolean appliesTo(String id) {
    return failNext > 0 && (ipnId == null || ipnId.equals(id));
  }

  public int failStatusOrDefault() {
    return failStatus == null ? 500 : failStatus;
  }
}
