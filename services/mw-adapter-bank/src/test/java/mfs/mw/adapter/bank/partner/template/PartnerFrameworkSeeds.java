package mfs.mw.adapter.bank.partner.template;

import mfs.mw.adapter.bank.partner.config.PartnerConfig;

/** Public access to the application.yml partner seeds for tests outside this package. */
public final class PartnerFrameworkSeeds {

  private PartnerFrameworkSeeds() {}

  /** The c2wbank seed exactly as application.yml defines it. */
  public static PartnerConfig c2wbank() {
    return PartnerFramework.c2wbankSeed();
  }
}
