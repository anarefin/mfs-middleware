package mfs.mw.seeder.service;

import java.util.ArrayList;
import java.util.List;
import mfs.mw.seeder.dto.SeedManifest;

/**
 * State one seed run carries from step to step: what the manifest will list. Steps run one after
 * the other, so it needs no locking. A step adds its entries only once it has done all its work,
 * so a step that failed and is run again never lists anything twice.
 */
public class SeedContext {

  private final List<SeedManifest.Persona> personas = new ArrayList<>();
  private final List<SeedManifest.Wallet> wallets = new ArrayList<>();
  private final List<SeedManifest.Partner> partners = new ArrayList<>();
  private final List<SeedManifest.UssdMenu> menus = new ArrayList<>();
  private final List<SeedManifest.Template> templates = new ArrayList<>();

  public List<SeedManifest.Persona> personas() {
    return personas;
  }

  public List<SeedManifest.Wallet> wallets() {
    return wallets;
  }

  public List<SeedManifest.Partner> partners() {
    return partners;
  }

  public List<SeedManifest.UssdMenu> menus() {
    return menus;
  }

  public List<SeedManifest.Template> templates() {
    return templates;
  }

  public SeedManifest manifest() {
    return new SeedManifest(
        SeedManifest.SCHEMA_VERSION,
        List.copyOf(personas),
        List.copyOf(wallets),
        List.copyOf(partners),
        List.copyOf(menus),
        List.copyOf(templates));
  }
}
