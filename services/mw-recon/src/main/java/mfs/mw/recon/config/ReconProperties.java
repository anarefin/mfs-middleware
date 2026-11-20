package mfs.mw.recon.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Reconciliation settings (ticket 126). The SFTP password is empty here and comes from Vault
 * ({@code mw.recon.trust.password}, provisioned from {@code SFTP_DEV_PASSWORD}).
 */
@ConfigurationProperties("mw.recon")
public record ReconProperties(boolean consumersEnabled, Trust trust, Match match, Fields fields) {

  public record Trust(
      String host,
      int port,
      String username,
      String password,
      String directory,
      boolean insecureHostKey,
      String fingerprint,
      String mt940Cron,
      String mt942Cron,
      String zone,
      String settlementWallet,
      String partnerId) {}

  /** Default tolerance is 0 poisha; the date window is ± days. */
  public record Match(long toleranceMinor, int dateWindowDays) {}

  /** Column names on {@code mw.partner.recon.v1} fields (the dev settlement file). */
  public record Fields(String partnerRef, String reference, String amount, String status, String valueDate) {}
}
