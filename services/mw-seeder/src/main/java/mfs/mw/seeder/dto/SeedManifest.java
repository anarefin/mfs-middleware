package mfs.mw.seeder.dto;

import java.util.List;
import java.util.Map;
import mfs.mw.seeder.util.Sensitive;
import mfs.mw.seeder.util.SensitiveType;

/**
 * What the seeder put in place (ticket 57), for the Newman (ticket 58) and Playwright (ticket 59)
 * suites: written as JSON to {@code mw.seeder.manifest-path} and logged once, masked. It holds no
 * secret: a persona names the {@code deploy/.env} variables that hold its password and TOTP
 * secret. Deterministic (no timestamps), so a second run that changed nothing writes the same file.
 */
public record SeedManifest(
    int schemaVersion,
    List<Persona> personas,
    List<Wallet> wallets,
    List<Partner> partners,
    List<UssdMenu> ussdMenus,
    List<Template> templates) {

  public static final int SCHEMA_VERSION = 1;

  /**
   * A Keycloak user. {@code loginClient}: the client a suite logs in with (ops: the direct-grant
   * client {@code mw-e2e-ops} for API calls; the console {@code mw-admin-ui} through its login
   * page). {@code totpSecretEnv} is set when the realm demands a TOTP code.
   */
  public record Persona(
      String persona,
      String realm,
      String username,
      List<String> roles,
      Map<String, String> attributes,
      String loginClient,
      String passwordEnv,
      String totpSecretEnv) {}

  /** A core-mock wallet (id = MSISDN); every seeded wallet has core-mock's seed PIN. */
  public record Wallet(
      @Sensitive(SensitiveType.MSISDN) String walletId, String type, long openingBalanceMinor) {}

  /** A partner at its baseline in mw-admin-api ({@code version} as published there, 0 = seed). */
  public record Partner(String partnerId, String family, long version, boolean enabled, Long timeoutMs) {}

  /** A USSD menu version published through mw-admin-api, live for {@code mnos}. */
  public record UssdMenu(String menuId, int version, List<String> mnos) {}

  /** A notification template version published through mw-admin-api. */
  public record Template(String code, String channel, String locale, int version) {}
}
