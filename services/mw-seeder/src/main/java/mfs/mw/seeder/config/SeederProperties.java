package mfs.mw.seeder.config;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * What the seeder seeds and where (ticket 57), {@code mw.seeder.*} in {@code application.yml}. The
 * data lists are the baseline the Newman and Playwright suites share; passwords and the TOTP secret
 * come from the environment (Compose interpolates them from the generated {@code deploy/.env}) and
 * are never written to a file or log.
 */
@ConfigurationProperties("mw.seeder")
public record SeederProperties(
    boolean runOnStartup,
    Duration stepTimeout,
    Map<String, Duration> stepTimeouts,
    Duration initialBackoff,
    Duration maxBackoff,
    Path manifestPath,
    Http http,
    Endpoints endpoints,
    List<Readiness> readiness,
    KeycloakAdmin keycloakAdmin,
    Map<String, Realm> realms,
    List<User> users,
    Operators operators,
    List<WalletSpec> wallets,
    List<PartnerSpec> partners,
    Content content) {

  public SeederProperties {
    stepTimeouts = stepTimeouts == null ? Map.of() : stepTimeouts;
    readiness = readiness == null ? List.of() : readiness;
    realms = realms == null ? Map.of() : realms;
    users = users == null ? List.of() : users;
    wallets = wallets == null ? List.of() : wallets;
    partners = partners == null ? List.of() : partners;
    content = content == null ? new Content(List.of(), List.of()) : content;
  }

  public record Http(Duration connectTimeout, Duration readTimeout) {}

  /** Base URLs inside the stack's networks (the services directly, not through APISIX). */
  public record Endpoints(URI keycloak, URI adminApi, URI coreMock) {}

  /** One readiness probe of await-platform-ready: ready when GET {@code url} answers 200. */
  public record Readiness(String name, URI url) {}

  /** The Keycloak bootstrap admin (master realm, password grant of {@code admin-cli}). */
  public record KeycloakAdmin(String realm, String clientId, String username, String password) {}

  /**
   * A realm the seeder puts users in: their password (and TOTP secret when the realm demands a
   * code), the names of the {@code deploy/.env} variables holding them (for the manifest), the client
   * a suite logs in with, and the clients that must exist.
   */
  public record Realm(
      String password,
      String passwordEnv,
      String totpSecret,
      String totpSecretEnv,
      String loginClient,
      List<String> clients) {

    public Realm {
      clients = clients == null ? List.of() : clients;
    }

    public boolean totp() {
      return totpSecret != null && !totpSecret.isBlank();
    }
  }

  /** A Keycloak user: persona name for the manifest, realm roles, user-profile attributes. */
  public record User(String persona, String realm, String username, List<String> roles, Map<String, String> attributes) {

    public User {
      roles = roles == null ? List.of() : roles;
      attributes = attributes == null ? Map.of() : attributes;
    }
  }

  /** The two ops personas the admin-api steps act as (maker != checker), realm and direct-grant client. */
  public record Operators(String realm, String client, String maker, String checker) {}

  /** A core-mock wallet: id = MSISDN, type, opening balance in poisha. */
  public record WalletSpec(String walletId, String type, long openingBalanceMinor) {}

  /** A partner's baseline in mw-admin-api: enabled, and the time limit of one call (null: any). */
  public record PartnerSpec(String partnerId, boolean enabled, Long timeoutMs) {}

  public record Content(List<MenuSpec> menus, List<TemplateSpec> templates) {

    public Content {
      menus = menus == null ? List.of() : menus;
      templates = templates == null ? List.of() : templates;
    }
  }

  /** A USSD menu: its tree (a classpath resource, mw-ussd's menu JSON) and the MNOs it goes live on. */
  public record MenuSpec(String menuId, String resource, List<String> mnos) {}

  /** A notification template version (SMS: no subject). */
  public record TemplateSpec(String code, String channel, String locale, String body, String subject, String senderId) {}
}
