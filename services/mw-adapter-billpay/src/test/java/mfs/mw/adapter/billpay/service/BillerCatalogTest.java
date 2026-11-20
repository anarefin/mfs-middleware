package mfs.mw.adapter.billpay.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mfs.mw.adapter.billpay.config.BillerProperties;
import mfs.mw.adapter.billpay.dto.ConfigChangedEvent;
import mfs.mw.adapter.billpay.exception.MwException;
import org.junit.jupiter.api.Test;

/** The biller projection (ticket 113): BILLER records over the static seed, field and month checks. */
class BillerCatalogTest {

  final BillerCatalog catalog =
      new BillerCatalog(
          new BillerProperties(Map.of("WASA", new BillerProperties.Biller("Dhaka WASA", "WATER", "billagg"))));

  static Map<String, Object> desco(boolean enabled) {
    Map<String, Object> v = new LinkedHashMap<>();
    v.put("code", "DESCO");
    v.put("nameEn", "Dhaka Electric Supply Company");
    v.put("nameBn", "ডেসকো");
    v.put("category", "UTILITY");
    v.put("primaryAggregator", "billagg");
    v.put("fallbackAggregator", "ekpay");
    v.put(
        "fields",
        List.of(Map.of("name", "accountNo", "labelEn", "Account", "labelBn", "হিসাব", "regex", "^[0-9]{8}$", "required", true)));
    v.put("amountRule", "FIXED");
    v.put("billMonthRequired", true);
    v.put("enabled", enabled);
    if (!enabled) {
      v.put("maintenanceEn", "DESCO is under maintenance");
      v.put("maintenanceBn", "ডেসকো রক্ষণাবেক্ষণে আছে");
    }
    return v;
  }

  static ConfigChangedEvent event(long version, Map<String, Object> value) {
    return new ConfigChangedEvent(
        1, "biller/DESCO", ConfigChangedEvent.ConfigType.BILLER, ConfigChangedEvent.Change.UPSERT, version, value,
        Instant.parse("2026-11-20T09:00:00Z"), "ops-maker01", "ops-checker01", null);
  }

  @Test
  void aBillerRecordJoinsTheSeedAndAnOlderVersionIsIgnored() {
    catalog.apply(event(2, desco(true)));
    catalog.apply(event(1, desco(false)));
    assertThat(catalog.list()).extracting(b -> b.code()).containsExactly("DESCO", "WASA");
    assertThat(catalog.find("DESCO").orElseThrow().enabled()).isTrue();
    assertThat(catalog.find("DESCO").orElseThrow().partnerId()).isEqualTo("billagg");
  }

  @Test
  void theBillNumberMustMatchTheFieldRegexAndTheMonthIsRequired() {
    catalog.apply(event(1, desco(true)));
    BillerCatalog.Biller b = catalog.find("DESCO").orElseThrow();
    assertThatThrownBy(() -> b.checkFetch("1234", "202611"))
        .isInstanceOfSatisfying(MwException.class, e -> assertThat(e.code()).isEqualTo("MW-VAL-001"));
    assertThatThrownBy(() -> b.checkFetch("12345678", null))
        .isInstanceOfSatisfying(MwException.class, e -> assertThat(e.code()).isEqualTo("MW-VAL-001"));
    assertThatThrownBy(() -> b.checkFetch("12345678", "202613"))
        .isInstanceOfSatisfying(MwException.class, e -> assertThat(e.code()).isEqualTo("MW-VAL-001"));
    b.checkFetch("12345678", "202611");
    assertThatThrownBy(() -> b.checkFetch("1".repeat(65), "202611"))
        .isInstanceOfSatisfying(MwException.class, e -> assertThat(e.code()).isEqualTo("MW-VAL-001"));
  }

  @Test
  void aDisabledBillerIsRefusedWithItsMaintenanceText() {
    catalog.apply(event(1, desco(false)));
    BillerCatalog.Biller b = catalog.find("DESCO").orElseThrow();
    assertThatThrownBy(() -> b.checkFetch("12345678", "202611"))
        .isInstanceOfSatisfying(
            MwException.class,
            e -> {
              assertThat(e.code()).isEqualTo("MW-SYS-BILLER_DISABLED");
              assertThat(e.getMessage()).isEqualTo("DESCO is under maintenance");
              assertThat(e.messageBn()).isEqualTo("ডেসকো রক্ষণাবেক্ষণে আছে");
            });
  }

  @Test
  void otherConfigTypesAndBrokenValuesAreSkipped() {
    catalog.apply(
        new ConfigChangedEvent(
            1, "partner/billagg", ConfigChangedEvent.ConfigType.PARTNER, ConfigChangedEvent.Change.UPSERT, 1, Map.of(),
            Instant.now(), "a", "b", null));
    catalog.apply(event(1, Map.of("code", "DESCO")));
    assertThat(catalog.find("DESCO")).isEmpty();
  }
}
