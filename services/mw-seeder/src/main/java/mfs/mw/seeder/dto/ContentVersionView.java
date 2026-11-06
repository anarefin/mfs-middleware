package mfs.mw.seeder.dto;

import java.util.List;

/** A USSD menu or template version made through mw-admin-api (admin.v1 {@code ContentVersion}). */
public record ContentVersionView(String configKey, int version, String status, String changeRequestId, String makerId) {

  /** admin.v1 {@code ContentVersionPage}. */
  public record Page(List<ContentVersionView> items) {}
}
