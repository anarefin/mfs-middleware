package mfs.mw.adapter.billpay.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import mfs.mw.adapter.billpay.config.BillerProperties;
import mfs.mw.adapter.billpay.dto.ConfigChangedEvent;
import mfs.mw.adapter.billpay.dto.ErrorDetail;
import mfs.mw.adapter.billpay.exception.ErrorCodes;
import mfs.mw.adapter.billpay.exception.MwException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * This adapter's read projection of the biller catalogue (ticket 113): mw-admin-api owns the
 * catalogue and publishes each entry on {@code mw.config.changed.v1} (BILLER, key {@code
 * biller/<code>}, full value); {@code messaging/BillerCatalogKafkaListener} replays the compacted
 * topic on every start into this in-memory snapshot. The static {@code mw.billers.*} entries are
 * version-0 seeds a record replaces. An older version than the one held is ignored; a value that
 * does not parse is skipped (logged).
 */
@Component
public class BillerCatalog {

  private static final Logger log = LoggerFactory.getLogger(BillerCatalog.class);
  /** Longest customer input a field regex is matched against. */
  static final int MAX_INPUT = 64;

  private static final Pattern BILL_MONTH = Pattern.compile("^[0-9]{4}(0[1-9]|1[0-2])$");

  /** One input field of a biller; {@code regex} compiled once. */
  public record Field(String name, String labelEn, String labelBn, Pattern regex, boolean required) {}

  /**
   * One catalogue entry. {@code amountRule} FIXED / MINIMUM / OPEN with optional bounds in poisha;
   * a seed from static config has no fields and the OPEN rule.
   */
  public record Biller(
      String code,
      String nameEn,
      String nameBn,
      String category,
      String partnerId,
      String fallbackPartnerId,
      List<Field> fields,
      String amountRule,
      Long minAmountMinor,
      Long maxAmountMinor,
      boolean billMonthRequired,
      boolean enabled,
      String maintenanceEn,
      String maintenanceBn,
      long version) {

    /**
     * Refuses a bill fetch the catalogue rules out: a disabled biller with its bn/en maintenance
     * text (503 {@code MW-SYS-BILLER_DISABLED}, FRS PLT-043), a bill number not matching the first
     * required field's regex, a missing or malformed {@code billMonth} (YYYYMM) when required (400
     * {@code MW-VAL-001}). Messages never repeat the value.
     */
    public void checkFetch(String billNumber, String billMonth) {
      if (!enabled) {
        throw new MwException(
                "MW-SYS-BILLER_DISABLED",
                maintenanceEn == null ? "This biller is not available now" : maintenanceEn)
            .withStatus(HttpStatus.SERVICE_UNAVAILABLE)
            .withRetryable(false)
            .withMessageBn(maintenanceBn == null ? "এই বিলার এখন পাওয়া যাচ্ছে না" : maintenanceBn);
      }
      Field key = fields.stream().filter(Field::required).findFirst().orElse(null);
      // Input capped before any catalogue regex runs on it (security review: bounded matching even
      // if a regex slipped past mw-admin-api's safe-subset check).
      if (billNumber != null && billNumber.length() > MAX_INPUT) {
        throw invalid(key == null ? "billNumber" : key.name(), "The bill number is too long");
      }
      if (key != null && (billNumber == null || !key.regex().matcher(billNumber).matches())) {
        throw invalid(key.name(), key.labelEn() + " is not valid for this biller");
      }
      if (billMonth != null && !BILL_MONTH.matcher(billMonth).matches()) {
        throw invalid("billMonth", "billMonth is YYYYMM");
      }
      if (billMonthRequired && billMonth == null) {
        throw invalid("billMonth", "This biller needs billMonth (YYYYMM)");
      }
    }

    private static MwException invalid(String field, String issue) {
      return new MwException(ErrorCodes.VAL_INVALID, issue).withDetails(List.of(new ErrorDetail(field, issue)));
    }
  }

  private final Map<String, Biller> billers = new ConcurrentHashMap<>();

  public BillerCatalog(BillerProperties seeds) {
    seeds.billers().forEach(
        (code, b) ->
            billers.put(
                code,
                new Biller(code, b.name(), b.name(), b.category(), b.partnerId(), null, List.of(), "OPEN", null, null,
                    false, true, null, null, 0)));
  }

  public Optional<Biller> find(String code) {
    return Optional.ofNullable(billers.get(code));
  }

  /** Every entry, sorted by code. */
  public List<Biller> list() {
    return billers.values().stream().sorted(Comparator.comparing(Biller::code)).toList();
  }

  /** Applies a {@code mw.config.changed.v1} record; anything but BILLER is ignored. */
  public void apply(ConfigChangedEvent event) {
    if (event == null || event.configType() != ConfigChangedEvent.ConfigType.BILLER || event.configKey() == null) {
      return;
    }
    String code = event.configKey().substring(event.configKey().indexOf('/') + 1);
    if (event.change() == ConfigChangedEvent.Change.DELETE) {
      billers.computeIfPresent(code, (k, held) -> held.version() <= event.version() ? null : held);
      return;
    }
    Biller next;
    try {
      next = parse(code, event.version(), event.value());
    } catch (RuntimeException e) {
      log.atWarn()
          .addKeyValue("event", "biller.config_refused")
          .addKeyValue("billerCode", code)
          .addKeyValue("errorClass", e.getClass().getName())
          .log("biller record skipped");
      return;
    }
    billers.merge(code, next, (held, incoming) -> incoming.version() >= held.version() ? incoming : held);
    log.atInfo()
        .addKeyValue("event", "biller.config_applied")
        .addKeyValue("billerCode", code)
        .addKeyValue("version", event.version())
        .log("biller catalogue entry applied");
  }

  private static Biller parse(String code, long version, Map<String, Object> v) {
    List<Field> fields = new ArrayList<>();
    for (Object raw : (List<?>) v.get("fields")) {
      Map<?, ?> f = (Map<?, ?>) raw;
      fields.add(
          new Field(
              text(f.get("name")),
              text(f.get("labelEn")),
              text(f.get("labelBn")),
              Pattern.compile(text(f.get("regex"))),
              Boolean.TRUE.equals(f.get("required"))));
    }
    String rule = text(v.get("amountRule"));
    if (!List.of("FIXED", "MINIMUM", "OPEN").contains(rule)) {
      throw new IllegalArgumentException("amountRule");
    }
    return new Biller(
        code,
        text(v.get("nameEn")),
        text(v.get("nameBn")),
        text(v.get("category")),
        text(v.get("primaryAggregator")),
        (String) v.get("fallbackAggregator"),
        List.copyOf(fields),
        rule,
        minor(v.get("minAmountMinor")),
        minor(v.get("maxAmountMinor")),
        Boolean.TRUE.equals(v.get("billMonthRequired")),
        !Boolean.FALSE.equals(v.get("enabled")),
        (String) v.get("maintenanceEn"),
        (String) v.get("maintenanceBn"),
        version);
  }

  private static String text(Object raw) {
    if (raw instanceof String s && !s.isBlank()) {
      return s;
    }
    throw new IllegalArgumentException("missing text");
  }

  private static Long minor(Object raw) {
    return raw instanceof Number n ? n.longValue() : null;
  }
}
