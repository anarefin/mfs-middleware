package mfs.mw.seeder.dto;

/**
 * Body of {@code POST /admin/v1/partners/{partnerId}/change-requests} (admin.v1): ENABLE, DISABLE
 * or SET_TIMEOUTS (with {@code timeouts}). Null fields are left out of the JSON.
 */
public record PartnerChangeRequest(String action, String reason, Timeouts timeouts) {

  public record Timeouts(Long timeoutMs) {}

  public static PartnerChangeRequest of(String action, String reason) {
    return new PartnerChangeRequest(action, reason, null);
  }

  public static PartnerChangeRequest timeout(long timeoutMs, String reason) {
    return new PartnerChangeRequest("SET_TIMEOUTS", reason, new Timeouts(timeoutMs));
  }
}
