package mfs.mw.app.gateway.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import mfs.mw.app.gateway.dto.Biller;
import mfs.mw.app.gateway.dto.ConfigChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * This gateway's read projection of the biller catalogue (ticket 113b, copied from
 * mw-adapter-billpay's): mw-admin-api owns the catalogue and publishes each entry on {@code
 * mw.config.changed.v1} (BILLER, key {@code biller/<code>}, full value); {@code
 * messaging/BillerCatalogKafkaListener} replays the compacted topic on every start into this
 * in-memory snapshot. An older version than the one held is ignored; a DELETE removes the entry; a
 * value that does not parse (or whose field regex does not compile) is skipped and logged.
 */
@Component
public class BillerCatalog {

  private static final Logger log = LoggerFactory.getLogger(BillerCatalog.class);

  private record Held(Biller biller, long version) {}

  private final Map<String, Held> billers = new ConcurrentHashMap<>();

  public Optional<Biller> find(String code) {
    return Optional.ofNullable(code == null ? null : billers.get(code)).map(Held::biller);
  }

  /** Every entry, sorted by code (empty until the first BILLER record arrives). */
  public List<Biller> list() {
    return billers.values().stream().map(Held::biller).sorted(Comparator.comparing(Biller::code)).toList();
  }

  /** Applies a {@code mw.config.changed.v1} record; anything but BILLER is ignored. */
  public void apply(ConfigChangedEvent event) {
    if (event == null
        || event.configType() != ConfigChangedEvent.ConfigType.BILLER
        || event.configKey() == null
        || event.change() == null) {
      return;
    }
    String code = event.configKey().substring(event.configKey().indexOf('/') + 1);
    if (event.change() == ConfigChangedEvent.Change.DELETE) {
      billers.computeIfPresent(code, (k, held) -> held.version() <= event.version() ? null : held);
      return;
    }
    Biller next;
    try {
      next = parse(code, event.value());
    } catch (RuntimeException e) {
      log.atWarn()
          .addKeyValue("event", "biller.config_refused")
          .addKeyValue("billerCode", code)
          .addKeyValue("errorClass", e.getClass().getName())
          .log("biller record skipped");
      return;
    }
    billers.merge(code, new Held(next, event.version()),
        (held, incoming) -> incoming.version() >= held.version() ? incoming : held);
    log.atInfo()
        .addKeyValue("event", "biller.config_applied")
        .addKeyValue("billerCode", code)
        .addKeyValue("version", event.version())
        .log("biller catalogue entry applied");
  }

  private static Biller parse(String code, Map<String, Object> v) {
    List<Biller.Field> fields = new ArrayList<>();
    for (Object raw : (List<?>) v.get("fields")) {
      Map<?, ?> f = (Map<?, ?>) raw;
      String regex = text(f.get("regex"));
      Pattern.compile(regex);
      fields.add(new Biller.Field(text(f.get("name")), text(f.get("labelEn")), text(f.get("labelBn")), regex,
          Boolean.TRUE.equals(f.get("required"))));
    }
    String rule = text(v.get("amountRule"));
    if (!List.of("FIXED", "MINIMUM", "OPEN").contains(rule)) {
      throw new IllegalArgumentException("amountRule");
    }
    return new Biller(
        code,
        text(v.get("nameEn")),
        text(v.get("category")),
        text(v.get("primaryAggregator")),
        text(v.get("nameBn")),
        List.copyOf(fields),
        rule,
        minor(v.get("minAmountMinor")),
        minor(v.get("maxAmountMinor")),
        Boolean.TRUE.equals(v.get("billMonthRequired")),
        !Boolean.FALSE.equals(v.get("enabled")),
        (String) v.get("maintenanceEn"),
        (String) v.get("maintenanceBn"));
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
