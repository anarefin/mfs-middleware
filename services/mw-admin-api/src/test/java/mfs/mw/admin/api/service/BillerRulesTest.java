package mfs.mw.admin.api.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BillerRulesTest {

  static Map<String, Object> biller() {
    Map<String, Object> v = new LinkedHashMap<>();
    v.put("nameEn", "Dhaka WASA");
    v.put("nameBn", "ঢাকা ওয়াসা");
    v.put("category", "UTILITY");
    v.put("primaryAggregator", "billagg");
    v.put(
        "fields",
        List.of(Map.of("name", "accountNo", "labelEn", "Account", "labelBn", "হিসাব", "regex", "^[0-9]{8}$", "required", true)));
    v.put("amountRule", "FIXED");
    v.put("billMonthRequired", false);
    v.put("enabled", true);
    return v;
  }

  @Test
  void aCompleteBillerHasNoProblems() {
    assertThat(BillerRules.problems(biller())).isEmpty();
  }

  @Test
  void aFieldRegexMustCompile() {
    Map<String, Object> v = biller();
    v.put("fields", List.of(Map.of("name", "accountNo", "labelEn", "A", "labelBn", "B", "regex", "^[0-9", "required", true)));
    assertThat(BillerRules.problems(v)).anyMatch(p -> p.contains("regex"));
  }

  @Test
  void aCatastrophicRegexIsRefusedAndASafeOneAccepted() {
    for (String bad :
        List.of(
            "(a+)+$", "(a|a)*", "(\\d|\\d\\d)+", "^(.*)*$", "^(?:SA|SB)-[0-9]+$", "^a|b$", "^(a|aa)+\\1$",
            "^(?=a)[0-9]{8}$", "^a**$", "^\\d{1,65}$", "^\\d{3,}$", "^\\b\\d$", "^[a[b]]$",
            "^\\d?\\d?\\d?\\d?\\d?\\d?\\d?\\d?\\d?$", "^\\d*\\d*\\d*$", "\\w+\\.\\d*", "^\\d{2,3}[A-Z]?$")) {
      Map<String, Object> v = biller();
      v.put("fields", List.of(Map.of("name", "accountNo", "labelEn", "A", "labelBn", "B", "regex", bad, "required", true)));
      assertThat(BillerRules.problems(v)).as(bad).anyMatch(p -> p.contains("regex"));
    }
    for (String good :
        List.of("^\\d{10,16}$", "^[A-Z]{2}\\d{6}$", "^[0-9]{8,12}$", "^0[0-9]{9,10}$", "^[A-Za-z0-9/-]{5,20}$", "^[A-Z]{2}\\d{6,10}$", "^[A-Z]{2}-\\d{4}\\.\\w+$")) {
      Map<String, Object> v = biller();
      v.put("fields", List.of(Map.of("name", "accountNo", "labelEn", "A", "labelBn", "B", "regex", good, "required", true)));
      assertThat(BillerRules.problems(v)).as(good).isEmpty();
    }
  }

  @Test
  void amountBoundsFollowTheRule() {
    Map<String, Object> open = biller();
    open.put("amountRule", "OPEN");
    open.put("minAmountMinor", 500);
    open.put("maxAmountMinor", 100);
    assertThat(BillerRules.problems(open)).anyMatch(p -> p.contains("maxAmountMinor"));

    Map<String, Object> minimum = biller();
    minimum.put("amountRule", "MINIMUM");
    assertThat(BillerRules.problems(minimum)).anyMatch(p -> p.contains("minAmountMinor"));

    Map<String, Object> other = biller();
    other.put("amountRule", "ANY");
    assertThat(BillerRules.problems(other)).anyMatch(p -> p.contains("amountRule"));
  }

  @Test
  void aDisabledBillerNeedsItsMaintenanceTextInBothLanguages() {
    Map<String, Object> v = biller();
    v.put("enabled", false);
    assertThat(BillerRules.problems(v)).anyMatch(p -> p.contains("maintenance"));
    v.put("maintenanceEn", "Down");
    v.put("maintenanceBn", "বন্ধ");
    assertThat(BillerRules.problems(v)).isEmpty();
  }
}
