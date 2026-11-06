package mfs.mw.seeder.dto;

/**
 * A partner as mw-admin-api's {@code GET /admin/v1/partners/{partnerId}} answers it (admin.v1
 * {@code Partner}); only the fields the seeder compares are read.
 */
public record PartnerView(String partnerId, String family, long version, Config config) {

  /** {@code enabled} absent means enabled (admin.v1 {@code PartnerConfig}). */
  public record Config(Boolean enabled, Long timeoutMs) {

    public boolean isEnabled() {
      return enabled == null || enabled;
    }
  }
}
