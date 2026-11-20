package mfs.mw.seeder.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import mfs.mw.seeder.client.IdentityAdmin;
import mfs.mw.seeder.dto.KeycloakUser;
import mfs.mw.seeder.dto.NewUser;
import mfs.mw.seeder.dto.SeedManifest;
import org.junit.jupiter.api.Test;

class SeedIdentitiesStepTest {

  /** In-memory Keycloak: users (profile by id) and their realm roles per realm, plus the clients that exist. */
  static final class FakeKeycloak implements IdentityAdmin {
    final Map<String, Map<String, String>> ids = new HashMap<>();
    final Map<String, KeycloakUser> profiles = new HashMap<>();
    final Map<String, Set<String>> roles = new HashMap<>();
    final Map<String, NewUser> created = new HashMap<>();
    final Set<String> clients = new HashSet<>(Set.of("ops/mw-e2e-ops", "ops/mw-admin-ui"));
    final List<String> writes = new ArrayList<>();

    @Override
    public Optional<KeycloakUser> findUser(String realm, String username) {
      return Optional.ofNullable(ids.getOrDefault(realm, Map.of()).get(username))
          .map(id -> profiles.getOrDefault(id, new KeycloakUser(id, username, null, null, Map.of())));
    }

    /** A user the realm import made (not the seeder). */
    void imported(String realm, String username, String first, String last, Map<String, String> attributes, Set<String> r) {
      String id = "imp-" + username;
      ids.computeIfAbsent(realm, k -> new HashMap<>()).put(username, id);
      profiles.put(id, new KeycloakUser(id, username, first, last, attributes));
      roles.put(id, new HashSet<>(r));
    }

    @Override
    public String createUser(String realm, NewUser user) {
      String id = realm + ":" + user.username();
      ids.computeIfAbsent(realm, r -> new HashMap<>()).put(user.username(), id);
      created.put(id, user);
      profiles.put(id, new KeycloakUser(id, user.username(), user.firstName(), user.lastName(), user.attributes()));
      writes.add("create " + id);
      return id;
    }

    @Override
    public Set<String> realmRoles(String realm, String userId) {
      return Set.copyOf(roles.getOrDefault(userId, Set.of()));
    }

    @Override
    public void addRealmRoles(String realm, String userId, Collection<String> r) {
      roles.computeIfAbsent(userId, k -> new HashSet<>()).addAll(r);
      writes.add("roles " + userId + " " + new java.util.TreeSet<>(r));
    }

    @Override
    public boolean clientExists(String realm, String clientId) {
      return clients.contains(realm + "/" + clientId);
    }
  }

  private final FakeKeycloak kc = new FakeKeycloak();
  private final SeedIdentitiesStep step = new SeedIdentitiesStep(kc, SeedFixtures.props(Path.of("unused")));

  @Test
  void aFreshRealmGetsEveryPersonaWithItsRolesPasswordAndTotpWhereTheRealmDemandsIt() {
    SeedContext ctx = new SeedContext();

    StepResult r = step.run(ctx);

    assertThat(r).isEqualTo(StepResult.created(2));
    assertThat(kc.roles.get("ops:seed-ops-maker")).containsExactlyInAnyOrder("viewer", "ops-maker");
    NewUser maker = kc.created.get("ops:seed-ops-maker");
    assertThat(maker.password()).isEqualTo("fake-ops-password");
    assertThat(maker.totpSecret()).isEqualTo("JBSWY3DPEHPK3PXPFAKE");
    assertThat(maker.email()).endsWith("@example.test");
    assertThat(kc.writes).noneMatch(w -> w.contains("seed-customer01"));

    SeedManifest.Persona p = ctx.personas().getFirst();
    assertThat(p)
        .isEqualTo(
            new SeedManifest.Persona(
                "maker", "ops", "seed-ops-maker", "Nusrat Jahan", List.of("viewer", "ops-maker"), Map.of(), "mw-e2e-ops",
                "KC_OPS_TEST_PASSWORD", "KC_OPS_TEST_TOTP_SECRET"));
    assertThat(ctx.personas().get(2).totpSecretEnv()).isNull();
  }

  /** Ticket 73: the app users have no Keycloak user (the customers realm is gone); they log in at mw-auth. */
  @Test
  void anAppUserIsListedForMwAuthLoginButNeverCreatedInKeycloak() {
    SeedContext ctx = new SeedContext();

    step.run(ctx);

    assertThat(kc.ids).doesNotContainKey("mw-auth");
    assertThat(ctx.personas().get(2))
        .isEqualTo(
            new SeedManifest.Persona(
                "customer", "mw-auth", "seed-customer01", "Sumaiya Islam", List.of("customer"), Map.of("msisdn", "01712000001"),
                null, "MW_SEED_APP_PIN", null));
  }

  @Test
  void aNewUserGetsItsConfiguredNamesAndOneWithoutNamesTheFallback() {
    step.run(new SeedContext());

    NewUser maker = kc.created.get("ops:seed-ops-maker");
    assertThat(maker.firstName()).isEqualTo("Nusrat");
    assertThat(maker.lastName()).isEqualTo("Jahan");
    NewUser checker = kc.created.get("ops:seed-ops-checker");
    assertThat(checker.firstName()).isEqualTo("Seed");
    assertThat(checker.lastName()).isEqualTo("Checker");
  }

  @Test
  void aPersonaWithoutNamesHasNoDisplayName() {
    SeedContext ctx = new SeedContext();
    step.run(ctx);

    assertThat(ctx.personas().get(1).displayName()).isNull();
  }

  @Test
  void theRealmImportsUsersAreReadFromKeycloakForTheManifestAndNeverWritten() {
    kc.imported("ops", "ops-e2e-maker01", "Rashed", "Karim", Map.of(), Set.of("viewer", "ops-maker", "default-roles-ops"));
    kc.imported("ops", "ops-maker01", "Imran", "Kabir", Map.of("orgId", "ORG001"), Set.of("ops-maker", "viewer"));
    SeedContext ctx = new SeedContext();

    step.run(ctx);

    assertThat(kc.writes).noneMatch(w -> w.contains("ops-e2e-maker01") || w.contains("ops-maker01"));
    assertThat(ctx.importedUsers())
        .as("listed in config order; one that the realm import did not make (ops-gone01) is left out")
        .containsExactly(
            new SeedManifest.Persona(
                "ops-e2e-maker01", "ops", "ops-e2e-maker01", "Rashed Karim", List.of("ops-maker", "viewer"), Map.of(),
                "mw-e2e-ops", "KC_OPS_TEST_PASSWORD", "KC_OPS_TEST_TOTP_SECRET"),
            new SeedManifest.Persona(
                "ops-maker01", "ops", "ops-maker01", "Imran Kabir", List.of("ops-maker", "viewer"), Map.of("orgId", "ORG001"),
                "mw-e2e-ops", "KC_OPS_TEST_PASSWORD", null));
    assertThat(ctx.personas()).noneMatch(p -> p.username().startsWith("ops-"));
  }

  @Test
  void aSecondRunFindsEveryoneAndWritesNothing() {
    step.run(new SeedContext());
    kc.writes.clear();
    SeedContext ctx = new SeedContext();

    assertThat(step.run(ctx)).isEqualTo(StepResult.unchanged(2));
    assertThat(kc.writes).isEmpty();
    assertThat(ctx.personas()).hasSize(3);
  }

  @Test
  void anExistingUserMissingARoleGetsOnlyThatRoleAndNoNewCredentials() {
    kc.ids.put("ops", new HashMap<>(Map.of("seed-ops-maker", "u1")));
    kc.roles.put("u1", new HashSet<>(Set.of("viewer")));

    StepResult r = step.run(new SeedContext());

    assertThat(r).isEqualTo(new StepResult(1, 1, 0));
    assertThat(kc.writes).contains("roles u1 [ops-maker]").doesNotContain("create ops:seed-ops-maker");
  }

  @Test
  void aMissingClientFailsTheStep() {
    kc.clients.remove("ops/mw-e2e-ops");

    assertThatThrownBy(() -> step.run(new SeedContext()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("mw-e2e-ops");
  }

  @Test
  void aUserInARealmWithoutSettingsFailsTheStep() {
    var props = SeedFixtures.props(Path.of("unused"));
    var bad = new mfs.mw.seeder.config.SeederProperties(
        props.runOnStartup(), props.stepTimeout(), props.stepTimeouts(), props.initialBackoff(), props.maxBackoff(),
        props.manifestPath(), props.http(), props.endpoints(), props.readiness(), props.keycloakAdmin(), props.realms(),
        List.of(new mfs.mw.seeder.config.SeederProperties.User("x", "nowhere", "x1", List.of(), Map.of(), null, null)),
        props.importedUsers(),
        props.operators(), props.wallets(), props.partners(), props.channelClients(), props.content(), props.appPins());

    assertThatThrownBy(() -> new SeedIdentitiesStep(kc, bad).run(new SeedContext()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("nowhere");
  }
}
