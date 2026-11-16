package mfs.mw.seeder.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import mfs.mw.seeder.client.IdentityAdmin;
import mfs.mw.seeder.config.SeederProperties;
import mfs.mw.seeder.config.SeederProperties.ImportedUser;
import mfs.mw.seeder.config.SeederProperties.Realm;
import mfs.mw.seeder.config.SeederProperties.User;
import mfs.mw.seeder.dto.KeycloakUser;
import mfs.mw.seeder.dto.NewUser;
import mfs.mw.seeder.dto.SeedManifest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

/**
 * seed-identities: the personas of {@code mw.seeder.users} in Keycloak, through its admin REST API.
 * A missing user is created with its realm's test password (and TOTP credential where the realm
 * demands a code) and its realm roles; an existing one only gets the roles it lacks (its
 * credentials are left alone). The clients each realm lists must already exist (the realm imports
 * create them); a missing one fails the step. The users of a realm that is not in Keycloak (the app
 * users, who log in at mw-auth with MSISDN + the dev app PIN; ticket 73) are only listed for the
 * manifest: no Keycloak call, not counted. A new user gets its configured first and last name
 * ("Seed" + the persona when none is set). The users the realm imports create ({@code
 * mw.seeder.imported-users}) are only read (profile, attributes, realm roles) and listed in the
 * manifest's {@code importedUsers}; one the realm does not have is left out with a WARN.
 */
@Service
@Order(2)
public class SeedIdentitiesStep implements SeedStep {

  private static final Logger log = LoggerFactory.getLogger(SeedIdentitiesStep.class);

  private final IdentityAdmin keycloak;
  private final SeederProperties props;

  public SeedIdentitiesStep(IdentityAdmin keycloak, SeederProperties props) {
    this.keycloak = keycloak;
    this.props = props;
  }

  @Override
  public String id() {
    return "seed-identities";
  }

  @Override
  public StepResult run(SeedContext ctx) {
    for (Map.Entry<String, Realm> realm : props.realms().entrySet()) {
      if (!realm.getValue().inKeycloak()) {
        continue;
      }
      for (String client : realm.getValue().clients()) {
        if (!keycloak.clientExists(realm.getKey(), client)) {
          throw new IllegalStateException("client " + client + " does not exist in realm " + realm.getKey());
        }
      }
    }
    StepResult result = StepResult.unchanged(0);
    List<SeedManifest.Persona> personas = new ArrayList<>();
    for (User user : props.users()) {
      Realm realm = props.realms().get(user.realm());
      if (realm == null) {
        throw new IllegalStateException("user " + user.username() + ": no settings for realm " + user.realm());
      }
      if (realm.inKeycloak()) {
        result = result.plus(ensure(user, realm));
      }
      personas.add(
          new SeedManifest.Persona(
              user.persona(), user.realm(), user.username(), displayName(user.firstName(), user.lastName()), user.roles(),
              user.attributes(), realm.loginClient(), realm.passwordEnv(), realm.totp() ? realm.totpSecretEnv() : null));
    }
    List<SeedManifest.Persona> imported = importedUsers();
    ctx.personas().addAll(personas);
    ctx.importedUsers().addAll(imported);
    return result;
  }

  /** The realm imports' users as Keycloak holds them; read only. */
  private List<SeedManifest.Persona> importedUsers() {
    List<SeedManifest.Persona> out = new ArrayList<>();
    for (ImportedUser u : props.importedUsers()) {
      Realm realm = props.realms().get(u.realm());
      if (realm == null || !realm.inKeycloak()) {
        throw new IllegalStateException("imported user " + u.username() + ": no Keycloak realm " + u.realm());
      }
      Optional<KeycloakUser> found = keycloak.findUser(u.realm(), u.username());
      if (found.isEmpty()) {
        log.atWarn()
            .addKeyValue("event", "seed.imported-user.missing")
            .log("realm {} has no user {} (realm import changed?); left out of the manifest", u.realm(), u.username());
        continue;
      }
      KeycloakUser kc = found.get();
      String defaultRoles = "default-roles-" + u.realm();
      List<String> roles =
          keycloak.realmRoles(u.realm(), kc.id()).stream().filter(r -> !r.equals(defaultRoles)).sorted().toList();
      out.add(
          new SeedManifest.Persona(
              u.username(), u.realm(), u.username(), displayName(kc.firstName(), kc.lastName()), roles,
              new java.util.TreeMap<>(kc.attributes()), realm.loginClient(), realm.passwordEnv(),
              u.presetTotp() ? realm.totpSecretEnv() : null));
    }
    return out;
  }

  /** "First Last", either one alone, or null when neither is set. */
  static String displayName(String first, String last) {
    String f = first == null ? "" : first.strip();
    String l = last == null ? "" : last.strip();
    String name = (f + " " + l).strip();
    return name.isEmpty() ? null : name;
  }

  private StepResult ensure(User user, Realm realm) {
    Optional<String> existing = keycloak.findUserId(user.realm(), user.username());
    if (existing.isEmpty()) {
      String id = keycloak.createUser(user.realm(), newUser(user, realm));
      if (!user.roles().isEmpty()) {
        keycloak.addRealmRoles(user.realm(), id, user.roles());
      }
      log.atInfo().addKeyValue("event", "seed.user.created").log("created {} user {}", user.realm(), user.username());
      return StepResult.created(1);
    }
    Set<String> missing = new LinkedHashSet<>(user.roles());
    missing.removeAll(keycloak.realmRoles(user.realm(), existing.get()));
    if (missing.isEmpty()) {
      return StepResult.unchanged(1);
    }
    keycloak.addRealmRoles(user.realm(), existing.get(), missing);
    log.atInfo()
        .addKeyValue("event", "seed.user.updated")
        .log("added realm roles {} to {} user {}", missing, user.realm(), user.username());
    return StepResult.updated(1);
  }

  private static NewUser newUser(User user, Realm realm) {
    if (realm.password() == null || realm.password().isBlank()) {
      throw new IllegalStateException("no test password for realm " + user.realm() + " (" + realm.passwordEnv() + ")");
    }
    return new NewUser(
        user.username(),
        hasText(user.firstName()) ? user.firstName() : "Seed",
        hasText(user.lastName()) ? user.lastName() : user.persona() == null ? "User" : capitalise(user.persona()),
        user.username() + "@example.test",
        user.attributes(),
        realm.password(),
        realm.totp() ? realm.totpSecret() : null);
  }

  private static boolean hasText(String s) {
    return s != null && !s.isBlank();
  }

  private static String capitalise(String s) {
    return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
  }
}
