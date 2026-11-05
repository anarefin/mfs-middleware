package mfs.mw.perf.stack;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The perf run's users and wallets, all on wallets mw-core-mock seeds (1,000 customers
 * {@code 01711000001..}, 50 agents {@code 01811000001..}, 20 merchants {@code 01911000001..}) and
 * none the e2e suite uses: app customers {@code perf-c001..} on {@code 01711000101..}, USSD
 * customers (no Keycloak user) after them, agents {@code perf-a01..} on {@code 01811000011..},
 * merchants {@code 01911000003..01911000010}.
 *
 * <p>APISIX allows 120 app requests per minute per token subject and 120 USSD hops per minute per
 * MSISDN, so the pools are sized to keep every user well under that at the target rate.
 *
 * <p>The app users exist only in the running stack's Keycloak: {@link #partialImport} is the realm
 * partial import that {@code Provisioning} applies with {@code ifResourceExists: SKIP} (idempotent).
 */
public final class PerfUsers {

  /** A Keycloak user of realm customers: username, wallet (msisdn attribute), realm role. */
  public record User(String username, String msisdn, String role) {}

  private static final int FIRST_CUSTOMER = 101;
  private static final int FIRST_AGENT = 11;
  private static final int SEEDED_AGENTS = 50;

  private final List<User> customers;
  private final List<User> agents;
  private final List<String> ussdWallets;

  public PerfUsers(int customers, int agents, int ussdWallets) {
    // e2e uses 01711000999 and 01711001000; keep below them
    if (FIRST_CUSTOMER + customers + ussdWallets - 1 >= 999 || customers < 1 || ussdWallets < 0) {
      throw new IllegalArgumentException("customers + USSD wallets must fit the core seed below 01711000999");
    }
    if (FIRST_AGENT + agents - 1 > SEEDED_AGENTS || agents < 1) {
      throw new IllegalArgumentException("agents must fit the core seed (01811000011..01811000050)");
    }
    this.customers =
        IntStream.range(0, customers)
            .mapToObj(i -> new User(String.format("perf-c%03d", i + 1), wallet("0171100", FIRST_CUSTOMER + i), "customer"))
            .toList();
    this.agents =
        IntStream.range(0, agents)
            .mapToObj(i -> new User(String.format("perf-a%02d", i + 1), wallet("0181100", FIRST_AGENT + i), "agent"))
            .toList();
    this.ussdWallets =
        IntStream.range(0, ussdWallets).mapToObj(i -> wallet("0171100", FIRST_CUSTOMER + customers + i)).toList();
  }

  /** 200 app customers, 30 agents, 100 USSD customers. */
  public static PerfUsers defaults() {
    return new PerfUsers(200, 30, 100);
  }

  /** From {@code mw.perf.customers} / {@code mw.perf.agents} / {@code mw.perf.ussd-wallets}. */
  public static PerfUsers fromSettings() {
    return new PerfUsers(
        Integer.getInteger("mw.perf.customers", 200),
        Integer.getInteger("mw.perf.agents", 30),
        Integer.getInteger("mw.perf.ussd-wallets", 100));
  }

  public List<User> customers() {
    return customers;
  }

  public List<User> agents() {
    return agents;
  }

  public List<String> ussdWallets() {
    return ussdWallets;
  }

  /** Seeded merchant wallets the e2e suite does not use (it pays merchant01 / merchant02). */
  public List<String> merchantWallets() {
    return IntStream.rangeClosed(3, 10).mapToObj(i -> wallet("0191100", i)).toList();
  }

  /** Every app user, customers first. */
  public List<User> appUsers() {
    List<User> all = new ArrayList<>(customers);
    all.addAll(agents);
    return all;
  }

  /** Keycloak partial import (realm customers) creating every app user, skipping existing ones. */
  public String partialImport(String password) {
    JsonMapper json = JsonMapper.builder().build();
    ObjectNode root = json.createObjectNode();
    root.put("ifResourceExists", "SKIP");
    var users = root.putArray("users");
    for (User u : appUsers()) {
      ObjectNode n = users.addObject();
      n.put("username", u.username());
      n.put("enabled", true);
      n.put("emailVerified", true);
      n.put("firstName", "Perf");
      n.put("lastName", "Load");
      n.put("email", u.username() + "@example.test");
      n.putArray("credentials").addObject().put("type", "password").put("value", password).put("temporary", false);
      n.putArray("realmRoles").add(u.role());
      n.putObject("attributes").putArray("msisdn").add(u.msisdn());
    }
    return json.writeValueAsString(root);
  }

  private static String wallet(String prefix, int n) {
    return prefix + String.format("%04d", n);
  }
}
