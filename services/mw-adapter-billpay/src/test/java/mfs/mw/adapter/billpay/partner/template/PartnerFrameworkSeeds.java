package mfs.mw.adapter.billpay.partner.template;

import mfs.mw.adapter.billpay.partner.config.PartnerConfig;

/** Public access to the application-dev.yml partner seeds for tests outside this package. */
public final class PartnerFrameworkSeeds {

  private PartnerFrameworkSeeds() {}

  /** The billagg seed exactly as application-dev.yml defines it. */
  public static PartnerConfig billagg() {
    return PartnerFramework.billaggSeed();
  }
}
