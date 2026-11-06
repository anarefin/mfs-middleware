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
import mfs.mw.seeder.dto.NewUser;
import mfs.mw.seeder.dto.SeedManifest;
import org.junit.jupiter.api.Test;

class SeedIdentitiesStepTest {

  /** In-memory Keycloak: users and their realm roles per realm, plus the clients that exist. */
  static final class FakeKeycloak implements IdentityAdmin {
    final Map<String, Map<String, String>> ids = new HashMap<>();
    final Map<String, Set<String>> roles = new HashMap<>();
    final Map<String, NewUser> created = new HashMap<>();
    final Set<String> clients = new HashSet<>(Set.of("ops/mw-e2e-ops", "ops/mw-admin-ui", "customers/mw-app"));
    final List<String> writes = new ArrayList<>();

    @Override
    public Optional<String> findUserId(String realm, String username) {
      return Optional.ofNullable(ids.getOrDefault(realm, Map.of()).get(username));
    }

    @Override
    public String createUser(String realm, NewUser user) {
      String id = realm + ":" + user.username();
      ids.computeIfAbsent(realm, r -> new HashMap<>()).put(user.username(), id);
      created.put(id, user);
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

    assertThat(r).isEqualTo(StepResult.created(3));
    assertThat(kc.roles.get("ops:seed-ops-maker")).containsExactlyInAnyOrder("viewer", "ops-maker");
    NewUser maker = kc.created.get("ops:seed-ops-maker");
    assertThat(maker.password()).isEqualTo("fake-ops-password");
    assertThat(maker.totpSecret()).isEqualTo("JBSWY3DPEHPK3PXPFAKE");
    assertThat(maker.email()).endsWith("@example.test");
    NewUser customer = kc.created.get("customers:seed-customer01");
    assertThat(customer.totpSecret()).isNull();
    assertThat(customer.attributes()).containsEntry("msisdn", "01712000001");

    SeedManifest.Persona p = ctx.personas().getFirst();
    assertThat(p)
        .isEqualTo(
            new SeedManifest.Persona(
                "maker", "ops", "seed-ops-maker", List.of("viewer", "ops-maker"), Map.of(), "mw-e2e-ops",
                "KC_OPS_TEST_PASSWORD", "KC_OPS_TEST_TOTP_SECRET"));
    assertThat(ctx.personas().get(2).totpSecretEnv()).isNull();
  }

  @Test
  void aSecondRunFindsEveryoneAndWritesNothing() {
    step.run(new SeedContext());
    kc.writes.clear();
    SeedContext ctx = new SeedContext();

    assertThat(step.run(ctx)).isEqualTo(StepResult.unchanged(3));
    assertThat(kc.writes).isEmpty();
    assertThat(ctx.personas()).hasSize(3);
  }

  @Test
  void anExistingUserMissingARoleGetsOnlyThatRoleAndNoNewCredentials() {
    kc.ids.put("ops", new HashMap<>(Map.of("seed-ops-maker", "u1")));
    kc.roles.put("u1", new HashSet<>(Set.of("viewer")));

    StepResult r = step.run(new SeedContext());

    assertThat(r).isEqualTo(new StepResult(2, 1, 0));
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
        List.of(new mfs.mw.seeder.config.SeederProperties.User("x", "nowhere", "x1", List.of(), Map.of())),
        props.operators(), props.wallets(), props.partners(), props.content());

    assertThatThrownBy(() -> new SeedIdentitiesStep(kc, bad).run(new SeedContext()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("nowhere");
  }
}
