package mfs.mw.perf.stack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class PerfUsersTest {

  /** Wallets the e2e suite uses (a perf run must not drain them or lock them). */
  private static final Set<String> E2E_WALLETS =
      Set.of("01711000001", "01711000002", "01711000003", "01711000004", "01711000999", "01711001000",
          "01811000001", "01911000001", "01911000002");

  @Test
  void the_default_pools_use_seeded_wallets_the_e2e_suite_never_touches() {
    PerfUsers u = PerfUsers.defaults();

    assertThat(u.customers()).hasSize(200);
    assertThat(u.agents()).hasSize(30);
    assertThat(u.ussdWallets()).hasSize(100);
    assertThat(u.customers().getFirst()).isEqualTo(new PerfUsers.User("perf-c001", "01711000101", "customer"));
    assertThat(u.agents().getFirst()).isEqualTo(new PerfUsers.User("perf-a01", "01811000011", "agent"));
    List<String> wallets = Stream.of(
            u.customers().stream().map(PerfUsers.User::msisdn),
            u.agents().stream().map(PerfUsers.User::msisdn),
            u.ussdWallets().stream(),
            u.merchantWallets().stream())
        .flatMap(s -> s)
        .toList();
    assertThat(new HashSet<>(wallets)).hasSameSizeAs(wallets).doesNotContainAnyElementsOf(E2E_WALLETS);
    // mw-core-mock seeds 1,000 customers 0171100xxxx, 50 agents 0181100xxxx, 20 merchants 0191100xxxx
    assertThat(wallets).allSatisfy(w -> assertThat(w).matches("0171100(0[0-9]{3}|1000)|01811000(0[0-4][0-9]|050)|01911000(00[1-9]|01[0-9]|020)"));
  }

  @Test
  void pools_larger_than_the_core_seed_are_refused() {
    assertThatThrownBy(() -> new PerfUsers(800, 30, 100)).hasMessageContaining("seed");
    assertThatThrownBy(() -> new PerfUsers(200, 41, 100)).hasMessageContaining("seed");
  }

  @Test
  void the_keycloak_import_creates_each_user_with_its_role_wallet_and_the_dev_password() {
    JsonNode json = JsonMapper.builder().build().readTree(new PerfUsers(2, 1, 1).partialImport("fake-pw"));

    assertThat(json.path("ifResourceExists").asString()).isEqualTo("SKIP");
    assertThat(json.path("users")).hasSize(3);
    JsonNode agent = json.path("users").get(2);
    assertThat(agent.path("username").asString()).isEqualTo("perf-a01");
    assertThat(agent.path("enabled").asBoolean()).isTrue();
    assertThat(agent.path("realmRoles").get(0).asString()).isEqualTo("agent");
    assertThat(agent.path("attributes").path("msisdn").get(0).asString()).isEqualTo("01811000011");
    assertThat(agent.path("credentials").get(0).path("value").asString()).isEqualTo("fake-pw");
    assertThat(agent.path("email").asString()).isEqualTo("perf-a01@example.test");
  }
}
