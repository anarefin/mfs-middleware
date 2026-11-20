package mfs.mw.simulators.service.mockcontrol;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import mfs.mw.simulators.dto.MockBehaviour;
import mfs.mw.simulators.dto.MockParty;

/**
 * The trigger registry of every mock party (ticket 136, FRS MCK-03): the data triggers (amount endings,
 * MSISDN, account and bill number endings, seeded records) each party answers by when no mock-control
 * scenario decides, grouped by FRS party. {@link #markdown()} renders it with the parties' operations,
 * scenarios, switch and journal; the rendering is {@code src/main/resources/mock-triggers.md} and the body of
 * {@code docs/mocks/README.md} ({@code MockTriggerDocsTest}). A new party or trigger adds a line here.
 */
public final class MockTriggers {

  /** One data trigger: the input it looks at, what in it, and what the mock answers. */
  public record Trigger(String input, String match, String outcome) {}

  /** One FRS party (one or more mock parties): its refs ({@code EXT-nn}, {@code CON-nn}, a spec section). */
  public record Section(String title, List<String> ext, List<String> parties, List<Trigger> triggers, String noTriggers) {}

  /** The amount fractions of {@code MagicAmounts}, shared by every party that takes money. */
  public static final Map<String, String> AMOUNT_FRACTIONS = orderedMap(
      "13", "timeout: applied, the reply held `sim.timeout-delay`",
      "77", "HTTP 500 after the effect (applied)",
      "99", "422 unknown code `9876` (ambiguous)",
      "55", "malformed (cut-short) body, applied");

  private static List<Trigger> amounts() {
    return AMOUNT_FRACTIONS.entrySet().stream()
        .map(e -> new Trigger("amount", "fraction `." + e.getKey() + "`", e.getValue())).toList();
  }

  private static List<Trigger> with(List<Trigger> first, List<Trigger> then) {
    return Stream.concat(first.stream(), then.stream()).toList();
  }

  private static List<String> banks() {
    return MockParties.ALL.stream().map(MockParty::name).filter(n -> n.startsWith("bank-")).toList();
  }

  private static List<Trigger> aggregatorTriggers(String codes) {
    return with(List.of(
        new Trigger("bill", "not in the seed, biller known", "fetch and pay 422 " + codes + "1 NOT_FOUND"),
        new Trigger("biller", "not in the profile's seed", "fetch and pay 422 " + codes + "4 UNKNOWN_BILLER"),
        new Trigger("bill", "PAID (seeded or paid earlier)", "fetch 200 the paid word; pay 422 " + codes + "2 PAID"),
        new Trigger("amount", "not the bill's", "pay 422 " + codes + "6 AMOUNT_MISMATCH"),
        new Trigger("bill number", "ending `999`", "pay 422 " + codes + "3 REFUSED"),
        new Trigger("reverse", "of a payment not paid", "422 " + codes + "5 NOT_PAID")), amounts());
  }

  public static final List<Section> SECTIONS = List.of(
      new Section("USSD gateways", List.of("EXT-01"), List.of("ussdgw", "ussd-gp", "ussd-robi", "ussd-bl", "ussd-teletalk"),
          List.of(), "a session is played by `POST /sim/ussdgw/sessions`; faults by scenario only"),
      new Section("SMSC", List.of("EXT-02"), List.of("smsc"), List.of(
          new Trigger("destination MSISDN", "ending `999`", "DLR UNDELIV"),
          new Trigger("destination MSISDN", "ending `998`", "DLR EXPIRED"),
          new Trigger("destination MSISDN", "ending `997`", "first two submits refused `0x58` (throttled), then accepted"),
          new Trigger("destination MSISDN", "ending `996`", "submit refused `0x0B`"),
          new Trigger("destination MSISDN", "ending `995`", "accepted, no DLR"),
          new Trigger("destination MSISDN", "anything else", "accepted, DLR DELIVRD after `sim.smsc.dlr-delay`")), null),
      new Section("Push server", List.of("EXT-04"), List.of("push"), List.of(
          new Trigger("token", "contains `invalid`", "404 `UNREGISTERED`, not stored")), null),
      new Section("AML system", List.of("EXT-05"), List.of("aml"), List.of(
          new Trigger("amount", "above 5,000,000 poisha", "ALERT `AMOUNT_THRESHOLD` (payer)"),
          new Trigger("WALLET or MSISDN party", "on the watch-list (`01711000777`, `01811000777`, `01911000777`)", "HOLD `WATCH_LIST`"),
          new Trigger("WALLET or MSISDN party", "ending `6666`", "BLOCK `BLOCK_SUFFIX` (the strongest action wins)")), null),
      new Section("Trust bank", List.of("EXT-06"), List.of("trustbank"), List.of(),
          "the statements are built from the mw-core-mock ledger; mismatches by INJECTED_MISMATCH"
              + " (`MISSING_IN_PARTNER`, `MISSING_IN_UPAY`, `AMOUNT_MISMATCH`, `DC_FLIP`)"),
      new Section("OCS, legacy single profile", List.of("EXT-08"), List.of("ocs"), with(List.of(
          new Trigger("MSISDN", "ending `999`", "422 `2001` business failure")), amounts()), null),
      new Section("Operator OCS (GP, Robi, Teletalk, Banglalink)", List.of("EXT-08"),
          List.of("ocs-gp", "ocs-robi", "ocs-teletalk", "ocs-bl"), List.of(
          new Trigger("MSISDN", "ending `999`", "the operator's barred code"),
          new Trigger("postpaid MSISDN", "seeded (`01711000021`, `01811000021`, `01511000021`, `01911000021`)",
              "the seeded bill (outstanding, minimum, bill id)"),
          new Trigger("postpaid MSISDN", "any other", "the operator's unknown-bill code")), null),
      new Section("Utility aggregator", List.of("EXT-09"), List.of("billagg"), with(List.of(
          new Trigger("bill number", "seeded (`1149...`)", "its own amount; PAID refused `3002` on fetch and pay"),
          new Trigger("bill number", "ending `404`", "fetch 422 `3001` not found"),
          new Trigger("bill number", "ending `000`", "fetch 422 `3002` already paid"),
          new Trigger("biller", "unknown, or a SOAP biller on the REST front end", "422 `3004` unknown biller"),
          new Trigger("bill number", "ending `999`", "pay 422 `3003` refused by the biller"),
          new Trigger("bill number", "anything else", "amount due 100 taka + the last three digits")), amounts()), null),
      new Section("SSLCommerz, eKpay, aggregator 3, aggregator 4", List.of("EXT-10"),
          List.of("sslcommerz", "ekpay", "agg3", "agg4"),
          aggregatorTriggers("the profile's code n="), null),
      new Section("Sample REST biller of the connector guide", List.of("CON-06"), List.of("samplebiller"),
          with(List.of(new Trigger("bill number", "seeded (`1360000001`..`1360000005`, biller `SAMPLEWATER`)",
              "its own amount and state")), aggregatorTriggers("`SB0`")), null),
      new Section("Merchant IPN receiver", List.of("EXT-11"), List.of("merchant"), List.of(),
          "every IPN is answered 200 and recorded; faults by scenario only"),
      new Section("Merchant system over SOAP", List.of("EXT-11"), List.of("merchant-soap"), List.of(
          new Trigger("merchantId", "`M-0001`", "active, tills `TILL-01`, `TILL-02`"),
          new Trigger("merchantId", "`M-0002`", "fault `5002` inactive"),
          new Trigger("merchantId", "`M-0003`, or a till it lacks", "fault `5003` (M-0003 has no till)"),
          new Trigger("merchantId", "any other", "fault `5001` unknown merchant"),
          new Trigger("refund", "above the notified amount", "fault `5004`")), null),
      new Section("C2W partner bank", List.of("EXT-12"), List.of("c2wbank"), with(List.of(
          new Trigger("account", "ending `999`", "FAILED `4002` debit refused, by callback")), amounts()), null),
      new Section("Bank gateway (one party per bank)", List.of("EXT-12"), banks(), with(List.of(
          new Trigger("account", "ending `00` (not seeded)", "422 `4001` ACCOUNT_NOT_FOUND"),
          new Trigger("account", "ending `98`, or seeded DORMANT / CLOSED", "422 `4002` ACCOUNT_INACTIVE"),
          new Trigger("account", "ending `99`, or a balance short of the amount", "DEBIT 422 `4003` INSUFFICIENT_FUNDS"),
          new Trigger("account", "ending `97`", "transfer 422 `4004` LIMIT_EXCEEDED"),
          new Trigger("account", "ending `96`", "transfer 422 `4005` TRANSFER_REFUSED"),
          new Trigger("account", "ending `95`", "CREDIT 202 PENDING, SUCCESS by callback and status")), amounts()), null),
      new Section("NPSB switch", List.of("EXT-13"), List.of("npsb"), List.of(
          new Trigger("creditor agent", "not in the participant seed, or suspended (`FAKEBDX9`)", "RJCT `RC01`"),
          new Trigger("creditor account", "ending `991`", "RJCT `AC01`"),
          new Trigger("creditor account", "ending `992`", "RJCT `AM04`"),
          new Trigger("creditor account", "ending `993`", "PDNG, then ACSC after `sim.npsb.settle-delay`"),
          new Trigger("creditor account", "ending `994`", "ACSC booked, the first answer held `sim.timeout-delay`"),
          new Trigger("creditor account", "ending `995`", "PDNG, then RJCT `AC01` after the settle delay"),
          new Trigger("creditor account", "anything else", "ACSC, `ClrSysRef` `NPSB-CLR-n`")), null),
      new Section("CyberSource", List.of("EXT-14"), List.of("cybersource"), List.of(
          new Trigger("test card", "`4111111111111111`", "authenticated, captured"),
          new Trigger("test card", "`4000000000000002`", "declined `4001`"),
          new Trigger("test card", "`4000000000003063`", "3-D Secure failed `4002` (webhook FAILED, capture refused)"),
          new Trigger("test card", "`4000000000000119`", "captured, the answer held `sim.timeout-delay`"),
          new Trigger("test card", "any other", "declined")), null),
      new Section("UCB IMT", List.of("EXT-15"), List.of("ucb-imt"), List.of(),
          "`POST /mock-control/ucb-imt/trigger` pushes one signed credit on demand"),
      new Section("UCB ATM switch", List.of("EXT-16"), List.of("ucbatm"), List.of(),
          "a known reference is DISPENSED; a BUSINESS_ERROR scenario code `5404` is NOT_FOUND,"
              + " any other code NOT_DISPENSED"),
      new Section("Data Warehouse drop", List.of("EXT-17"), List.of("dw"), List.of(
          new Trigger("control file", "a listed file missing, its sha256 or row count wrong, or a wrong header",
              "verdict REJECTED with the reasons"),
          new Trigger("control file", "everything matches", "verdict ACCEPTED")), null),
      new Section("Bangladesh Election Commission NID", List.of("EXT-07"), List.of("ec"), List.of(
          new Trigger("NID", "ending `000`, or not in `seed/v1/ec/nid.json`", "404 `NID_NOT_FOUND`"),
          new Trigger("NID", "ending `999`", "timeout: the answer held `sim.timeout-delay`"),
          new Trigger("NID", "ending `111`", "the record with the date of birth one year after the one asked"),
          new Trigger("NID", "any other seeded one", "the record (names, parents, address, photo reference)")), null),
      new Section("payShield 10K HSM", List.of("spec 18.8"), List.of("payshield"), List.of(), "the"
          + " commands run on real key material; error codes by scenario (BUSINESS_ERROR `code`, default `15`)"));

  private MockTriggers() {}

  /** The registry as the Markdown of {@code docs/mocks/README.md}. */
  public static String markdown() {
    StringBuilder md = new StringBuilder();
    md.append("<!-- generated from mw-simulators MockTriggers (ticket 136); do not edit by hand -->\n");
    for (Section s : SECTIONS) {
      md.append("\n## ").append(s.title()).append(" (").append(String.join(", ", s.ext())).append(")\n\n");
      md.append("| Party | Operations | Scope keys |\n| --- | --- | --- |\n");
      Map<String, List<String>> rows = new LinkedHashMap<>();
      for (String name : s.parties()) {
        MockParty p = MockParties.require(name);
        rows.computeIfAbsent(String.join(", ", p.operations()) + " | " + String.join(", ", p.keys()), k -> new ArrayList<>())
            .add("`" + name + "`");
      }
      rows.forEach((opsKeys, names) -> md.append("| ").append(String.join(", ", names)).append(" | ").append(opsKeys).append(" |\n"));
      List<MockBehaviour> behaviours = MockParties.require(s.parties().get(0)).behaviours();
      md.append("\nScenarios: ").append(behaviours.stream().map(Enum::name).collect(Collectors.joining(", ")))
          .append(".\nSwitch: UP, DOWN, MAINTENANCE, SLOW; settings `callbackUrl`, `callbackOnly`, `timeoutDelayMs`.\n")
          .append("Journal: `GET /mock-control/").append(s.parties().size() == 1 ? s.parties().get(0) : "<party>")
          .append("/journal` (`DELETE` clears it and resets the party).\n\n");
      if (s.triggers().isEmpty()) {
        md.append("Data triggers: none; ").append(s.noTriggers()).append(".\n");
      } else {
        md.append("| Input | Trigger | Outcome |\n| --- | --- | --- |\n");
        s.triggers().forEach(t -> md.append("| ").append(t.input()).append(" | ").append(t.match()).append(" | ")
            .append(t.outcome()).append(" |\n"));
      }
    }
    return md.toString();
  }

  private static Map<String, String> orderedMap(String... kv) {
    Map<String, String> m = new LinkedHashMap<>();
    for (int i = 0; i < kv.length; i += 2) {
      m.put(kv[i], kv[i + 1]);
    }
    return java.util.Collections.unmodifiableMap(m);
  }
}
