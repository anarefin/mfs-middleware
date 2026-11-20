package mfs.mw.orchestrator.service.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Routing rules (ticket 98, FRS PLT-040): the most specific matching rule wins, amount and channel
 * constrain a rule, a disabled rule is skipped, and a partner the caller names is used only when
 * that rule allows it.
 */
class RoutingServiceTest {

  static final Instant NOW = Instant.parse("2026-11-20T08:00:00Z");

  private final RoutingService routing = new RoutingService();

  @Test
  void theMostSpecificRuleWinsAndADisabledRuleIsSkipped() {
    RoutingRule generic = rule("generic", "TOPUP", null, null, null, "any-connector", List.of(), 0, true);
    RoutingRule gp = rule("gp", "TOPUP", "gp", null, null, "ocs", List.of("ocs-standby"), 0, true);
    RoutingRule appOnly =
        rule("gp-app", "TOPUP", "gp", "CUSTOMER_APP", new Long[] {1_000L, 5_000L}, "ocs-app", List.of(), 0, true);
    RoutingRule priced =
        rule("gp-small", "TOPUP", "gp", null, new Long[] {1_000L, 5_000L}, "ocs-small", List.of(), 5, true);
    RoutingRule disabled =
        rule("gp-off", "TOPUP", "gp", "CUSTOMER_APP", new Long[] {1_000L, 5_000L}, "ocs-off", List.of(), 100, false);

    RoutingSnapshot snapshot = snapshot(List.of(generic, gp, appOnly, priced, disabled));

    Decision app = routing.decide(query("TOPUP", "gp", "gp", null, 2_000, "CUSTOMER_APP"), snapshot, NOW);
    assertThat(app).isInstanceOf(Decision.Ready.class);
    assertThat(((Decision.Ready) app).attempts().get(0).wirePartnerId()).isEqualTo("gp");
    assertThat(((Decision.Ready) app).attempts().get(0).healthPartnerId()).isEqualTo("ocs-app");

    Decision ussd = routing.decide(query("TOPUP", "gp", "gp", null, 2_000, "USSD"), snapshot, NOW);
    assertThat(wires(ussd)).containsExactly("gp");
    assertThat(((Decision.Ready) ussd).attempts().get(0).healthPartnerId()).isEqualTo("ocs-small");

    Decision large = routing.decide(query("TOPUP", "gp", "gp", null, 9_000, "USSD"), snapshot, NOW);
    assertThat(((Decision.Ready) large).attempts().get(0).healthPartnerId()).isEqualTo("ocs");
    assertThat(wires(large)).containsExactly("gp", "ocs-standby");

    Decision other = routing.decide(query("TOPUP", null, "robi", null, 2_000, "USSD"), snapshot, NOW);
    assertThat(wires(other)).containsExactly("any-connector");
  }

  @Test
  void aNamedPartnerIsUsedOnlyWhenTheWinningRuleAllowsIt() {
    RoutingRule gp = rule("gp", "TOPUP", "gp", null, null, "ocs", List.of("ocs-standby"), 0, true);
    RoutingSnapshot snapshot = snapshot(List.of(gp));

    Decision named = routing.decide(query("TOPUP", "gp", "gp", null, 2_000, "CUSTOMER_APP"), snapshot, NOW);
    assertThat(wires(named)).startsWith("gp");

    Decision connector = routing.decide(query("TOPUP", "ocs", "gp", null, 2_000, "CUSTOMER_APP"), snapshot, NOW);
    assertThat(wires(connector)).containsExactly("ocs", "ocs-standby");

    Decision unknown = routing.decide(query("TOPUP", "no-such", "no-such", null, 2_000, "CUSTOMER_APP"), snapshot, NOW);
    assertThat(unknown).isEqualTo(new Decision.UnknownPartner());
  }

  @Test
  void aDownPrimaryIsSkippedAndAMaintenanceMessageDisablesTheBiller() {
    RoutingRule bill = rule("bills", "BILL_PAY", null, null, null, "billagg", List.of("billagg-2"), 0, true);
    RoutingSnapshot down =
        new RoutingSnapshot(
            List.of(bill),
            List.of(),
            List.of(),
            List.of(),
            java.util.Map.of("billagg", "DOWN"));
    Decision skipped = routing.decide(query("BILL_PAY", "billagg", null, "DESCO", 100, "CUSTOMER_APP"), down, NOW);
    assertThat(wires(skipped)).containsExactly("billagg-2");

    RoutingSnapshot closed =
        new RoutingSnapshot(List.of(bill), List.of(), List.of(), List.of(), java.util.Map.of("billagg", "DOWN", "billagg-2", "MAINTENANCE"));
    Decision none = routing.decide(query("BILL_PAY", "billagg", null, "DESCO", 100, "CUSTOMER_APP"), closed, NOW);
    assertThat(none).isEqualTo(new Decision.Blocked("MW-SYS-PARTNER_CIRCUIT_OPEN", "No partner is available", null));

    MaintenanceMessage message =
        new MaintenanceMessage(
            "desco", null, "DESCO", "DESCO is under maintenance", "ডেসকো রক্ষণাবেক্ষণে আছে", null, null, true, 1);
    RoutingSnapshot maintaining =
        new RoutingSnapshot(List.of(bill), List.of(), List.of(), List.of(message), java.util.Map.of());
    Decision disabled = routing.decide(query("BILL_PAY", "billagg", null, "DESCO", 100, "CUSTOMER_APP"), maintaining, NOW);
    assertThat(disabled)
        .isEqualTo(new Decision.Blocked("MW-SYS-PARTNER_DISABLED", "DESCO is under maintenance", "ডেসকো রক্ষণাবেক্ষণে আছে"));
  }

  /**
   * Ticket 114 (FRS PLT-032, T8): the catalogue rule names the biller's aggregator and its fallback.
   * A DOWN primary is skipped before anything is sent. A payment the partner already answered, or
   * whose outcome is unknown, is not a pre-send refusal, so it is not tried on the fallback.
   */
  @Test
  void aDownCatalogueAggregatorIsSkippedForItsFallbackAndASentPayIsNotRetried() {
    RoutingRule amberit = billerRule("bill-amberit", "AMBERIT", "sslcommerz", List.of("billagg"));
    RoutingRule du = billerRule("bill-du", "DU", "agg3", List.of("ekpay"));
    RoutingRule generic = rule("billpay", "BILL_PAY", null, null, null, "billagg", List.of(), 0, true);
    List<RoutingRule> rules = List.of(generic, amberit, du);

    RoutingSnapshot up = snapshot(rules);
    assertThat(wires(routing.decide(query("BILL_PAY", null, null, "AMBERIT", 125_050, "CUSTOMER_APP"), up, NOW)))
        .containsExactly("sslcommerz", "billagg");
    assertThat(wires(routing.decide(query("BILL_PAY", null, null, "DU", 125_050, "CUSTOMER_APP"), up, NOW)))
        .containsExactly("agg3", "ekpay");

    RoutingSnapshot sslDown =
        new RoutingSnapshot(rules, List.of(), List.of(), List.of(), java.util.Map.of("sslcommerz", "DOWN"));
    assertThat(wires(routing.decide(query("BILL_PAY", null, null, "AMBERIT", 125_050, "CUSTOMER_APP"), sslDown, NOW)))
        .containsExactly("billagg");

    RoutingSnapshot agg3Down =
        new RoutingSnapshot(rules, List.of(), List.of(), List.of(), java.util.Map.of("agg3", "MAINTENANCE"));
    assertThat(wires(routing.decide(query("BILL_PAY", null, null, "DU", 125_050, "CUSTOMER_APP"), agg3Down, NOW)))
        .containsExactly("ekpay");

    assertThat(RoutingCatalog.preSendRefusal("MW-SYS-PARTNER_CIRCUIT_OPEN")).isTrue();
    assertThat(RoutingCatalog.preSendRefusal("MW-SYS-PARTNER_UNAVAILABLE")).isTrue();
    assertThat(RoutingCatalog.preSendRefusal("MW-TMO-PARTNER_TIMEOUT")).isFalse();
    assertThat(RoutingCatalog.preSendRefusal("MW-PTR-SSLCOMMERZ-BILL_ALREADY_PAID")).isFalse();
    assertThat(RoutingCatalog.preSendRefusal("MW-PTR-AGG3-PAYMENT_REFUSED")).isFalse();
  }

  private static RoutingRule billerRule(String id, String biller, String primary, List<String> fallbacks) {
    return new RoutingRule(id, "BILL_PAY", null, biller, null, null, null, null, null, primary, fallbacks, 10, true, 1);
  }

  private static List<String> wires(Decision decision) {
    assertThat(decision).isInstanceOf(Decision.Ready.class);
    return ((Decision.Ready) decision).attempts().stream().map(Attempt::wirePartnerId).toList();
  }

  private static RoutingSnapshot snapshot(List<RoutingRule> rules) {
    return new RoutingSnapshot(rules, List.of(), List.of(), List.of(), java.util.Map.of());
  }

  private static RoutingQuery query(
      String txnType, String requested, String operator, String biller, long amount, String channel) {
    return new RoutingQuery(txnType, "PREPAID", biller, null, operator, amount, channel, requested);
  }

  private static RoutingRule rule(
      String id, String txnType, String operator, String channel, Long[] amount, String primary,
      List<String> fallbacks, int priority, boolean enabled) {
    Long min = amount == null ? null : amount[0];
    Long max = amount == null ? null : amount[1];
    return new RoutingRule(id, txnType, null, null, null, operator, min, max, channel, primary, fallbacks, priority, enabled, 1);
  }
}
