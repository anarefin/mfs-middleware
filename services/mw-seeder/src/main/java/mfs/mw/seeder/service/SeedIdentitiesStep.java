package mfs.mw.seeder.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import mfs.mw.seeder.client.IdentityAdmin;
import mfs.mw.seeder.config.SeederProperties;
import mfs.mw.seeder.config.SeederProperties.Realm;
import mfs.mw.seeder.config.SeederProperties.User;
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
 * create them); a missing one fails the step.
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
      result = result.plus(ensure(user, realm));
      personas.add(
          new SeedManifest.Persona(
              user.persona(), user.realm(), user.username(), user.roles(), user.attributes(), realm.loginClient(),
              realm.passwordEnv(), realm.totp() ? realm.totpSecretEnv() : null));
    }
    ctx.personas().addAll(personas);
    return result;
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
        "Seed",
        user.persona() == null ? "User" : capitalise(user.persona()),
        user.username() + "@example.test",
        user.attributes(),
        realm.password(),
        realm.totp() ? realm.totpSecret() : null);
  }

  private static String capitalise(String s) {
    return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
  }
}
