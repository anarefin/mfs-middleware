package mfs.mw.seeder.service;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import mfs.mw.seeder.config.SeederProperties;
import mfs.mw.seeder.config.SeederProperties.Content;
import mfs.mw.seeder.config.SeederProperties.MenuSpec;
import mfs.mw.seeder.config.SeederProperties.PartnerSpec;
import mfs.mw.seeder.config.SeederProperties.Realm;
import mfs.mw.seeder.config.SeederProperties.TemplateSpec;
import mfs.mw.seeder.config.SeederProperties.User;
import mfs.mw.seeder.config.SeederProperties.WalletSpec;

/** Small, fake seed settings for the step tests. */
final class SeedFixtures {

  static final String MAKER = "seed-ops-maker";
  static final String CHECKER = "seed-ops-checker";

  private SeedFixtures() {}

  static SeederProperties props(Path manifest) {
    return new SeederProperties(
        true,
        Duration.ofSeconds(5),
        Map.of(),
        Duration.ofMillis(5),
        Duration.ofMillis(20),
        manifest,
        new SeederProperties.Http(Duration.ofSeconds(1), Duration.ofSeconds(1)),
        new SeederProperties.Endpoints(URI.create("http://kc"), URI.create("http://admin"), URI.create("http://core")),
        List.of(
            new SeederProperties.Readiness("apisix", URI.create("http://apisix:7085/status/ready")),
            new SeederProperties.Readiness("mw-admin-api", URI.create("http://mw-admin-api:8080/actuator/health/readiness"))),
        new SeederProperties.KeycloakAdmin("master", "admin-cli", "kc-admin", "fake-admin-password"),
        Map.of(
            "ops", new Realm("fake-ops-password", "KC_OPS_TEST_PASSWORD", "JBSWY3DPEHPK3PXPFAKE", "KC_OPS_TEST_TOTP_SECRET", "mw-e2e-ops", List.of("mw-e2e-ops", "mw-admin-ui")),
            "customers", new Realm("fake-customer-password", "KC_CUSTOMERS_TEST_PASSWORD", null, null, "mw-app", List.of("mw-app"))),
        List.of(
            new User("maker", "ops", MAKER, List.of("viewer", "ops-maker"), Map.of()),
            new User("checker", "ops", CHECKER, List.of("viewer", "ops-checker"), Map.of()),
            new User("customer", "customers", "seed-customer01", List.of("customer"), Map.of("msisdn", "01712000001"))),
        new SeederProperties.Operators("ops", "mw-e2e-ops", MAKER, CHECKER),
        List.of(
            new WalletSpec("01712000001", "CUSTOMER", 1_000_000),
            new WalletSpec("01812000001", "AGENT", 5_000_000)),
        List.of(new PartnerSpec("ocs", true, 20_000L)),
        new Content(
            List.of(new MenuSpec("main", "classpath:seed-content/menus/main.json", List.of("gp", "robi"))),
            List.of(new TemplateSpec("TXN_P2P_RECEIVED", "SMS", "en", "You have received Tk {{amount}}", null, "Bits"))));
  }
}
