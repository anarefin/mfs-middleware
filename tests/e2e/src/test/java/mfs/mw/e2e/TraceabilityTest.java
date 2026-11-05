package mfs.mw.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Offline check (no stack) that {@code TRACEABILITY.md} maps every scenario of the suite to the
 * MVP acceptance criteria (spec "MVP acceptance criteria", ticket 43) and that every coverage item
 * the criteria demand is claimed by at least one scenario. The required items below are written
 * from the criteria, not from the table, so a missing flow or outcome fails here.
 */
class TraceabilityTest {

  private static final Path FEATURES = Path.of("src/test/resources/features");
  private static final Path TABLE = Path.of("TRACEABILITY.md");
  private static final Pattern SCENARIO = Pattern.compile("^\\s*Scenario(?: Outline)?:\\s*(.+?)\\s*$");
  private static final Pattern ROW =
      Pattern.compile("^\\|\\s*([a-z0-9-]+\\.feature)\\s*\\|\\s*(.+?)\\s*\\|\\s*([0-9, -]+?)\\s*\\|\\s*(.*?)\\s*\\|\\s*$");

  /** Every MVP flow from its channel (criteria 1 and 2). */
  private static final List<String> FLOWS =
      List.of(
          "P2P app", "CASH_IN app", "CASH_OUT app", "TOPUP app", "BILL_PAY app", "ADD_MONEY_BANK app",
          "MERCHANT_PAY app", "MERCHANT_PAY checkout");

  /** The flows the USSD menus offer (criterion 1 for the USSD channel). */
  private static final List<String> USSD_FLOWS = List.of("P2P ussd", "CASH_OUT ussd", "TOPUP ussd", "BILL_PAY ussd");

  record Row(String feature, String scenario, Set<Integer> criteria, Set<String> covers) {}

  static Set<String> required() {
    Set<String> req = new LinkedHashSet<>();
    for (String flow : FLOWS) {
      for (String outcome : List.of("success", "failure", "tsq-commit", "tsq-release", "idempotent")) {
        req.add(flow + " " + outcome);
      }
    }
    USSD_FLOWS.forEach(f -> req.add(f + " success"));
    req.addAll(
        List.of(
            "ops search-txnId", "ops search-msisdn", "ops timeline", "ops audit", "ops reversal-approved",
            "ussd http", "ussd smpp"));
    return req;
  }

  @Test
  void everyScenarioIsMappedExactlyOnceAndEveryRowNamesARealScenario() throws IOException {
    Map<String, List<String>> scenarios = scenarios();
    List<Row> rows = rows();
    List<String> mapped = rows.stream().map(r -> r.feature() + " :: " + r.scenario()).toList();
    List<String> existing = new ArrayList<>();
    scenarios.forEach((f, names) -> names.forEach(n -> existing.add(f + " :: " + n)));

    assertThat(mapped).as("rows naming no scenario of the suite").isSubsetOf(existing);
    assertThat(existing).as("scenarios missing from TRACEABILITY.md").isSubsetOf(mapped);
    assertThat(mapped).as("scenarios mapped twice").doesNotHaveDuplicates();
    assertThat(existing).as("scenario names must be unique per feature").doesNotHaveDuplicates();
  }

  @Test
  void criteriaAreOneToFiveAndCoversItemsMatchTheirCriterion() throws IOException {
    for (Row r : rows()) {
      assertThat(r.criteria()).as("criteria of %s", r).allSatisfy(c -> assertThat(c).isBetween(1, 5));
      for (String item : r.covers()) {
        int criterion = item.endsWith(" idempotent") ? 2 : item.startsWith("ops ") ? 4 : item.startsWith("ussd ") ? 5 : 1;
        assertThat(r.criteria()).as("%s claims '%s' (criterion %d)", r, item, criterion).contains(criterion);
        assertThat(required()).as("'%s' of %s is not a known coverage item", item, r).contains(item);
      }
    }
  }

  @Test
  void everyCoverageItemTheCriteriaDemandIsClaimed() throws IOException {
    Set<String> claimed = new LinkedHashSet<>();
    rows().forEach(r -> claimed.addAll(r.covers()));
    assertThat(claimed).as("coverage items claimed by some scenario").containsAll(required());
  }

  private static Map<String, List<String>> scenarios() throws IOException {
    Map<String, List<String>> out = new LinkedHashMap<>();
    try (Stream<Path> files = Files.list(FEATURES)) {
      for (Path f : files.filter(p -> p.toString().endsWith(".feature")).sorted().toList()) {
        List<String> names = new ArrayList<>();
        for (String line : Files.readAllLines(f)) {
          Matcher m = SCENARIO.matcher(line);
          if (m.matches()) {
            names.add(m.group(1));
          }
        }
        out.put(f.getFileName().toString(), names);
      }
    }
    return out;
  }

  static List<Row> rows() throws IOException {
    List<Row> rows = new ArrayList<>();
    for (String line : Files.readAllLines(TABLE)) {
      Matcher m = ROW.matcher(line);
      if (!m.matches()) {
        continue;
      }
      Set<Integer> criteria = new LinkedHashSet<>();
      for (String c : m.group(3).split(",")) {
        String t = c.trim();
        if (!t.isEmpty() && !t.equals("-")) {
          criteria.add(Integer.valueOf(t));
        }
      }
      Set<String> covers = new LinkedHashSet<>();
      for (String c : m.group(4).split(";")) {
        if (!c.isBlank()) {
          covers.add(c.trim());
        }
      }
      rows.add(new Row(m.group(1), m.group(2), criteria, covers));
    }
    return rows;
  }
}
