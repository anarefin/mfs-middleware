package mfs.mw.ussd.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import mfs.mw.ussd.dto.Biller;
import mfs.mw.ussd.dto.ConfigChangedEvent;
import mfs.mw.ussd.repository.BillerCatalogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * This service's projection of mw-admin-api's biller catalogue (ticket 113b): {@code
 * mw.config.changed.v1} BILLER records (key {@code biller/<code>}, full value) arrive through
 * {@code messaging/ConfigChangedListener} (the retried listener of the menus; its group commits, so
 * the projection is stored, {@code ussd.biller_catalog}, not rebuilt in memory). An older version
 * than the stored one is ignored, a DELETE removes the entry, a value that does not parse (or whose
 * field regex does not compile) is logged and dropped: the same record cannot get better. Other
 * config types are ignored.
 */
@Service
public class BillerCatalog {

  private static final Logger log = LoggerFactory.getLogger(BillerCatalog.class);

  private final BillerCatalogRepository repository;
  private final JsonMapper json;

  public BillerCatalog(BillerCatalogRepository repository, JsonMapper json) {
    this.repository = repository;
    this.json = json;
  }

  public void onConfigChanged(ConfigChangedEvent e) {
    if (e == null || e.configType() != ConfigChangedEvent.ConfigType.BILLER || e.configKey() == null
        || e.change() == null) {
      return;
    }
    String code = e.configKey().substring(e.configKey().indexOf('/') + 1);
    if (e.change() == ConfigChangedEvent.Change.DELETE) {
      repository.deleteIfNotNewer(code, e.version());
      return;
    }
    try {
      parse(code, e.value());
    } catch (RuntimeException bad) {
      log.atWarn()
          .addKeyValue("event", "biller.config_refused")
          .addKeyValue("billerCode", code)
          .addKeyValue("errorClass", bad.getClass().getName())
          .log("biller record skipped");
      return;
    }
    boolean stored = repository.upsertIfNewer(code, e.version(), json.writeValueAsString(e.value()));
    log.atInfo()
        .addKeyValue("event", stored ? "biller.config_applied" : "biller.config_skipped")
        .addKeyValue("billerCode", code)
        .addKeyValue("version", e.version())
        .log(stored ? "biller catalogue entry applied" : "biller catalogue entry older than the stored one");
  }

  /** Every stored biller, sorted by code (empty until the first BILLER record arrives). */
  @SuppressWarnings("unchecked")
  public List<Biller> list() {
    List<Biller> out = new ArrayList<>();
    for (BillerCatalogRepository.Row row : repository.all()) {
      try {
        out.add(parse(row.code(), json.readValue(row.value(), Map.class)));
      } catch (RuntimeException e) {
        log.atWarn()
            .addKeyValue("event", "biller.config_unreadable")
            .addKeyValue("billerCode", row.code())
            .addKeyValue("errorClass", e.getClass().getName())
            .log("stored biller skipped");
      }
    }
    return out;
  }

  static Biller parse(String code, Map<String, Object> v) {
    List<Biller.Field> fields = new ArrayList<>();
    for (Object raw : (List<?>) v.get("fields")) {
      Map<?, ?> f = (Map<?, ?>) raw;
      String regex = text(f.get("regex"));
      Pattern.compile(regex);
      fields.add(new Biller.Field(text(f.get("name")), text(f.get("labelEn")), text(f.get("labelBn")), regex,
          Boolean.TRUE.equals(f.get("required"))));
    }
    return new Biller(
        code,
        text(v.get("nameEn")),
        text(v.get("category")),
        text(v.get("primaryAggregator")),
        text(v.get("nameBn")),
        List.copyOf(fields),
        !Boolean.FALSE.equals(v.get("enabled")),
        (String) v.get("maintenanceEn"),
        (String) v.get("maintenanceBn"),
        Boolean.TRUE.equals(v.get("billMonthRequired")));
  }

  private static String text(Object raw) {
    if (raw instanceof String s && !s.isBlank()) {
      return s;
    }
    throw new IllegalArgumentException("missing text");
  }
}
